package io.mszymanski.orknux.shell

import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/** The tokens and money one chat has spent. Cost is what this shell saw costed; a resumed chat's earlier turns are not in it. */
data class Usage(val inputTokens: Long = 0, val outputTokens: Long = 0, val cachedInputTokens: Long = 0, val cost: Double? = null)

/**
 * Where the person is: the workspace, the agent, the chat and the mode, and
 * sending a message in it.
 *
 * A chat is opened lazily, on the first message after a start or a `/clear`,
 * so that switching agents or workspaces before saying anything opens nothing.
 */
class Conversation(val api: Api, var workspace: Workspace, var agent: Agent?, var mode: String) {
    @Volatile
    var chat: Chat? = null
        private set

    @Volatile
    var usage = Usage()
        private set

    /** The reasoning effort asked for before the chat existed; set on it once it does. */
    @Volatile
    var effort: String? = null

    /** The LLM session the chat records into, as the stream says it: where its subagents are found. */
    @Volatile
    var llmSessionId: String? = null
        private set

    /** How fast the model reads the prompt and writes its answer, as this conversation last measured them. */
    val speeds = Speeds()

    private val body = AtomicReference<InputStream?>(null)

    @Volatile
    var interruptedOnce = false
        private set

    fun clear() {
        chat = null
        usage = Usage()
        llmSessionId = null
    }

    fun resume(chosen: Chat) {
        chat = chosen
        llmSessionId = chosen.llmSessionId
        effort = chosen.reasoningEffort
        usage = Usage(chosen.inputTokens, chosen.outputTokens, chosen.cachedInputTokens, null)
        mode = when (chosen.mode) {
            "PLAN" -> Modes.PLAN
            else -> if (mode == Modes.PLAN) Modes.AUTO else mode
        }
    }

    /** Switches agent: on the open chat where there is one, and for the next chat either way. */
    fun choose(next: Agent) {
        agent = next
        chat?.let { chat = api.chooseAgent(it.id, next.id) }
    }

    /** Puts the open chat in the server's mode for [mode]; nothing to do before a chat is open. */
    fun syncMode() {
        val open = chat ?: return
        val wanted = Modes.serverMode(mode)
        if (open.mode != wanted) chat = api.setMode(open.id, wanted)
    }

    /** Sets the reasoning effort, on the chat where one is open; null is the model's own. */
    fun chooseEffort(value: String?) {
        effort = value
        chat?.let { chat = api.setEffort(it.id, value) }
    }

    /** Opens the chat now, if it is not open yet. */
    fun open(): Chat = chat ?: api.startChat(workspace.id, agent?.id).also { started ->
        chat = started
        llmSessionId = started.llmSessionId
        if (agent == null && started.agentId != null) agent = Agent(started.agentId, started.agentName ?: "agent", null, true, emptyList(), emptyList())
        effort?.let { wanted -> runCatching { chat = api.setEffort(started.id, wanted) } }
    }

    /**
     * Says [text], with [attachmentIds] uploaded beforehand, and hands every
     * frame of the answer to [onEvent] as it arrives. Returns when the stream
     * ends: answered, failed, or interrupted.
     */
    fun send(text: String, attachmentIds: List<Long> = emptyList(), onEvent: (ChatEvent) -> Unit) {
        val open = open()
        syncMode()
        interruptedOnce = false
        val request = Json.obj().put("text", text)
        val ids = request.putArray("attachmentIds")
        attachmentIds.forEach { ids.add(it) }
        request.put("voice", false)
        try {
            api.client.stream("/api/chats/${open.id}/stream", request, opened = { body.set(it) }) { frame ->
                val event = ChatEvent.of(frame)
                when (event) {
                    // Counted at once, so the status line moves as the turn ends; the chat's own totals replace it after.
                    is ChatEvent.Done -> {
                        usage = usage.copy(
                            inputTokens = usage.inputTokens + (event.inputTokens ?: 0),
                            outputTokens = usage.outputTokens + (event.outputTokens ?: 0),
                            cachedInputTokens = usage.cachedInputTokens + (event.cachedInputTokens ?: 0),
                            cost = event.cost?.let { (usage.cost ?: 0.0) + it } ?: usage.cost,
                        )
                        event.generatedPerSecond?.takeIf { it > 0 }?.let { speeds.decoded(it) }
                    }
                    is ChatEvent.Reading -> speeds.read(event.processed)
                    is ChatEvent.Titled -> chat = chat?.copy(title = event.title)
                    is ChatEvent.Session -> llmSessionId = event.id
                    else -> {}
                }
                onEvent(event)
            }
        } finally {
            body.set(null)
        }
        refresh()
    }

    /** Re-reads the chat for its running totals, which the server keeps. */
    fun refresh() {
        val open = chat ?: return
        runCatching { api.chat(open.id) }.getOrNull()?.let { fresh ->
            chat = fresh
            fresh.llmSessionId?.let { llmSessionId = it }
            usage = usage.copy(inputTokens = fresh.inputTokens, outputTokens = fresh.outputTokens, cachedInputTokens = fresh.cachedInputTokens)
        }
    }

    /** A turn the server runs by itself, followed from here: Ctrl+C stops it as it stops one sent from here. */
    fun following(stream: InputStream) {
        interruptedOnce = false
        body.set(stream)
    }

    fun followed() = body.set(null)

    /** First press asks the server to stop the answer; the second stops waiting for it. */
    fun interrupt(): String {
        val open = chat ?: return "Nothing is running."
        return if (!interruptedOnce) {
            interruptedOnce = true
            runCatching { api.interrupt(open.id) }
            "Interrupting… (Ctrl+C again to stop waiting)"
        } else {
            runCatching { body.get()?.close() }
            "Stopped waiting for the answer."
        }
    }
}

/** Where a turn is drawn: whole lines into the transcript, and the live part under it. */
interface TurnOutput {
    fun line(text: String)

    /**
     * What is happening now, for the live line. Its clock runs from when [key]
     * last changed: the same tool call being written keeps its key as its count grows.
     */
    fun phase(key: String, text: String)

    /** The thinking streaming now, for the live block above the indicator; null once it has ended. */
    fun thinking(text: String?) {}

    /** A piece of work kept two ways - folded and in full - of which the screen shows one. */
    fun lines(folded: List<String>, full: List<String>) = folded.forEach(::line)

    fun width(): Int = 100
}

/** One stretch of thinking: a turn can think, call a tool, think again and answer, and each is its own block. [millis] is negative where unknown. */
data class ThinkingBlock(val text: String, val millis: Long)

/**
 * A model's two speeds, in tokens a second: eval, how fast it reads the
 * prompt - measured from the server's reading progress as it arrives - and
 * decode, how fast it writes, as the server measured it for the turn.
 * Null where the provider reports nothing to measure them from.
 */
class Speeds(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var eval: Double? = null
        private set
    @Volatile var decode: Double? = null
        private set
    private var since = 0L
    private var from = 0L
    private var last = -1L

    /** A reading report: [processed] tokens of the prompt so far; a smaller number than the last is the next request. */
    fun read(processed: Long) {
        val now = clock()
        if (processed < last || last < 0) {
            since = now
            from = processed
        } else if (now - since >= 300 && processed > from) {
            eval = (processed - from) * 1000.0 / (now - since)
        }
        last = processed
    }

    fun decoded(perSecond: Double) {
        decode = perSecond
        last = -1
    }

    /** "eval 1.2k t/s · decode 48 t/s", what is known of it, or null. */
    fun said(): String? = listOfNotNull(
        eval?.let { "eval ${TurnRenderer.tokens(it.toLong())} t/s" },
        decode?.let { "decode ${if (it >= 100) it.toLong().toString() else "%.1f".format(it)} t/s" },
    ).joinToString(" · ").ifEmpty { null }
}

/** One piece of a turn's work, kept whole for Ctrl+O: a thought, or a tool call with all it was given and gave back. */
sealed interface Detail {
    data class Thought(val block: ThinkingBlock) : Detail
    data class Tool(val name: String, val arguments: String, val result: String, val failed: Boolean, val millis: Long?) : Detail
}

/**
 * A turn's frames, made into what the person reads.
 *
 * The answer is drawn as markdown, a line at a time. Thinking is shown live
 * while it streams and folded to one line once something else begins - or
 * written out in full, or left out, as the `thinking` setting says. A tool
 * call being written, a tool running and an agent being asked are the live
 * line while they last and one line each once they are over.
 */
class TurnRenderer(
    private val theme: Theme,
    private val out: TurnOutput,
    private val thinkingMode: String = "collapsed",
    links: Links = Links(false, null),
) {
    private val markdown = Markdown(theme, { out.width() }, links)
    private val thinking = StringBuilder()
    private var thinkingStarted = 0L
    private val calls = HashMap<Int, ChatEvent.Call>()
    private val callStarted = HashMap<Int, Long>()

    /** This turn's thinking, every stretch in order: what Ctrl+O shows again. */
    val blocks = mutableListOf<ThinkingBlock>()

    /** The turn's work in order, whole: what Ctrl+O shows. */
    val details = mutableListOf<Detail>()

    /** Every link the answer drew, numbered from 1 by its place here. */
    val links: List<Pair<String, String>> get() = markdown.found

    /** The answer's text as it came, for whoever reads it after the turn - `/review` lists its findings. */
    val answer = StringBuilder()

    @Volatile
    var answered = false
        private set

    /** When the thinking now streaming began, for the live block's clock; 0 when none is. */
    val thinkingSince: Long get() = if (thinking.isEmpty()) 0 else thinkingStarted

    /** What was written last: the work (thinking, tool calls, notes) or the answer. */
    private enum class Kind { WORK, ANSWER }
    private var lastKind: Kind? = null

    /**
     * Lines with room around them: a blank line before every piece of work - a
     * thought, a tool call, a note - so each reads as its own, and before the
     * answer; none inside the answer, which arrives in pieces as it streams.
     */
    private fun write(kind: Kind, lines: List<String>) {
        if (lines.isEmpty()) return
        if (lastKind != null && (kind == Kind.WORK || lastKind != kind)) out.line("")
        lastKind = kind
        lines.forEach(out::line)
    }

    private fun work(line: String) = write(Kind.WORK, listOf(line))

    /** Work kept both ways: spaced as any other, and the output decides which it shows. */
    private fun either(folded: List<String>, full: List<String>) {
        if (folded.isEmpty() && full.isEmpty()) return
        // The blank before it belongs to each view that shows something: a thought hidden when folded leaves none.
        fun spaced(lines: List<String>) = if (lines.isNotEmpty() && lastKind != null) listOf("") + lines else lines
        out.lines(spaced(folded), spaced(full))
        if (folded.isNotEmpty()) lastKind = Kind.WORK
    }

    private fun answerLines(rendered: String) {
        if (rendered.isNotEmpty()) write(Kind.ANSWER, rendered.removeSuffix("\n").split('\n'))
    }

    fun handle(event: ChatEvent) {
        if (event !is ChatEvent.Thinking && event !is ChatEvent.Thought) {
            closeThinking(null)
            justClosed = false
        }
        when (event) {
            is ChatEvent.Thinking -> {
                if (thinking.isEmpty()) thinkingStarted = System.currentTimeMillis()
                justClosed = false
                thinking.append(event.text)
                if (thinkingMode != "hidden") out.thinking(thinking.toString())
                out.phase("thinking", theme.thinking("Thinking"))
            }
            is ChatEvent.Thought -> closeThinking(event)
            // No tool named is the answer itself being written: the server counts its characters the same way.
            is ChatEvent.Writing -> out.phase(
                "writing:${event.tool}",
                (event.tool?.let { "Preparing a tool call: " + theme.accent(it) } ?: "Writing the answer") + theme.muted(" · ${event.chars} chars"),
            )
            is ChatEvent.Reading -> {
                // `processed` is how much of the prompt has been read, the cached part included (the server's
                // PromptProgress says so): it is the share by itself, with nothing added to it.
                val share = if (event.total > 0) " ${event.processed * 100 / event.total}%" else ""
                out.phase("reading", "Reading the conversation$share" + theme.muted(" · ${tokens(event.total)} tokens, ${tokens(event.cached)} cached"))
            }
            is ChatEvent.Call -> {
                calls[event.at] = event
                callStarted[event.at] = System.currentTimeMillis()
                val asked = askedAgent(event)
                out.phase(
                    "call:${event.at}",
                    if (asked != null) "Asking " + theme.accent(asked) + "…"
                    else "Running " + theme.accent(event.tool) + ": " + theme.muted(argumentSummary(event.arguments, out.width() - event.tool.length - 24)),
                )
            }
            is ChatEvent.Called -> {
                val call = calls.remove(event.at)
                val took = callStarted.remove(event.at)?.let { System.currentTimeMillis() - it }
                val tool = Detail.Tool(call?.tool ?: "tool", call?.arguments ?: "", event.result, event.failed, took)
                details += tool
                either(
                    listOf(calledLine(theme, call?.tool ?: "tool", call?.arguments ?: "", event.result, event.failed, took, out.width())),
                    detailLines(listOf(tool), theme, out.width()).dropLastWhile { it.isEmpty() },
                )
                out.phase("thinking", theme.thinking("Thinking"))
            }
            is ChatEvent.Chunk -> {
                answer.append(event.text)
                answerLines(markdown.feed(event.text))
                out.phase("writing-answer", "Writing")
            }
            is ChatEvent.Answered -> {
                flush()
                answered = true
            }
            is ChatEvent.Done -> flush()
            is ChatEvent.Error -> {
                flush()
                work(theme.danger("✗ " + event.reason))
            }
            is ChatEvent.Compacting -> out.phase("compacting", "Compacting the conversation")
            is ChatEvent.Compacted -> work(theme.muted("Compacted ${event.replaced} earlier turns into a summary (${tokens(event.tokens)} tokens)."))
            is ChatEvent.Drew -> answerLines(markdown.render(event.markdown))
            is ChatEvent.Filed -> work(theme.muted("File: ${event.filename}" + (event.contentType?.let { " ($it)" } ?: "")))
            // What the model says while it works: its own lines, trimmed - it arrives with newlines after it, which
            // written raw were rows the screen had not counted.
            is ChatEvent.Said -> event.text.trim().takeIf { it.isNotEmpty() }?.let { said -> write(Kind.WORK, said.lines().map { theme.muted(markdown.inline(it)) }) }
            else -> {}
        }
    }

    /** What is left of the answer, and the links it drew, numbered, once at the end. */
    fun flush() {
        closeThinking(null)
        answerLines(markdown.finish())
    }

    /** The answer's links, numbered for `/open`; nothing where there are none. */
    // Set when a stretch has been folded, so the `thought` the server sends after it does not fold it twice.
    private var justClosed = false

    private fun closeThinking(thought: ChatEvent.Thought?) {
        if (thinking.isEmpty() && (thought == null || justClosed)) return
        val text = thinking.toString().ifBlank { thought?.text ?: "" }
        if (text.isBlank()) return
        val millis = thought?.millis?.takeIf { it > 0 } ?: (System.currentTimeMillis() - thinkingStarted).takeIf { thinking.isNotEmpty() } ?: -1
        thinking.setLength(0)
        out.thinking(null)
        justClosed = true
        val block = ThinkingBlock(text, millis)
        blocks += block
        details += Detail.Thought(block)
        // Folded as the setting says; in full, whole. Where the setting is "expanded" both are the whole of it.
        either(fold(block, thinkingMode, theme, out.width()), fold(block, "expanded", theme, out.width()))
    }

    companion object {
        /**
         * A finished tool, as the one line it leaves: `✓ terminal_run git status · 0.4s · exit 0`,
         * or `✗` and what went wrong.
         */
        fun calledLine(theme: Theme, tool: String, arguments: String, result: String, failed: Boolean, millis: Long?, width: Int): String {
            val asked = if (tool == "ask_agent") runCatching { Json.parse(arguments).str("agent") }.getOrNull() ?: "an agent" else null
            val what = if (asked != null) "asked $asked" else argumentSummary(arguments, (width / 2).coerceAtLeast(20))
            val exit = runCatching { Json.parse(result) }.getOrNull()?.takeIf { it.isObject }
                ?.let { (if (it.get("result")?.isObject == true) it.get("result") else it).long("exitCode") }
            val bad = failed || (exit != null && exit != 0L)
            val time = millis?.let { " · " + seconds(it) } ?: ""
            val head = (if (bad) theme.danger("✗") else theme.success("✓")) + " " + theme.bold(tool) + theme.muted(" $what$time")
            val room = width - visibleLength(head) - 4
            val summary = resultSummary(result, room.coerceAtLeast(12))
            return head + theme.muted(" · ") + if (bad) theme.danger(summary) else theme.dim(theme.muted(summary))
        }

        /** A stretch of thinking as the transcript keeps it: one line, the whole of it, or nothing. */
        fun fold(block: ThinkingBlock, mode: String, theme: Theme, width: Int): List<String> = when (mode) {
            "hidden" -> emptyList()
            "expanded" -> listOf(theme.thinking("▾ Thought") + theme.muted(forHowLong(block.millis))) +
                wrapped(block.text, width - 4).map { "  " + theme.muted("│ ") + theme.dim(theme.muted(it)) }
            else -> listOf(theme.thinking("▸ Thought") + theme.muted(forHowLong(block.millis)))
        }

        /** Nothing where the time is not known - a line from history kept none - which is a negative [millis]. */
        private fun forHowLong(millis: Long) = if (millis >= 0) " for ${seconds(millis)}" else ""

        /**
         * A turn's work written out in full, for reading on a page of its own:
         * each thought whole, and each tool call with every argument and all
         * it answered - a command's output as the lines it was, JSON indented.
         */
        fun detailLines(details: List<Detail>, theme: Theme, width: Int): List<String> {
            val w = (width - 6).coerceAtLeast(20)
            fun readable(text: String): List<String> {
                val node = runCatching { Json.parse(text) }.getOrNull()
                if (node == null || !(node.isObject || node.isArray)) return text.lines()
                // A command's answer: its streams as they were printed, rather than as JSON strings.
                if (node.isObject && (node.has("stdout") || node.has("stderr"))) {
                    val rest = (node.deepCopy() as com.fasterxml.jackson.databind.node.ObjectNode).apply { remove("stdout"); remove("stderr") }
                    return listOfNotNull(
                        rest.takeIf { it.size() > 0 }?.let { r -> r.properties().joinToString(" · ") { (k, v) -> "$k ${if (v.isTextual) v.textValue() else v.toString()}" } },
                    ) + (node.get("stdout")?.asText()?.trimEnd()?.takeIf { it.isNotEmpty() }?.lines() ?: emptyList()) +
                        (node.get("stderr")?.asText()?.trimEnd()?.takeIf { it.isNotEmpty() }?.lines()?.map { theme.warning(it) } ?: emptyList())
                }
                return Json.pretty(node).lines()
            }
            fun indented(lines: List<String>) = lines.flatMap { line -> if (visibleLength(line) <= w) listOf(line) else wrapped(stripAnsi(line), w) }.map { "    $it" }
            return details.flatMap { detail ->
                when (detail) {
                    is Detail.Thought -> fold(detail.block, "expanded", theme, width) + ""
                    is Detail.Tool -> listOf(
                        (if (detail.failed) theme.danger("✗ ") else theme.success("✓ ")) + theme.bold(detail.name) +
                            (detail.millis?.let { theme.muted(" · %.1fs".format(it / 1000.0)) } ?: ""),
                        theme.muted("  given"),
                    ) + indented(readable(detail.arguments.ifBlank { "{}" })) + theme.muted("  answered") + indented(readable(detail.result)) + ""
                }
            }
        }

        fun wrapped(text: String, width: Int): List<String> {
            val w = width.coerceAtLeast(20)
            return text.trim().lines().flatMap { line ->
                if (line.length <= w) listOf(line) else {
                    val parts = mutableListOf<String>()
                    var rest = line
                    while (rest.length > w) {
                        val cut = rest.lastIndexOf(' ', w).takeIf { it > w / 2 } ?: w
                        parts += rest.substring(0, cut)
                        rest = rest.substring(cut).trimStart()
                    }
                    parts + rest
                }
            }
        }

        /** The agent an `ask_agent` call names, for "Asking Researcher…". */
        fun askedAgent(call: ChatEvent.Call): String? {
            if (call.tool != "ask_agent") return null
            return runCatching { Json.parse(call.arguments).str("agent") }.getOrNull() ?: "an agent"
        }

        fun oneLine(text: String): String = text.replace(Regex("\\s+"), " ").trim()

        fun seconds(millis: Long): String = if (millis < 60_000) String.format("%.1fs", millis / 1000.0) else "${millis / 60_000}m ${(millis % 60_000) / 1000}s"

        fun tokens(n: Long): String = when {
            n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
            n >= 10_000 -> "${n / 1000}k"
            n >= 1_000 -> String.format("%.1fk", n / 1000.0)
            else -> n.toString()
        }

        fun cost(c: Double?): String = when {
            c == null -> "–"
            c < 0.01 -> String.format("$%.4f", c)
            else -> String.format("$%.2f", c)
        }

        /** One line about a call's arguments: the command where there is one, else the first few fields. */
        fun argumentSummary(arguments: String, width: Int): String {
            val json = runCatching { Json.parse(arguments.ifBlank { "{}" }) }.getOrNull()
            val text = if (json != null && json.isObject) {
                listOf("command", "query", "path", "url", "name", "key", "text").firstNotNullOfOrNull { json.str(it) }
                    ?: json.properties().take(3).joinToString(", ") { (k, v) -> "$k=" + (if (v.isTextual) v.textValue() else v.toString()) }
            } else {
                arguments
            }
            return clip(oneLine(text), width)
        }

        /** One line about what a tool gave back: an error where it says one, else its first line. */
        fun resultSummary(result: String, width: Int): String {
            // A device's answer arrives as {"result": {...}}; what is worth a line is inside it.
            val json = runCatching { Json.parse(result) }.getOrNull()?.let { if (it.isObject && it.get("result")?.isObject == true) it.get("result") else it }
            val text = when {
                json != null && json.isObject && json.has("error") -> json.str("error") ?: result
                json != null && json.isObject && json.has("exitCode") -> "exit ${json.str("exitCode")}" + (json.str("stdout")?.let { " · " + oneLine(it) } ?: "")
                else -> result.lineSequence().firstOrNull { it.isNotBlank() } ?: "(nothing)"
            }
            val lines = result.lines().size
            val more = if (lines > 1 && json == null) " (+${lines - 1} lines)" else ""
            return clip(oneLine(text), (width - more.length).coerceAtLeast(10)) + more
        }

        fun clip(text: String, width: Int): String {
            val w = width.coerceAtLeast(8)
            return if (text.length <= w) text else text.take(w - 1) + "…"
        }
    }
}
