package io.mszymanski.orknux.shell

import org.jline.keymap.KeyMap
import org.jline.reader.Candidate
import org.jline.reader.Completer
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.MaskingCallback
import org.jline.reader.Reference
import org.jline.reader.UserInterruptException
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Attributes
import org.jline.terminal.Terminal
import org.jline.utils.AttributedString
import org.jline.utils.NonBlockingReader
import org.jline.utils.Status
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The chat in a terminal: a line editor, the transcript above it and a status
 * line under it, and the device door open in the background for as long as it
 * runs.
 *
 * Three phases, and who owns the keyboard in each: while the person types
 * ([Phase.READING]) the line editor does; while a turn runs ([Phase.TURN]) a
 * key thread does, in raw mode, listening for Ctrl+C, Esc and Ctrl+O and
 * answering approvals; in between ([Phase.OTHER]) the main thread does. A
 * command that needs asking about is queued for whichever owns it, so two
 * readers never fight over one keyboard.
 */
class Interactive(
    private val terminal: Terminal,
    private val store: ConfigStore,
    private var config: Config,
    private val api: Api,
    private val named: Pair<String?, String?> = null to null,
) {
    private enum class Phase { READING, TURN, OTHER }

    private class Pending(val request: RunRequest, val decision: Decision, val answer: CompletableFuture<Approval> = CompletableFuture())

    private val theme = Theme(config.theme)
    private val links = Links(Links.osc8For(config.links, theme.depth), api.client.server)
    private val out = Any()
    private val start: Path = Path.of(System.getProperty("user.dir"))
    private lateinit var reader: LineReader
    private var status: Status? = null
    private lateinit var conversation: Conversation
    /** The conversation a turn is running in: the main one, or a side one an `@agent` message opened. */
    @Volatile private var active: Conversation? = null
    private val sideChats = LinkedHashMap<String, Conversation>()
    private var workspaces: List<Workspace> = emptyList()
    private var agents: List<Agent> = emptyList()
    private var skills: List<Skill>? = null
    private lateinit var handler: CommandHandler
    private lateinit var device: DeviceLoop

    @Volatile private var phase = Phase.OTHER
    private lateinit var region: LiveRegion
    private val goals = GoalApi(api.client)
    @Volatile private var goal: GoalState? = null
    private var models: List<Model>? = null
    @Volatile private var liveThinking: String? = null
    @Volatile private var thinkingSince = 0L
    @Volatile private var wakeForApproval = false
    @Volatile private var lastTurn: TurnRenderer? = null
    private var lastThinking: List<ThinkingBlock> = emptyList()
    private val attachments = mutableListOf<Attachment>()
    private val approvals = LinkedBlockingQueue<Pending>()
    private var prefill: String? = null

    fun run(): Int {
        val drawable = terminal.type != Terminal.TYPE_DUMB && terminal.type != Terminal.TYPE_DUMB_COLOR
        if (drawable) takeScreen()
        status = runCatching { Status.getStatus(terminal) }.getOrNull()
        // Its rows taken now, on the empty screen: taken later, the first draw scrolled the banner away.
        runCatching { status?.update(List(barRows()) { AttributedString("") }) }
        reader = buildReader()
        region = LiveRegion(
            sink = { text -> terminal.writer().print(text); terminal.writer().flush() },
            width = ::width,
            ansi = drawable,
            theme = theme,
            unicode = LiveRegion.unicodeFor(terminal.encoding()?.name()),
        )
        terminal.handle(Terminal.Signal.INT) { if (phase == Phase.TURN) interruptTurn() }
        terminal.handle(Terminal.Signal.WINCH) {
            if (region.active) region.redraw() else {
                runCatching { status?.resize() }
                footer()
            }
        }

        val policy = CommandPolicy(config.commands)
        handler = CommandHandler(policy, CommandRunner(config.shell), ::approve, deviceReport(), { (active ?: conversation).mode }, ::keepRule)

        try {
            setUp()
        } catch (refused: OrknuxException) {
            println(theme.danger(refused.message ?: "Could not start."))
            return 1
        }
        policy.problems.forEach { say(theme.warning("Rule skipped: $it")) }
        theme.unknown.forEach { say(theme.warning("Theme entry ignored: $it")) }
        if (drawable) toFoot()

        device = DeviceLoop(api.client, handler, deviceReport())
        device.start()
        footer()
        lookForUpdate()
        try {
            loop()
        } finally {
            device.stop()
            runCatching { status?.update(emptyList()) }
            runCatching { status?.close() }
            runCatching { reader.history.save() }
        }
        return 0
    }

    /**
     * The whole window: what the shell had on it is scrolled up into the
     * terminal's scrollback - not wiped, and not an alternate screen, so the
     * conversation itself stays in the scrollback too and a mouse wheel reads
     * back through it - and the cursor put at the top of the clean screen,
     * where the banner is written. [toFoot] then takes it to the foot.
     */
    private fun takeScreen() {
        val rows = terminal.height.takeIf { it > 0 } ?: return
        terminal.writer().print("\u001b[$rows;1H" + "\n".repeat(rows) + "\u001b[H")
        terminal.writer().flush()
    }

    /**
     * The banner stays at the top, and the prompt goes to the row directly
     * above the status bar, as in Claude Code: everything after is written
     * there and scrolls up, the banner with it once the screen is full.
     */
    private fun toFoot() {
        val rows = terminal.height.takeIf { it > 0 } ?: return
        terminal.writer().print("\u001b[${(rows - barRows()).coerceAtLeast(1)};1H")
        terminal.writer().flush()
    }

    // ---- setting up --------------------------------------------------------------------------

    private fun setUp() {
        val who = config.user?.let { " as $it" } ?: ""
        say(theme.bold(theme.accent("Orknux Shell")) + theme.muted(" ${Version.VERSION} · ${api.client.server}$who"))
        api.compatibility()?.let { (ok, reason) -> if (!ok) throw OrknuxException(reason ?: "This server does not take this client.") }
        val workspace = Connection.workspace(api, config, named.first) { all -> chooseFrom("Workspaces", all) { it.name } }
            ?: throw OrknuxException("No workspace chosen.")
        agents = api.agents(workspace.id).filter { it.enabled }
        val agent = Connection.agent(agents, config, named.second)
        conversation = Conversation(api, workspace, agent, config.mode)
        remember()
        say(
            theme.muted("Workspace ") + theme.text(workspace.name) + theme.muted(" · agent ") +
                theme.text(agent?.name ?: "the one you last talked to") + theme.muted(" · mode ") + modeLabel(),
        )
        say(theme.muted("/help for commands · @agent to ask another agent · Ctrl+O the last turn in full · Shift+Tab switches mode · Ctrl+C interrupts · Ctrl+D quits"))
        say("")
    }

    private fun remember() {
        val c = conversation
        config = store.update {
            it.copy(workspaceId = c.workspace.id, workspace = c.workspace.name, agentId = c.agent?.id, agent = c.agent?.name, mode = c.mode)
        }
    }

    private fun buildReader(): LineReader {
        val parser = DefaultParser().apply {
            isEofOnEscapedNewLine = true
            isEofOnUnclosedQuote = false
            setEofOnUnclosedBracket()
            escapeChars = charArrayOf('\\')
            quoteChars = charArrayOf('"', '\'')
        }
        val historyFile = store.path.resolveSibling("orknux-shell.history")
        // What LineReaderBuilder.build() does, on a reader that can take its completion list away again.
        val r = ShellLineReader(terminal, { footer() }, ::underPrompt, ::pullDown).apply {
            setHistory(org.jline.reader.impl.history.DefaultHistory())
            setParser(parser)
            setCompleter(completer())
            setVariable(LineReader.HISTORY_FILE, historyFile)
            setVariable(LineReader.SECONDARY_PROMPT_PATTERN, "  ")
            setVariable(LineReader.INDENTATION, 2)
            // A lone Esc closes the list; a fifth of a second tells it from the start of Alt+key.
            setVariable(LineReader.AMBIGUOUS_BINDING, 200L)
            // The list in the theme's colours on the terminal's own background - JLine's own is a magenta slab.
            val accent = theme.hex("accent")
            for ((name, style) in listOf(
                LineReader.COMPLETION_STYLE_LIST_BACKGROUND to "bg:default",
                LineReader.COMPLETION_STYLE_BACKGROUND to "bg:default",
                LineReader.COMPLETION_STYLE_LIST_STARTING to "fg-rgb:$accent",
                LineReader.COMPLETION_STYLE_STARTING to "fg-rgb:$accent",
                LineReader.COMPLETION_STYLE_LIST_DESCRIPTION to "fg:bright-black",
                LineReader.COMPLETION_STYLE_DESCRIPTION to "fg:bright-black",
                LineReader.COMPLETION_STYLE_LIST_GROUP to "fg:bright-black,bold",
                LineReader.COMPLETION_STYLE_GROUP to "fg:bright-black,bold",
                LineReader.COMPLETION_STYLE_LIST_SELECTION to "fg:default,bg-rgb:#27354d",
                LineReader.COMPLETION_STYLE_SELECTION to "fg:default,bg-rgb:#27354d",
            )) setVariable(name, style)
            option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
            option(LineReader.Option.INSERT_BRACKET, false)
            option(LineReader.Option.AUTO_FRESH_LINE, true)
            option(LineReader.Option.CASE_INSENSITIVE, true)
            option(LineReader.Option.AUTO_MENU_LIST, false)
        }
        /*
         * The commands are listed as a / is typed, and narrowed with every key
         * after it, as in Claude Code: nobody should have to know that Tab is
         * what shows them. Only while the line is one word starting with /,
         * and the cursor at its end; past that, Tab completes as before.
         */
        /*
         * The commands under a / as it is typed, drawn by the Shell in the place
         * under the prompt the status line is drawn in, and moved through with the
         * keys here: Up and Down move the highlight, Tab completes it, Enter runs
         * it, Esc closes the list. JLine's own completion list padded itself to
         * the height of the window, which scrolled the conversation away now
         * that the prompt follows the text down. Tab elsewhere completes as before.
         */
        for (name in listOf(LineReader.SELF_INSERT, LineReader.BACKWARD_DELETE_CHAR)) {
            val plain = r.widgets[name] ?: continue
            r.widgets[name] = org.jline.reader.Widget {
                val done = plain.apply()
                slashAt = 0
                if (slashDismissed != r.buffer.toString()) slashDismissed = null
                done
            }
        }
        r.widgets["orknux-close-list"] = org.jline.reader.Widget {
            if (slashChoices(r).isNotEmpty()) slashDismissed = r.buffer.toString()
            true
        }
        r.widgets["orknux-cycle-mode"] = org.jline.reader.Widget { cycleMode(); true }
        r.widgets["orknux-newline"] = org.jline.reader.Widget { r.buffer.write("\n"); true }
        r.widgets["orknux-thinking"] = org.jline.reader.Widget { showThinking(); true }
        val keys = r.keyMaps[LineReader.MAIN]!!
        keys.bind(Reference("orknux-cycle-mode"), "\u001b[Z")
        keys.bind(Reference("orknux-thinking"), KeyMap.ctrl('O'))
        keys.bind(Reference("orknux-close-list"), KeyMap.esc())
        listOf("\u001b\r", "\u001b\n", "\u001b[13;2u", "\u001b[27;2;13~").forEach { keys.bind(Reference("orknux-newline"), it) }
        // Enter runs the highlighted command while the list is open, and the line as typed otherwise.
        r.widgets["orknux-accept"] = org.jline.reader.Widget {
            slashChoices(r).getOrNull(slashAt)?.let { chosen -> r.buffer.clear(); r.buffer.write(chosen.value.trimEnd()) }
            r.callWidget(LineReader.ACCEPT_LINE)
            true
        }
        keys.bind(Reference("orknux-accept"), KeyMap.ctrl('M'))
        val tabBefore = keys.getBound("\t")
        r.widgets["orknux-tab"] = org.jline.reader.Widget {
            val chosen = slashChoices(r).getOrNull(slashAt)
            if (chosen != null) {
                r.buffer.clear()
                r.buffer.write(chosen.value.trimEnd() + " ")
                slashAt = 0
            } else when (tabBefore) {
                is Reference -> r.callWidget(tabBefore.name())
                is org.jline.reader.Widget -> tabBefore.apply()
            }
            true
        }
        keys.bind(Reference("orknux-tab"), "\t")
        for ((sequence, step) in listOf("\u001b[B" to 1, "\u001bOB" to 1, "\u001b[A" to -1, "\u001bOA" to -1)) {
            val before = keys.getBound(sequence)
            val name = "orknux-arrow-" + sequence.drop(1)
            r.widgets[name] = org.jline.reader.Widget {
                val shown = slashChoices(r)
                if (shown.isNotEmpty()) {
                    slashAt = (slashAt + step).mod(shown.size)
                } else {
                    when (before) {
                        is Reference -> r.callWidget(before.name())
                        is org.jline.reader.Widget -> before.apply()
                    }
                }
                true
            }
            keys.bind(Reference(name), sequence)
        }
        return r
    }

    /** Which of the listed commands is highlighted. */
    @Volatile private var slashAt = 0

    /** The line the list was closed on with Esc: it stays closed until the line changes. */
    @Volatile private var slashDismissed: String? = null

    /** True while the list is drawn as one types, rather than on Tab. */
    @Volatile private var listingAsTyped = false

    /**
     * What the list under a / shows now: while the line is one word starting
     * with /, the cursor at its end, and the list not closed with Esc. A / alone
     * lists the Shell's own commands; a letter after it narrows to commands and
     * skills alike.
     */
    private fun slashChoices(r: LineReader): List<Completion> {
        val typed = r.buffer.toString()
        if (r.buffer.cursor() != typed.length || !Regex("""/[^\s/]*""").matches(typed) || typed == slashDismissed) return emptyList()
        listingAsTyped = true
        return try {
            completions.complete(typed)
        } finally {
            listingAsTyped = false
        }
    }

    /** The list's rows: a window of [SLASH_ROWS] around the highlight, the highlighted one marked. */
    private fun slashRows(): List<String> {
        if (!::reader.isInitialized) return emptyList()
        val shown = slashChoices(reader)
        if (shown.isEmpty()) return emptyList()
        slashAt = slashAt.coerceIn(0, shown.lastIndex)
        val top = (slashAt - SLASH_ROWS / 2).coerceIn(0, (shown.size - SLASH_ROWS).coerceAtLeast(0))
        val wide = shown.subList(top, minOf(shown.size, top + SLASH_ROWS)).maxOf { it.value.trimEnd().length } + 2
        return shown.subList(top, minOf(shown.size, top + SLASH_ROWS)).mapIndexed { i, c ->
            val at = top + i == slashAt
            val name = c.value.trimEnd().padEnd(wide)
            (if (at) theme.accent("› ") + theme.bold(theme.accent(name)) else "  " + theme.text(name)) + theme.muted(c.description ?: "")
        } + listOfNotNull(if (shown.size > SLASH_ROWS) theme.muted("  ${slashAt + 1} of ${shown.size} · ↑↓ · Tab completes · Enter runs · Esc closes") else theme.muted("  ↑↓ · Tab completes · Enter runs · Esc closes"))
    }

    private val completions: Completions by lazy {
        Completions(object : CompletionSources {
            override fun agents() = agents.map { it.name }
            override fun workspaces() = workspacesCached().map { it.name }
            override fun skills() = if (listingAsTyped && reader.buffer.toString() == "/") emptyList() else skillsCached().map { it.key }
            override fun marker() = conversation.workspace.commandMarker
            override fun models() = modelsCached().map { it.name }
            override fun chats() = runCatching { api.chats(conversation.workspace.id).map { it.title } }.getOrDefault(emptyList())
            override fun refs() = branches()
            override fun efforts() = this@Interactive.efforts()
            override fun attachments() = attachments.size
            override fun links() = lastTurn?.links?.size ?: 0
            override fun subagents() = runCatching { subagentsListed().size }.getOrDefault(0)
            override fun files(prefix: String) = this@Interactive.files(prefix)
        })
    }

    private fun completer(): Completer {
        return Completer { _, line, candidates ->
            completions.complete(line.line().substring(0, line.cursor())).forEach {
                candidates += Candidate(it.value, it.value, null, it.description, null, null, it.complete)
            }
        }
    }

    /** File names under the directory a typed path points into, for `/attach`. */
    private fun files(prefix: String): List<String> {
        val home = System.getProperty("user.home")
        val expanded = if (prefix.startsWith("~")) home + prefix.substring(1) else prefix
        val separator = expanded.lastIndexOfAny(charArrayOf('/', '\\'))
        val dirText = if (separator >= 0) expanded.substring(0, separator + 1) else ""
        val dir = if (dirText.isEmpty()) start else start.resolve(dirText)
        val shown = if (separator >= 0) prefix.substring(0, prefix.length - (expanded.length - separator - 1)) else ""
        return runCatching {
            Files.list(dir).use { entries ->
                entries.map { p ->
                    val name = p.fileName.toString()
                    val directory = Files.isDirectory(p)
                    shown + name + if (directory) "/" else ""
                }.toList()
            }
        }.getOrDefault(emptyList())
    }

    private fun branches(): List<String> =
        runCatching { Review.git(start, "for-each-ref", "--format=%(refname:short)", "refs/heads").out.lines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun efforts(): List<String> =
        conversation.chat?.let { runCatching { api.efforts(it.id) }.getOrNull() } ?: listOf("minimal", "low", "medium", "high")

    private fun workspacesCached(): List<Workspace> {
        if (workspaces.isEmpty()) workspaces = runCatching { api.workspaces() }.getOrDefault(emptyList())
        return workspaces
    }

    private fun modelsCached(): List<Model> {
        models?.let { return it }
        return goals.models(conversation.workspace.id).also { models = it }
    }

    private fun skillsCached(): List<Skill> {
        skills?.let { return it }
        val loaded = runCatching { api.skills(conversation.workspace.id, conversation.agent) }.getOrDefault(emptyList())
        skills = loaded
        return loaded
    }

    // ---- the loop ----------------------------------------------------------------------------

    private fun loop() {
        while (true) {
            drainApprovals()
            footer()
            val line = try {
                phase = Phase.READING
                if (approvals.isNotEmpty()) continue
                val buffer = prefill.also { prefill = null }
                reader.readLine(prompt(), null, null as MaskingCallback?, buffer)
            } catch (interrupted: UserInterruptException) {
                if (wakeForApproval) {
                    wakeForApproval = false
                    prefill = interrupted.partialLine?.takeIf { it.isNotEmpty() }
                    continue
                }
                if (interrupted.partialLine.isNullOrEmpty()) say(theme.muted("(Ctrl+D or /quit to leave)"))
                continue
            } catch (_: EndOfFileException) {
                return
            } finally {
                phase = Phase.OTHER
            }
            val text = line.replace("\\\n", "\n").trim()
            // The line the person entered is on the screen now, written by the line editor rather than through say():
            // what follows is after text, so the blank line it asks for is kept.
            if (text.isNotEmpty()) lastBlank = false
            if (text.isEmpty()) {
                // An empty Enter leaves no bare prompt behind: its row is cleared and the screen comes back down.
                if (region.ansi) {
                    terminal.writer().print("\u001b[1A\u001b[2K")
                    pullDown(1)
                }
                continue
            }
            try {
                if (!said(text)) return
                // What was written while that turn ran, sent now, each as if typed at the prompt.
                while (queued.isNotEmpty()) {
                    val next = queued.removeAt(0)
                    say(prompt() + next)
                    lastBlank = false
                    if (!said(next)) return
                }
                // And a line begun and not sent waits in the next prompt.
                if (ahead.isNotEmpty()) {
                    prefill = ahead.toString()
                    ahead.setLength(0)
                }
            } catch (refused: OrknuxException) {
                say(theme.danger(refused.message ?: "Refused."))
                say("")
            } catch (bad: IllegalArgumentException) {
                say(theme.danger(bad.message ?: "That cannot be done."))
                say("")
            } catch (failed: RuntimeException) {
                // A fault of the Shell's own, said and survived: one command going wrong is not the end of the chat.
                say(theme.danger("✗ " + (failed.message ?: failed.javaClass.simpleName)))
                say("")
            }
        }
    }

    /** One line the person entered. False to leave. */
    private fun said(text: String): Boolean {
        SlashCommands.parse(text)?.let { slash ->
            if (slash.name == "quit") return false
            command(slash)
            say("")
            return true
        }
        skillCommand(text)?.let { message ->
            turn(message)
            return true
        }
        SlashCommands.unknown(text)?.let { word ->
            val near = SlashCommands.nearest(word)
            say(theme.warning("Unknown command /$word.") + theme.muted(near?.let { " Did you mean /$it? " } ?: " ") + theme.muted("/help lists them; nothing was sent."))
            return true
        }
        if (text.startsWith("@")) addressed(text)?.let { (agent, message) ->
            side(agent, message)
            return true
        }
        PastedPaths.detect(text)?.let { paths ->
            val names = paths.joinToString(", ") { Path.of(it).fileName.toString() }
            val answer = ask(theme.accent("Attach $names? ") + theme.muted("[y/N] "))
            if (answer.lowercase().startsWith("y")) {
                attach(paths)
                return true
            }
        }
        turn(text)
        return true
    }

    /**
     * `/plan add a cache` where plan is a skill and not a command of the Shell's:
     * sent as the workspace's own command, `!plan add a cache`, which is what
     * the server reads. Everything in the Shell is typed with a slash, so a
     * skill is too; the marker still works as it is.
     */
    private fun skillCommand(text: String): String? {
        if (!text.startsWith("/")) return null
        val key = text.substring(1).substringBefore(' ').substringBefore('\n')
        if (key.isEmpty()) return null
        val marker = conversation.workspace.commandMarker ?: return null
        val skill = skillsCached().firstOrNull { it.key.equals(key, ignoreCase = true) } ?: return null
        return marker + skill.key + text.substring(1 + key.length)
    }

    /** `@Research bot what is new` is the agent Research bot and the message; the longest name that fits wins. */
    private fun addressed(text: String): Pair<Agent, String>? {
        val body = text.removePrefix("@").replace("\\ ", " ")
        val agent = agents.sortedByDescending { it.name.length }.firstOrNull { a ->
            body.startsWith(a.name, ignoreCase = true) && (body.length == a.name.length || body[a.name.length].isWhitespace())
        } ?: return null
        val message = body.substring(agent.name.length).trim()
        if (message.isEmpty()) throw OrknuxException("Say something to ${agent.name}: @${agent.name} <message>")
        return agent to message
    }

    private fun prompt(): String = theme.accent("› ")

    private fun ask(prompt: String): String = try {
        reader.readLine(prompt).trim().also { lastBlank = false }
    } catch (_: UserInterruptException) {
        ""
    }

    // ---- a turn ------------------------------------------------------------------------------

    /** Sends [text] in [into] - the main conversation unless an `@agent` message chose another - and draws the answer. */
    private fun turn(text: String, into: Conversation = conversation) {
        val output = object : TurnOutput {
            override fun line(text: String) = say(text)
            override fun phase(key: String, text: String) = region.phase(key, text)
            override fun thinking(text: String?) {
                if (text != null && liveThinking == null) thinkingSince = System.currentTimeMillis()
                liveThinking = text
                // Kept for Ctrl+O, but drawn live only when asked for: by default the spinner says "Thinking"
                // and the text is read afterwards, from the "Thought for" line, as in Claude Code.
                region.thinkingHint = detailsHint()
                region.thinking(if (config.thinking == "expanded" || thinkingShown) text else null)
            }
            override fun width(): Int = this@Interactive.width()
        }
        val renderer = TurnRenderer(theme, output, config.thinking, links) { thinkingShown }
        val sending = if (into === conversation) attachments.toList() else emptyList()
        // A blank line between what was asked and what comes back.
        say("")
        active = into
        lastTurn = renderer
        phase = Phase.TURN
        // The status bar stays where it is, at the foot of the screen, and is kept up to date by the ticker.
        val saved = terminal.enterRawMode()
        val raw = terminal.attributes
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        terminal.attributes = raw
        thinkingShown = false
        region.thinkingHint = detailsHint()
        region.phase("thinking", theme.thinking("Thinking"))
        region.start { below() }
        // Ten frames a second, and only while a turn runs: nothing ticks when nothing is happening.
        val ticker = Thread.ofPlatform().daemon().name("orknux-shell-ticker").start {
            var n = 0
            while (phase == Phase.TURN) {
                region.tick()
                if (++n % 10 == 0) footer()
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        val keys = Thread.ofPlatform().daemon().name("orknux-shell-keys").start { keysDuringTurn() }
        val chatBefore = into.chat?.id
        try {
            into.send(text, sending.map { it.id }) { event -> renderer.handle(event) }
            if (sending.isNotEmpty()) attachments.removeAll(sending)
        } catch (failed: OrknuxException) {
            renderer.flush()
            say(theme.danger("✗ " + (failed.message ?: "The server could not answer.")))
        } finally {
            renderer.flush()
            phase = Phase.OTHER
            keys.interrupt()
            keys.join(1_000)
            ticker.interrupt()
            ticker.join(500)
            liveThinking = null
            active = null
            region.stop()
            terminal.attributes = saved
        }
        lastThinking = renderer.blocks.toList()
        if (chatBefore == null && api.startedWithoutOrigin) {
            say(theme.warning("This server does not know chats opened in Orknux Shell yet, so its agent cannot run commands here."))
        }
        if (!region.ansi) say(theme.muted(statusText()))
        // A blank line between an answer and the next prompt.
        say("")
        if (into === conversation) checkGoal()
        answerQuestions(into)
    }

    // ---- questions the agent put to the person -------------------------------------------------

    /**
     * What the agent asked with ask_person - and anything else waiting on the
     * person in this conversation - put to them as a list to choose from, with
     * a row for their own words where the question takes them. An answer wakes
     * the agent on the server, so its next turn is followed here as it is
     * written; and asked about again, should that turn ask something too. Esc
     * leaves a question open: `/answer` comes back to it.
     */
    private fun answerQuestions(into: Conversation, askedFor: Boolean = false) {
        while (true) {
            val session = into.llmSessionId ?: return
            val open = api.openQuestions(session)
            if (open.isEmpty()) {
                if (askedFor) say(theme.muted("Nothing is waiting for an answer."))
                return
            }
            var answered = false
            for (question in open) {
                if (!ask(question)) {
                    say(theme.muted("Left open; /answer comes back to it."))
                    say("")
                    return
                }
                answered = true
            }
            if (!answered) return
            val chat = into.chat?.id ?: return
            followWake(chat, into)
        }
    }

    /** One question; false where the person left it. */
    private fun ask(question: PersonQuestion): Boolean {
        say(theme.bold(question.question) + (question.askedBy?.let { theme.muted("  · $it asks") } ?: ""))
        question.items.forEach { say(theme.muted("  • ") + it) }
        // Room between the question and the choices under it.
        say("")
        val own = question.allowsText
        val rows = question.options.map { option -> option.label + (option.description?.let { theme.muted(" - $it") } ?: "") } +
            listOfNotNull(if (own) theme.muted("Answer in my own words") else null)
        val picked: Set<Int> = if (question.multiple) {
            pickMany("Answer", rows) ?: return false
        } else {
            setOf(chooseFrom("Answer", rows.indices.toList()) { rows[it] } ?: return false)
        }
        val ownRow = question.options.size
        val text = if (own && ownRow in picked) {
            val typed = try {
                reader.readLine(theme.accent("› ") + theme.muted("your answer: ")).trim().also { lastBlank = false }
            } catch (_: UserInterruptException) {
                return false
            } catch (_: org.jline.reader.EndOfFileException) {
                return false
            }
            typed.ifEmpty { return false }
        } else {
            null
        }
        val chosen = picked.filter { it < ownRow }.map { question.options[it].id }
        api.answerQuestion(question.id, chosen, text, question.multiple)
        val said = picked.filter { it < ownRow }.map { question.options[it].label } + listOfNotNull(text?.let { "\"$it\"" })
        say("")
        say(theme.success("✓ ") + theme.muted("Answered: ") + said.joinToString(", "))
        say("")
        return true
    }

    /** Several of [rows], with Space; off a terminal, numbers separated by commas. */
    private fun pickMany(what: String, rows: List<String>): Set<Int>? {
        if (!region.ansi) {
            say(theme.bold(what))
            rows.forEachIndexed { i, row -> say("  " + theme.accent("${i + 1}.") + " " + row) }
            val answer = ask(theme.muted("Numbers, separated by commas (Enter to leave it) › "))
            if (answer.isEmpty()) return null
            return answer.split(',').mapNotNull { it.trim().toIntOrNull()?.minus(1) }.filter { it in rows.indices }.toSet().ifEmpty { null }
        }
        val picker = Picker(
            sink = { text -> terminal.writer().print(text); terminal.writer().flush() },
            key = { timeout -> terminal.reader().read(if (timeout < 0) 0L else timeout) },
            theme = theme,
            rows = { terminal.height.takeIf { it > 0 } ?: 24 },
            width = ::width,
        )
        val saved = terminal.enterRawMode()
        val chosen = try {
            synchronized(out) { picker.chooseMany(what, rows) }
        } finally {
            terminal.attributes = saved
        }
        pullDown(picker.lastHeight - 1)
        return chosen
    }

    /**
     * The turn an answer woke, drawn as it is written: the server runs it by
     * itself, and the Shell reads it the way it reads a goal's iterations.
     * Ctrl+C or Esc asks the server to stop it.
     */
    private fun followWake(chatId: String, into: Conversation) {
        val output = object : TurnOutput {
            override fun line(text: String) = say(text)
            override fun phase(key: String, text: String) = region.phase(key, text)
            override fun thinking(text: String?) {
                if (text != null && liveThinking == null) thinkingSince = System.currentTimeMillis()
                liveThinking = text
                region.thinkingHint = detailsHint()
                region.thinking(if (config.thinking == "expanded" || thinkingShown) text else null)
            }
            override fun width(): Int = this@Interactive.width()
        }
        val renderer = TurnRenderer(theme, output, config.thinking, links) { thinkingShown }
        // The same keys as a turn sent from here - Ctrl+C or Esc interrupts, Ctrl+O shows the thinking, typing queues -
        // and Ctrl+C a key rather than a signal: as a signal it reached a handler that knew only turns sent from here.
        active = into
        lastTurn = renderer
        thinkingShown = false
        phase = Phase.TURN
        val saved = terminal.enterRawMode()
        val raw = terminal.attributes
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        terminal.attributes = raw
        region.thinkingHint = detailsHint()
        region.phase("woken", theme.muted("Taking your answer"))
        region.start { below() }
        val ticker = Thread.ofPlatform().daemon().start {
            var n = 0
            while (phase == Phase.TURN) {
                region.tick()
                if (++n % 10 == 0) footer()
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        val keys = Thread.ofPlatform().daemon().name("orknux-shell-keys").start { keysDuringTurn() }
        try {
            api.client.stream("/api/chats/$chatId/follow?wait=true", null, opened = { into.following(it) }) { frame -> renderer.handle(ChatEvent.of(frame)) }
        } catch (failed: OrknuxException) {
            say(theme.danger("✗ " + (failed.message ?: "Lost the chat.")))
        } finally {
            into.followed()
            renderer.flush()
            phase = Phase.OTHER
            keys.interrupt()
            keys.join(500)
            ticker.interrupt()
            liveThinking = null
            active = null
            region.stop()
            terminal.attributes = saved
        }
        lastThinking = renderer.blocks.toList()
        runCatching { into.refresh() }
        say("")
        footer()
    }

    private fun rule(): String =
        theme.muted((if (LiveRegion.unicodeFor(terminal.encoding()?.name())) "─" else "-").repeat((width() - 1).coerceAtLeast(1)))

    /** Under the prompt while a line is read: the list of commands under a /, where it is open. */
    private fun underPrompt(): List<String> =
        if (!::conversation.isInitialized || !region.ansi) emptyList()
        else slashRows().map { LiveRegion.clip(it, width() - 1) }

    /**
     * Moves the scrolling area's text down [rows] rows and the cursor with it:
     * what a list under the prompt, or an empty line entered, pushed up comes
     * back, and the prompt is on the last row above the status bar again.
     */
    private fun pullDown(rows: Int) {
        if (rows <= 0 || !region.ansi) return
        terminal.writer().print("\u001b[${rows}T\u001b[${rows}B")
        terminal.writer().flush()
    }

    /** What sits under the spinner: the files waiting to go, and the status line. */
    /** What is being typed while a turn runs. */
    private val ahead = StringBuilder()

    /** Messages written while a turn ran, sent one after another when it ends. */
    private val queued = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** Under the spinner: what is queued, the line being typed, and the status line. */
    private fun below(): List<String> {
        val typing = buildList {
            queued.forEach { add(theme.muted("  queued · ") + theme.text(it)) }
            if (region.active) add(prompt() + ahead.toString() + theme.muted(if (ahead.isEmpty()) "type to queue a message for when this turn ends" else "▏"))
        }
        // A blank line between what is happening and what is being written.
        if (status != null) return listOf("") + typing
        return listOf("") + typing + listOfNotNull(rule(), chips().takeIf { attachments.isNotEmpty() }, " " + statusText())
    }

    /** Ctrl+C or Esc during a turn: the spinner stops at once, and the server is asked to stop. */
    private fun interruptTurn() {
        val said = active?.interrupt() ?: return
        region.halt("Interrupted")
        say("")
        say(theme.warning(said))
        say("")
    }

    /** A message to another agent, in a side conversation of its own that `/subagents` lists; the main one is untouched. */
    private fun side(agent: Agent, message: String) {
        val chat = sideChats.getOrPut(agent.id) { Conversation(api, conversation.workspace, agent, conversation.mode) }
        say(theme.bold(theme.accent("@" + agent.name)))
        turn(message, chat)
    }

    /** The keyboard while a turn runs: Ctrl+C or Esc interrupts, Ctrl+O shows the thinking, and a command waiting to be asked about is asked. */
    private fun keysDuringTurn() {
        val input = terminal.reader()
        // Interrupted when the turn ends, in the middle of any read or peek: that is the way out, not an error.
        try {
            while (phase == Phase.TURN && !Thread.currentThread().isInterrupted) {
                approvals.poll()?.let { ask(it); continue }
                val c = input.read(50)
                if (c != NonBlockingReader.READ_EXPIRED) Trace.log("key $c")
                when (c) {
                    3 -> interruptTurn()
                    15 -> {
                        // Shown under the spinner while it is wanted, and gone again on the next Ctrl+O: nothing is
                        // written into the transcript, so there is nothing to take back.
                        thinkingShown = !thinkingShown
                        region.thinkingHint = detailsHint()
                        region.thinking(if (thinkingShown || config.thinking == "expanded") liveThinking else null)
                    }
                    27 -> if (input.peek(30) == NonBlockingReader.READ_EXPIRED) interruptTurn() else drainSequence(input)
                    // Typed while the agent works: kept under the spinner, and Enter queues it for when the turn ends.
                    13, 10 -> {
                        val typed = ahead.toString().trim()
                        if (typed.isNotEmpty()) queued += typed
                        ahead.setLength(0)
                        region.redraw()
                    }
                    127, 8 -> {
                        if (ahead.isNotEmpty()) ahead.setLength(ahead.length - 1)
                        region.redraw()
                    }
                    21 -> { ahead.setLength(0); region.redraw() }
                    else -> if (c >= 32) {
                        ahead.append(c.toChar())
                        region.redraw()
                    }
                }
            }
        } catch (_: java.io.InterruptedIOException) {
        } catch (_: InterruptedException) {
        } catch (failure: Exception) {
            Trace.log("key read failed: $failure")
        }
    }

    private fun drainSequence(input: NonBlockingReader) {
        while (input.peek(5) >= 0) input.read(5)
    }

    /** Ctrl+O and `/thinking`: the last turn's thinking in full, every stretch in order, and what is streaming now. */
    /** What Ctrl+O does now, said on the spinner's line. */
    private fun detailsHint(): String = if (thinkingShown) "Ctrl+O fewer details" else "Ctrl+O details"

    /**
     * Whether this turn shows its details: the thinking live, and each tool call
     * in full rather than on one line. Ctrl+O while it runs turns it on and off.
     */
    @Volatile private var thinkingShown = false

    /**
     * The last turn's thinking, read in a page of its own on the terminal's
     * alternate screen - the arrows, PgUp and PgDn scroll it - and gone again on
     * Ctrl+O, Esc or q, the conversation exactly as it was. Nothing is written
     * into the transcript, so there is nothing to take back.
     */
    private fun showThinking() {
        val details = lastTurn?.details?.toList().orEmpty().ifEmpty { lastThinking.map { Detail.Thought(it) } }
        if (details.isEmpty()) {
            say(theme.muted("Nothing to show of the last turn: no thinking and no tool calls."))
            return
        }
        val text = TurnRenderer.detailLines(details, theme, width())
        if (!region.ansi) {
            text.forEach(::say)
            return
        }
        val saved = terminal.enterRawMode()
        try {
            ThinkingPage(text, theme, { terminal.height.takeIf { it > 0 } ?: 24 }, ::width,
                sink = { terminal.writer().print(it); terminal.writer().flush() },
                key = { timeout -> terminal.reader().read(if (timeout < 0) 0L else timeout) }).show()
        } finally {
            terminal.attributes = saved
            // The page used the whole window; the bars are pinned again for the conversation's screen.
            footer()
        }
    }

    // ---- approvals ---------------------------------------------------------------------------

    /** Called on a device thread; blocks until whoever holds the keyboard has asked. */
    private fun approve(request: RunRequest, decision: Decision): Approval {
        val pending = Pending(request, decision)
        approvals.put(pending)
        if (phase == Phase.READING) {
            wakeForApproval = true
            terminal.raise(Terminal.Signal.INT)
        }
        return pending.answer.get()
    }

    private fun drainApprovals() {
        while (true) {
            val pending = approvals.poll() ?: return
            val saved = terminal.enterRawMode()
            try {
                ask(pending)
            } finally {
                terminal.attributes = saved
            }
        }
    }

    /** The box, the three choices, and the pattern editor where that is the choice. Needs raw mode. */
    private fun ask(pending: Pending) {
        val answer = try {
            val directory = CommandRunner(config.shell).resolveDirectory(pending.request.cwd).toString()
            ApprovalBox.lines(theme, pending.request, pending.decision, directory, width()).forEach(::say)
            when (select()) {
                ApprovalChoice.ONCE -> Approval.Once
                ApprovalChoice.DECLINE -> Approval.Declined
                ApprovalChoice.PATTERN -> editPattern(pending.request.command)?.let { Approval.Pattern(it) } ?: Approval.Once
            }
        } catch (_: Exception) {
            Approval.Declined
        }
        say(
            "  " + when (answer) {
                Approval.Once -> theme.success("Approved")
                is Approval.Pattern -> theme.success("Approved, and allowed from now on: ") + theme.code(answer.pattern)
                Approval.Declined -> theme.danger("Declined")
            },
        )
        pending.answer.complete(answer)
    }

    private fun select(): ApprovalChoice {
        val input = terminal.reader()
        var at = ApprovalChoice.ONCE
        val writer = terminal.writer()
        val inRegion = region.active
        Trace.log("select: region ${region.active}, terminal ${terminal.javaClass.simpleName} ${terminal.type}")
        if (inRegion) region.phase("approval", "Waiting for your approval")
        fun draw() {
            if (inRegion) {
                region.asking(ApprovalBox.choices(theme, at))
                return
            }
            synchronized(out) {
                writer.print("\r\u001b[2K" + ApprovalBox.choices(theme, at))
                writer.flush()
            }
        }
        draw()
        try {
            while (true) {
                val c = input.read(100)
                if (c == NonBlockingReader.READ_EXPIRED) continue
                Trace.log("approval key $c")
                if (c < 0) return ApprovalChoice.DECLINE
                when (c) {
                    13, 10 -> return at
                    3 -> return ApprovalChoice.DECLINE
                    'a'.code, 'y'.code -> return ApprovalChoice.ONCE
                    'p'.code -> return ApprovalChoice.PATTERN
                    'd'.code, 'n'.code -> return ApprovalChoice.DECLINE
                    9 -> at = ApprovalChoice.entries[(at.ordinal + 1) % 3]
                    27 -> {
                        if (input.peek(40) == NonBlockingReader.READ_EXPIRED) return ApprovalChoice.DECLINE
                        val bracket = input.read(10)
                        val code = input.read(10)
                        if (bracket == '['.code || bracket == 'O'.code) {
                            when (code) {
                                'C'.code, 'B'.code -> at = ApprovalChoice.entries[(at.ordinal + 1) % 3]
                                'D'.code, 'A'.code, 'Z'.code -> at = ApprovalChoice.entries[(at.ordinal + 2) % 3]
                            }
                        }
                        drainSequence(input)
                    }
                }
                draw()
            }
        } finally {
            if (inRegion) {
                region.asking(null)
            } else {
                synchronized(out) {
                    writer.print("\r\u001b[2K")
                    writer.flush()
                }
            }
        }
    }

    /** The proposed rule, for the person to change before it is kept. Null where they emptied it. */
    private fun editPattern(command: String): String? {
        var proposed = CommandPolicy.proposePattern(command)
        say(theme.muted("  Allow rule: a Java regular expression found in the command line; ^ and $ anchor it. Empty to skip."))
        while (true) {
            val suspended = region.active
            if (suspended) region.stop()
            val edited = try {
                reader.readLine(theme.accent("  allow › "), null, null as MaskingCallback?, proposed).trim()
            } finally {
                if (suspended) region.start(afterBlank = false) { below() }
            }
            if (edited.isEmpty()) return null
            try {
                if (!Pattern.compile(edited).matcher(command).find()) say(theme.warning("  Note: that rule does not match this command."))
                return edited
            } catch (bad: PatternSyntaxException) {
                say(theme.danger("  Not a regular expression: ${bad.description}"))
                proposed = edited
            }
        }
    }

    private fun keepRule(pattern: String): String {
        config = store.update { it.copy(commands = it.commands.copy(allow = it.commands.allow + pattern)) }
        handler.policy = CommandPolicy(config.commands)
        return store.path.toString()
    }

    private fun deviceReport() = object : DeviceReport {
        override fun running(request: RunRequest, approval: String) {
            if (region.active) region.phase("local:${request.command}", "Running here: " + theme.muted(TurnRenderer.clip(TurnRenderer.oneLine(request.command), 60)))
        }

        override fun ran(request: RunRequest, result: RunResult, approval: String) {
            val exit = if (result.exitCode == 0) theme.success("exit 0") else theme.danger("exit ${result.exitCode}")
            val notable = listOfNotNull(
                if (approval == "person") "approved" else null,
                if (result.truncated) "output cut to the last ${CommandRunner.OUTPUT_LIMIT / 1000}k" else null,
                if (result.timedOut) "stopped at its time limit" else null,
            )
            // In a turn the tool call's own line follows with the command, the exit code and the output, so an
            // ordinary run is not said twice; what that line does not say - approved, cut, timed out - is.
            if (region.active && notable.isEmpty()) return
            val extra = (listOf("${result.seconds}s") + notable).joinToString(" · ")
            if (region.active) say("")
            say(theme.dim(theme.muted("$ " + TurnRenderer.clip(TurnRenderer.oneLine(request.command), width() - 4))))
            say(theme.dim("  ⎿ ") + exit + theme.dim(theme.muted(" · $extra")))
        }

        override fun refused(request: RunRequest, why: String) {
            say(theme.dim(theme.muted("$ " + TurnRenderer.clip(TurnRenderer.oneLine(request.command), width() - 4))))
            say(theme.dim("  ⎿ ") + theme.danger(why))
        }

        // Said between other things, so a blank line on either side.
        override fun notice(text: String) { say(""); say(theme.muted(text)); say("") }
        override fun error(text: String) { say(""); say(theme.warning(text)); say("") }
    }

    // ---- the screen --------------------------------------------------------------------------

    /**
     * One line into the transcript, above the editor and the status line, whoever
     * is holding the keyboard. Written as it is - an OSC 8 link included, which
     * the line editor's own styling would strip.
     */
    private fun say(text: String) {
        // A line is one row: text with newlines in it is its lines, each said, or the rows under it go uncounted.
        if ('\n' in text) {
            text.removeSuffix("\n").split('\n').forEach { say(it.removeSuffix("\r")) }
            return
        }
        // One blank line between things, never two: the renderer spaces its own pieces, and a notice said in the
        // middle of a turn (an interruption, an approval) spaces itself as well.
        val blank = stripAnsi(text).isBlank()
        if (blank && lastBlank) return
        lastBlank = blank
        if (::region.isInitialized && region.active) {
            region.line(text)
            return
        }
        synchronized(out) {
            if (::reader.isInitialized) {
                reader.printAbove(text)
            } else {
                terminal.writer().println(text)
                terminal.writer().flush()
            }
        }
    }

    @Volatile private var lastBlank = true

    private val startedAt = System.currentTimeMillis()

    /**
     * The status bar's height: its rule and its row. Fixed, since JLine's status
     * cannot change height under text without losing where the cursor is; the
     * workspace is in the bar at the top of the window instead.
     */
    private fun barRows(): Int = 2

    private fun width() = terminal.width.takeIf { it > 0 } ?: 100

    /** The status line under the editor, with the files waiting to go; while a turn runs, the region draws it instead. */
    private fun footer() {
        header()
        if (::region.isInitialized && region.active && status == null) {
            region.redraw()
            return
        }
        // Under the prompt the line editor draws it itself, fresh at every redraw (ShellLineReader).
        val st = status ?: return
        // A rule across above it and a column in from the edge: the bar is a bar, not the last line of the answer.
        val rule = theme.muted((if (region.ansi && LiveRegion.unicodeFor(terminal.encoding()?.name())) "─" else "-").repeat((width() - 1).coerceAtLeast(1)))
        val lines = listOf(rule) + listOfNotNull(chips().takeIf { attachments.isNotEmpty() }?.let { " $it" }) + (" " + statusText())
        val draw = {
            runCatching {
                st.update(lines.map { AttributedString.fromAnsi(LiveRegion.clip(it, width() - 1)) })
                // JLine sets the scrolling area from the top of the window each time it draws its bar: the top bar is
                // pinned again after it, or the transcript would scroll it away.
                header(lines.size)
            }
        }
        synchronized(out) { if (::region.isInitialized) region.exclusive(draw) else draw() }
    }

    private fun chips(): String = attachments.mapIndexed { i, a ->
        theme.accent("📎 ") + theme.text(a.filename) + theme.muted(" ${size(a.sizeBytes)} ") + theme.muted("×${i + 1}")
    }.joinToString("  ")

    private fun statusText(): String = statusParts().let { (left, right) -> oneRow(left, right) }

    /**
     * The workspace in the window's title - "Orknux · Private" - which the
     * terminal shows above everything, on its tab and its title bar. Not a row
     * pinned at the top of the screen: that takes a scrolling area which starts
     * below the first row, and a terminal throws away the lines scrolled out of
     * one rather than keeping them in its scrollback, so the conversation could
     * no longer be scrolled back through.
     */
    private fun header(@Suppress("UNUSED_PARAMETER") footRows: Int = 0) {
        if (!::region.isInitialized || !region.ansi) return
        val name = conversationOrNull()?.workspace?.name ?: config.workspace ?: return
        if (name == titled) return
        titled = name
        terminal.writer().print("\u001b]0;Orknux · ${name.replace(Regex("[\\u0000-\\u001f\\u007f]"), "")}\u0007")
        terminal.writer().flush()
    }

    @Volatile private var titled: String? = null

    private fun conversationOrNull(): Conversation? = if (::conversation.isInitialized) conversation else null

    private fun oneRow(left: String, right: String): String {
        val gap = width() - 3 - visibleLength(left) - visibleLength(right)
        return if (gap >= 2) left + " ".repeat(gap) + right else left + theme.muted(" · ") + right
    }

    /** What the bar says: what is going on, and - the right-hand part - the workspace one is in. */
    private fun statusParts(): Pair<String, String> {
        val c = conversation
        val u = c.usage
        // Whether the agent can run commands on this computer: the door Orknux Shell keeps open to the server for it.
        // Said only when something is wrong: an agent here runs commands on this computer as a matter of course, and a
        // light that is always on says nothing. Off, while the door to the server for them is down.
        // The door takes a moment to open at the start, which is not news.
        val settled = System.currentTimeMillis() - startedAt > DEVICE_GRACE_MILLIS
        val device = if (::device.isInitialized && !device.connected && settled) theme.warning("commands cannot run here: reconnecting") else null
        val left = listOfNotNull(
            theme.text(c.agent?.name ?: c.chat?.agentName ?: "agent"),
            modeLabel(),
            c.effort?.let { theme.muted("effort ") + theme.text(it) },
            goal?.takeIf { it.running }?.let { theme.accent("goal ${it.progress}") },
            theme.muted("↑${TurnRenderer.tokens(u.inputTokens)} ↓${TurnRenderer.tokens(u.outputTokens)} (${TurnRenderer.tokens(u.cachedInputTokens)} cached)"),
            // The cost only where the model has a price; a local model's has none, and a dash read as a glitch.
            u.cost?.let { theme.muted(TurnRenderer.cost(it)) },
            c.speeds.said()?.let { theme.muted(it) },
            device,
        ).joinToString(theme.muted(" · "))
        // The workspace is in the bar at the top of the window, where one is rather than what is going on.
        return left to theme.muted("workspace ") + theme.text(c.workspace.name)
    }

    private fun modeLabel(): String = when (conversation.mode) {
        Modes.AUTO -> theme.success("auto")
        Modes.ASK -> theme.warning("ask")
        else -> theme.accent("plan")
    }

    private fun cycleMode() = setMode(Modes.next(conversation.mode))

    private fun setMode(mode: String) {
        conversation.mode = mode
        sideChats.values.forEach { it.mode = mode }
        remember()
        footer()
        if (conversation.chat != null) {
            Thread.ofVirtual().start {
                runCatching { conversation.syncMode() }.onFailure { say(theme.danger("Could not switch the chat's mode: ${it.message}")) }
            }
        }
    }

    // ---- slash commands ----------------------------------------------------------------------

    private fun command(slash: Slash) {
        val argument = slash.argument?.replace("\\ ", " ")?.let { if (slash.name == "attach") it else it.trim('"', '\'') }
        when (slash.name) {
            "help" -> help()
            "usage" -> usage()
            "clear" -> {
                conversation.clear()
                say(theme.muted("A new conversation starts with your next message."))
            }
            "mode" -> {
                val next = argument?.lowercase() ?: Modes.next(conversation.mode)
                if (next !in Modes.ALL) throw OrknuxException("The modes are ${Modes.ALL.joinToString(", ")}.")
                setMode(next)
                say(theme.muted("Mode ") + modeLabel() + theme.muted(" - " + modeMeaning(next)))
            }
            "agent" -> agent(argument)
            "workspace" -> workspace(argument)
            "skill" -> skill(argument)
            "resume" -> resume(argument)
            "theme" -> theme(argument)
            "rules" -> showRules()
            "attach" -> if (argument == null) listAttachments() else attach(PastedPaths.tokens(slash.argument) ?: throw OrknuxException("A quote is not closed."))
            "detach" -> detach(argument)
            "open" -> open(argument)
            "thinking" -> thinking(argument)
            "effort" -> effort(argument)
            "subagents" -> subagents(argument)
            "review" -> review(argument)
            "goal" -> goal(argument)
            "update" -> update()
            "answer" -> answerQuestions(conversation, askedFor = true)
            "sessions" -> sessions()
            "orkx-web-chat" -> openInBrowser(
                "this chat",
                "/chat/" + (conversation.chat?.id ?: throw OrknuxException("There is no chat yet: it starts with your first message.")),
            )
            "orkx-web-session" -> openInBrowser(
                "this conversation's session log",
                "/workspace/${conversation.workspace.id}/sessions/" +
                    (conversation.llmSessionId ?: throw OrknuxException("There is no session yet: it starts with your first message.")),
            )
            "orkx-web-agent-settings" -> openInBrowser(
                "${conversation.agent?.name ?: "the agent"}'s settings",
                "/workspace/${conversation.workspace.id}/agents/" +
                    (conversation.agent?.id ?: throw OrknuxException("No agent is chosen in this chat yet: /agent picks one.")) + "/settings",
            )
        }
    }

    /**
     * The chat's sessions: its own, every agent it asked, and the @agent side
     * chats - active ones first, each with its agent, what it is about and
     * what it spent. Choosing one opens its log in the browser.
     */
    private fun sessions() {
        val own = conversation.llmSessionId ?: throw OrknuxException("There is no session yet: it starts with your first message.")
        val found = buildList {
            api.session(own)?.let { add(it to "this chat") }
            api.subagents(own).forEach { add(it to "asked") }
            sideChats.values.mapNotNull { it.llmSessionId }.forEach { id -> api.session(id)?.let { add(it to "@agent") } }
        }.sortedByDescending { it.first.active }
        if (found.isEmpty()) {
            say(theme.muted("No sessions found for this chat."))
            return
        }
        // "Active" as the server means it - an agent at work in it now, or a moment ago - not a claim that a turn is running.
        val active = found.count { it.first.active }
        say(theme.bold("Sessions of this chat") + theme.muted(" · ${found.size}, $active active"))
        val chosen = chooseFrom("Sessions", found) { (s, kind) ->
            (if (s.active) theme.warning("● active ") else theme.muted("○ idle   ")) + theme.text(s.agent) + theme.muted(" ($kind)") +
                theme.muted(" · ↑${TurnRenderer.tokens(s.inputTokens)} ↓${TurnRenderer.tokens(s.outputTokens)}" + (s.cost?.let { " · $it" } ?: "") +
                    " · " + TurnRenderer.oneLine(s.about))
        } ?: return
        openInBrowser("the session log of ${chosen.first.agent}", "/workspace/${conversation.workspace.id}/sessions/${chosen.first.sessionId}")
    }

    /** Opens a page of the server in the browser, and says its address too - clickable where the terminal draws links. */
    private fun openInBrowser(what: String, path: String) {
        val url = links.absolute(path)
        val opened = runCatching { Links.open(url) }.isSuccess
        say(theme.muted(if (opened) "Opened $what in the browser: " else "Open $what in the browser: ") + links.link(url, url) { theme.accent(it) })
    }

    // ---- updates -----------------------------------------------------------------------------

    /** Once a day, in the background: one line where a newer Shell is out, and nothing else. */
    private fun lookForUpdate() {
        Thread.ofVirtual().name("orknux-shell-update-check").start {
            val news = runCatching { DailyCheck.run(store, Updater()) }.getOrNull() ?: return@start
            news.withdrawn?.let { say(theme.warning("This version, ${Version.VERSION}, was withdrawn: $it") + theme.muted(" · /update")) }
            news.newer?.let { say(theme.muted("Orknux Shell ") + theme.accent(it) + theme.muted(" is out: ") + theme.accent("/update")) }
        }
    }

    private fun update() {
        val updater = Updater()
        val offer = updater.latest()
        store.update { it.copy(updateCheckedAt = System.currentTimeMillis(), updateLatest = offer.version) }
        if (!updater.newer(offer)) {
            say(theme.muted("Orknux Shell ${Version.VERSION} is the newest there is."))
            return
        }
        say(theme.bold(theme.accent("Orknux Shell ${offer.version}")) + theme.muted(offer.publishedAt?.let { " · published ${it.substringBefore('T')}" } ?: ""))
        say("")
        releaseNotes(theme, offer.changelog, width()).forEach(::say)
        say("")
        say(theme.muted("Downloading ${size(offer.file.size)}…"))
        val path = updater.install(offer)
        say("")
        say(theme.success("✓ ") + "Orknux Shell ${offer.version} is installed" + theme.muted(" at $path; its signature checked against Orknux's release key."))
        say(theme.muted("This session goes on as ${Version.VERSION}. /quit and start orknux again to use it."))
    }

    private fun modeMeaning(mode: String) = when (mode) {
        Modes.AUTO -> "local commands run unless a rule, sudo or the agent's own caution says to ask"
        Modes.ASK -> "every local command is asked about"
        else -> "the agent only looks and puts a plan to you; local commands are asked about"
    }

    private fun help() {
        say(theme.bold("Commands"))
        SlashCommands.ALL.forEach { say("  " + theme.accent("/" + it.name) + (it.argument?.let { a -> theme.muted(" $a") } ?: "") + theme.muted("  " + it.summary)) }
        say("  " + theme.accent("@agent") + theme.muted(" <message>  Ask another agent, in a side conversation, without switching"))
        say(theme.bold("Keys"))
        listOf(
            "Enter" to "send",
            "Alt+Enter, Shift+Enter, or \\ at the end" to "a new line",
            "Tab" to "complete commands, agents, workspaces, skills and files",
            "Shift+Tab" to "switch mode: auto, ask, plan",
            "Ctrl+O" to "read the last turn's thinking",
            "Ctrl+C / Esc" to "interrupt the answer (twice: stop waiting)",
            "Ctrl+D" to "quit",
        ).forEach { (k, v) -> say("  " + theme.accent(k) + theme.muted("  $v")) }
        say(theme.muted("Paste or drop a file path on its own to attach it."))
        conversation.workspace.commandMarker?.let { say(theme.muted("Skills: start a message with /key, or write ${it}key anywhere in one; /skill lists them.")) }
    }

    private fun usage() {
        conversation.refresh()
        val u = conversation.usage
        val chat = conversation.chat
        if (chat == null) {
            say(theme.muted("Nothing said yet in this conversation."))
            return
        }
        say(theme.bold(chat.title))
        say("  " + theme.muted("input   ") + "%,d tokens".format(u.inputTokens) + theme.muted("  (%,d of them cached)".format(u.cachedInputTokens)))
        say("  " + theme.muted("output  ") + "%,d tokens".format(u.outputTokens))
        say("  " + theme.muted("cost    ") + (u.cost?.let { TurnRenderer.cost(it) + theme.muted(" for the turns sent from here") } ?: theme.muted("not priced, or none sent from here")))
        conversation.effort?.let { say("  " + theme.muted("effort  ") + it) }
    }

    private fun agent(argument: String?) {
        agents = api.agents(conversation.workspace.id).filter { it.enabled }
        if (agents.isEmpty()) {
            say(theme.muted("This workspace has no agent; add one in Orknux."))
            return
        }
        val now = agents.indexOfFirst { it.id == (conversation.agent?.id ?: conversation.chat?.agentId) }.coerceAtLeast(0)
        val chosen = if (argument == null) {
            chooseFrom("Agent", agents, now) { a -> theme.text(a.name) + (a.description?.let { theme.muted(" - " + TurnRenderer.oneLine(it)) } ?: "") } ?: return
        } else {
            SlashCommands.pick(agents, argument) { it.name } ?: throw OrknuxException("No agent \"$argument\" here.")
        }
        conversation.choose(chosen)
        skills = null
        remember()
        say(theme.muted("Agent ") + theme.text(chosen.name))
    }

    private fun workspace(argument: String?) {
        workspaces = api.workspaces()
        val now = workspaces.indexOfFirst { it.id == conversation.workspace.id }.coerceAtLeast(0)
        val chosen = if (argument == null) {
            chooseFrom("Workspace", workspaces, now) { w -> theme.text(w.name) } ?: return
        } else {
            SlashCommands.pick(workspaces, argument) { it.name } ?: throw OrknuxException("No workspace \"$argument\".")
        }
        if (chosen.id == conversation.workspace.id) return
        conversation.workspace = chosen
        agents = api.agents(chosen.id).filter { it.enabled }
        conversation.agent = agents.firstOrNull { it.id == config.agentId }
        conversation.clear()
        sideChats.clear()
        attachments.clear()
        skills = null
        remember()
        say(theme.muted("Workspace ") + theme.text(chosen.name) + theme.muted(" · a new conversation starts with your next message."))
    }

    private fun skill(argument: String?) {
        val marker = conversation.workspace.commandMarker
            ?: throw OrknuxException("This workspace has no skill marker set, so a skill cannot be named in a message.")
        val all = api.skills(conversation.workspace.id, conversation.agent).also { skills = it }
        if (argument == null) {
            if (all.isEmpty()) {
                say(theme.muted("No skill is within this agent's reach."))
                return
            }
            // Chosen, it is put on the line to say what it should work on: /key, then the message.
            val picked = chooseFrom("Skill", all) { s -> theme.accent("/" + s.key) + theme.muted("  " + (s.description?.let { TurnRenderer.oneLine(it) } ?: s.name)) } ?: return
            prefill = "/${picked.key} "
            return
        }
        val key = argument.substringBefore(' ')
        val rest = argument.substringAfter(' ', "").trim()
        val chosen = SlashCommands.pick(all, key.removePrefix(marker)) { it.key } ?: throw OrknuxException("No skill \"$key\" within this agent's reach.")
        if (rest.isEmpty()) prefill = "$marker${chosen.key} " else turn("$marker${chosen.key} $rest")
    }

    private fun resume(argument: String?) {
        val chats = api.chats(conversation.workspace.id).sortedByDescending { it.lastMessageAt ?: "" }.take(30)
        if (chats.isEmpty()) {
            say(theme.muted("No earlier chats in this workspace."))
            return
        }
        val chosen = if (argument != null) {
            SlashCommands.pick(chats, argument) { it.title } ?: throw OrknuxException("No chat \"$argument\".")
        } else {
            chooseFrom("Chats", chats) { c -> c.title + theme.muted(" · " + (c.agentName ?: "") + " · " + (c.lastMessageAt?.take(16)?.replace('T', ' ') ?: "")) } ?: return
        }
        val thoughts = printHistory(api.messages(chosen.id))
        conversation.resume(chosen)
        conversation.agent = agents.firstOrNull { it.id == chosen.agentId } ?: conversation.agent
        lastTurn = null
        lastThinking = thoughts.takeLast(1)
        remember()
        say("")
        say(theme.muted("Continuing ") + theme.text(chosen.title))
    }

    /** A chat's history as the transcript would have drawn it, thinking folded. Returns the thinking it held. */
    private fun printHistory(messages: List<Message>): List<ThinkingBlock> {
        val markdown = Markdown(theme, { width() }, links)
        val thoughts = mutableListOf<ThinkingBlock>()
        val foldAs = if (config.thinking == "expanded") "collapsed" else config.thinking
        messages.forEach { m ->
            // Each piece with room around it, as a turn is drawn live; say() never makes it two blank lines.
            say("")
            when (m.role.lowercase()) {
                "user" -> say(theme.accent("› ") + theme.text(m.content))
                "assistant" -> {
                    m.thinking?.takeIf { it.isNotBlank() }?.let { text ->
                        val block = ThinkingBlock(text, m.thinkingMillis ?: -1)
                        thoughts += block
                        TurnRenderer.fold(block, foldAs, theme, width()).forEach(::say)
                    }
                    say("")
                    markdown.render(m.content).removeSuffix("\n").split('\n').forEach(::say)
                }
                "thought" -> {
                    val block = ThinkingBlock(m.content, m.thinkingMillis ?: -1)
                    thoughts += block
                    TurnRenderer.fold(block, foldAs, theme, width()).forEach(::say)
                }
                "tool" -> m.content.trim().takeIf { it.isNotEmpty() && it != "{}" }
                    ?.let { say(theme.muted("● " + TurnRenderer.clip(TurnRenderer.oneLine(it), width() - 4))) }
                else -> say(theme.muted(TurnRenderer.clip(TurnRenderer.oneLine(m.content), width())))
            }
        }
        return thoughts
    }

    // ---- attachments -------------------------------------------------------------------------

    private fun attach(paths: List<String>) {
        val home = System.getProperty("user.home")
        paths.forEach { given ->
            val expanded = PastedPaths.clean(given, home) ?: given
            val file = start.resolve(expanded).normalize()
            if (!Files.isRegularFile(file)) {
                say(theme.danger("There is no file $given."))
                return@forEach
            }
            try {
                val kept = api.upload(conversation.workspace.id, file)
                attachments += kept
                say(theme.muted("Attached ") + theme.text(kept.filename) + theme.muted(" (${size(kept.sizeBytes)}) - it goes with your next message."))
            } catch (refused: OrknuxException) {
                say(theme.danger("${file.fileName} was not attached: ${refused.message}"))
            }
        }
        footer()
    }

    private fun listAttachments() {
        if (attachments.isEmpty()) {
            say(theme.muted("Nothing attached. /attach <path> [more paths], or paste a path on its own."))
            return
        }
        attachments.forEachIndexed { i, a -> say("  " + theme.accent("${i + 1}.") + " 📎 " + a.filename + theme.muted(" ${size(a.sizeBytes)}" + (a.contentType?.let { " · $it" } ?: ""))) }
        say(theme.muted("They go with your next message. /detach <n|all> takes one off."))
    }

    private fun detach(argument: String?) {
        when {
            argument == null -> throw OrknuxException("Which one? /detach <n|all>")
            argument.equals("all", ignoreCase = true) -> attachments.clear()
            else -> {
                val n = argument.toIntOrNull()?.takeIf { it in 1..attachments.size } ?: throw OrknuxException("There is no attachment $argument.")
                say(theme.muted("Detached ${attachments.removeAt(n - 1).filename}."))
            }
        }
        footer()
    }

    // ---- links, thinking, effort -------------------------------------------------------------

    private fun open(argument: String?) {
        val found = lastTurn?.links ?: emptyList()
        if (found.isEmpty()) throw OrknuxException("The last answer had no links.")
        val n = argument?.toIntOrNull() ?: if (found.size == 1) 1 else {
            found.forEachIndexed { i, (label, url) -> say("  " + theme.accent("${i + 1}.") + " " + (if (label != url) "$label " else "") + theme.muted(url)) }
            say(theme.muted("/open <n> opens one."))
            return
        }
        val (_, url) = found.getOrNull(n - 1) ?: throw OrknuxException("There is no link $n; the last answer had ${found.size}.")
        runCatching { Links.open(url) }.onFailure { throw OrknuxException("Could not open it: ${it.message}. The address: $url") }
        say(theme.muted("Opened ") + links.link(url, url))
    }

    private fun thinking(argument: String?) {
        if (argument == null) {
            showThinking()
            return
        }
        val mode = argument.lowercase()
        if (mode !in Config.THINKING) throw OrknuxException("Thinking is shown ${Config.THINKING.joinToString(", ")}.")
        config = store.update { it.copy(thinking = mode) }
        say(theme.muted("Thinking is now $mode once it ends."))
    }

    private fun effort(argument: String?) {
        val chat = conversation.chat
        val offered = chat?.let { runCatching { api.efforts(it.id) }.getOrDefault(emptyList()) }
        if (argument == null && !offered.isNullOrEmpty()) {
            val levels = offered + "default"
            val now = levels.indexOf(conversation.effort ?: "default").coerceAtLeast(0)
            val picked = chooseFrom("Reasoning effort", levels, now) { theme.text(it) } ?: return
            return effort(picked)
        }
        if (argument == null) {
            say(theme.muted("Reasoning effort: ") + theme.text(conversation.effort ?: "the model's own"))
            when {
                offered == null -> say(theme.muted("The levels this model takes are known once the chat has started; /effort <level> is kept until then."))
                offered.isEmpty() -> say(theme.muted("This chat's model takes no reasoning effort."))
                else -> say(theme.muted("It takes: ${offered.joinToString(", ")}, or default."))
            }
            return
        }
        val wanted = argument.lowercase().takeIf { it != "default" }
        if (wanted != null && offered != null && offered.isNotEmpty() && wanted !in offered) {
            throw OrknuxException("This model takes ${offered.joinToString(", ")}.")
        }
        conversation.chooseEffort(wanted)
        say(theme.muted("Reasoning effort: ") + theme.text(wanted ?: "the model's own"))
        footer()
    }

    // ---- subagents ---------------------------------------------------------------------------

    private data class Listed(val label: String, val agent: String, val about: String, val status: String, val tokens: String, val show: () -> Unit)

    private fun subagentsListed(): List<Listed> {
        val listed = mutableListOf<Listed>()
        conversation.llmSessionId?.let { session ->
            api.subagents(session).forEach { s ->
                listed += Listed(
                    "asked", s.agent, s.about, if (s.active) "running" else "answered",
                    "↑${TurnRenderer.tokens(s.inputTokens)} ↓${TurnRenderer.tokens(s.outputTokens)}" + (s.cost?.let { " · $it" } ?: ""),
                ) { printSession(s.sessionId) }
            }
        }
        sideChats.values.forEach { side ->
            val chat = side.chat ?: return@forEach
            val u = side.usage
            listed += Listed(
                "@", side.agent?.name ?: "agent", chat.title, if (active === side) "running" else "answered",
                "↑${TurnRenderer.tokens(u.inputTokens)} ↓${TurnRenderer.tokens(u.outputTokens)}",
            ) { printHistory(api.messages(chat.id)) }
        }
        return listed
    }

    private fun subagents(argument: String?) {
        val listed = subagentsListed()
        if (listed.isEmpty()) {
            say(theme.muted("No subagents in this conversation yet: none was asked, and no @agent message was sent."))
            return
        }
        val chosen = if (argument == null) {
            chooseFrom("Subagents", listed) { s ->
                val status = if (s.status == "running") theme.warning("running") else theme.success(s.status)
                theme.text(s.agent) + theme.muted(if (s.label == "@") " (@)" else "") + " " + status +
                    theme.muted(" · ${s.tokens} · " + TurnRenderer.oneLine(s.about))
            } ?: return
        } else {
            val n = argument.toIntOrNull()?.takeIf { it in 1..listed.size } ?: throw OrknuxException("There is no subagent $argument.")
            listed[n - 1]
        }
        say(theme.bold(theme.accent(chosen.agent)) + theme.muted(" - " + TurnRenderer.oneLine(chosen.about)))
        chosen.show()
    }

    /** A subagent's session as a transcript: what it was asked, its tool calls folded, its thinking folded, its answers. */
    private fun printSession(sessionId: String) {
        val markdown = Markdown(theme, { width() }, links)
        api.sessionLines(sessionId).forEach { line ->
            when (line.kind) {
                "USER" -> say(theme.accent("› ") + theme.text(TurnRenderer.clip(TurnRenderer.oneLine(line.content), width() * 3)))
                "AGENT" -> markdown.render(line.content).removeSuffix("\n").split('\n').forEach(::say)
                "TOOL" -> {
                    say(theme.success("●") + " " + theme.bold(line.actor ?: "tool") + theme.muted(" " + TurnRenderer.clip(TurnRenderer.oneLine(line.content), width() - 20)))
                    line.result?.let { say("  " + theme.muted("⎿ " + TurnRenderer.resultSummary(it, width() - 6))) }
                }
                "THINKING" -> TurnRenderer.fold(ThinkingBlock(line.content, -1), "collapsed", theme, width()).forEach(::say)
                else -> {}
            }
        }
    }

    // ---- goals -------------------------------------------------------------------------------

    private fun goal(argument: String?) {
        when {
            argument == null -> {
                val chat = conversation.chat ?: throw OrknuxException("No goal: this conversation has not started.")
                val state = goals.current(chat.id) ?: run {
                    say(theme.muted("No goal on this chat. /goal <what> until <condition> [max <n>] [reviewer <model>]"))
                    return
                }
                goal = state
                say(theme.bold("Goal") + theme.muted(" · ${state.status.lowercase()} · iteration ${state.progress}"))
                say("  " + theme.muted("work at  ") + state.goal)
                say("  " + theme.muted("until    ") + state.condition)
                state.lastFeedback?.let { say("  " + theme.muted("feedback ") + TurnRenderer.oneLine(it)) }
            }
            argument.equals("stop", ignoreCase = true) -> {
                val chat = conversation.chat ?: throw OrknuxException("There is no goal to stop.")
                goals.stop(chat.id)
                goal = goals.current(chat.id)
                say(theme.muted("Goal stopped."))
            }
            else -> {
                val request = Goals.parse(argument)
                val reviewer = request.reviewer?.let { name ->
                    SlashCommands.pick(modelsCached(), name) { it.name } ?: throw OrknuxException("No model \"$name\" in this workspace.")
                }
                val chat = conversation.open()
                val started = goals.start(chat.id, request, reviewer?.id)
                goal = started
                say(theme.accent("◆ Goal") + theme.muted(" set: ") + request.goal + theme.muted(" · until ") + request.condition +
                    theme.muted(" · at most ${request.max ?: "the server's default"} iterations" + (reviewer?.let { " · judged by ${it.name}" } ?: "")))
                follow(chat.id)
            }
        }
        footer()
    }

    /**
     * While a goal runs the server works the chat by itself, an iteration at a
     * time, so the Shell follows it: each turn as it is written - the same
     * frames a sent message gets - and each iteration's verdict as a line.
     */
    private fun follow(chatId: String) {
        val output = object : TurnOutput {
            override fun line(text: String) = say(text)
            override fun phase(key: String, text: String) {
                val g = goal
                if (region.active && g != null) region.phase("goal:$key", "Goal · iteration ${g.iteration + 1} of ${g.maxIterations ?: "∞"} · " + stripAnsi(text).lowercase())
            }
            override fun width(): Int = this@Interactive.width()
        }
        while (goal?.running == true) {
            val renderer = TurnRenderer(theme, output, config.thinking, links)
            var followed = false
            phase = Phase.TURN
            region.phase("goal", "Goal · iteration ${(goal?.iteration ?: 0) + 1} of ${goal?.maxIterations ?: "∞"} · working")
            region.start { below() }
            val ticker = Thread.ofPlatform().daemon().start {
                var n = 0
                while (phase == Phase.TURN) {
                    region.tick()
                    if (++n % 10 == 0) footer()
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
            val saved = terminal.enterRawMode()
            val keys = Thread.ofPlatform().daemon().start {
                val input = terminal.reader()
                while (phase == Phase.TURN && !Thread.currentThread().isInterrupted) {
                    val c = try { input.read(100) } catch (_: Exception) { break }
                    if (c == 3 || c == 27) {
                        runCatching { goals.stop(chatId) }
                        region.halt("Goal stopped")
                        break
                    }
                }
            }
            try {
                api.client.stream("/api/chats/$chatId/follow?wait=true", null) { frame ->
                    if (frame.event == "following") followed = true
                    renderer.handle(ChatEvent.of(frame))
                }
            } catch (failed: OrknuxException) {
                say(theme.danger("✗ " + (failed.message ?: "Lost the chat.")))
            } finally {
                renderer.flush()
                phase = Phase.OTHER
                keys.interrupt()
                keys.join(500)
                ticker.interrupt()
                region.stop()
                terminal.attributes = saved
            }
            val before = goal
            val now = runCatching { goals.current(chatId) }.getOrNull()
            goal = now
            if (now != null && (now.iteration != before?.iteration || now.status != before.status)) say(Goals.verdictLine(now, theme))
            conversation.refresh()
            if (!followed && now?.running == true) Thread.sleep(1_000)
        }
        footer()
    }

    /** After a turn of the main chat, the goal's verdict where one is running. */
    private fun checkGoal() {
        val before = goal?.takeIf { it.running } ?: return
        val chat = conversation.chat ?: return
        val now = runCatching { goals.current(chat.id) }.getOrNull() ?: return
        goal = now
        if (now.iteration != before.iteration || now.status != before.status) say(Goals.verdictLine(now, theme))
        if (now.running) follow(chat.id)
    }

    // ---- review ------------------------------------------------------------------------------

    private fun review(argument: String?) {
        val request = Review.parse(argument)
        val diff = Review.gather(request, start)
        say(theme.muted("Reviewing ${diff.description}: ${diff.files.size} file${if (diff.files.size == 1) "" else "s"}, ${"%,d".format(diff.diff.length)} characters."))
        diff.cut?.let { say(theme.warning(it)) }
        val marker = conversation.workspace.commandMarker
        val skill = marker?.let { Review.skillFor(skillsCached()) }
        skill?.let { say(theme.muted("With the skill ") + theme.accent(marker + it.key)) }
        turn(Review.message(diff, skill?.let { marker + it.key }))
        val findings = Review.findings(lastTurn?.answer?.toString() ?: "")
        Review.render(findings, diff.root, theme, links).forEach(::say)
        if (request.fix && findings.isNotEmpty()) turn(Review.fixMessage())
    }

    // ---- small things ------------------------------------------------------------------------

    /**
     * One of [items], chosen with the arrow keys and Enter - or by number off
     * a terminal that cannot draw the list in place.
     */
    private fun <T> chooseFrom(what: String, items: List<T>, current: Int = 0, label: (T) -> String): T? {
        if (items.isEmpty()) return null
        if (!::region.isInitialized || !region.ansi) {
            say(theme.bold(what))
            items.forEachIndexed { i, item -> say("  " + theme.accent("${i + 1}.") + " " + label(item)) }
            val answer = ask(theme.muted("Number (Enter to cancel) › "))
            if (answer.isEmpty()) return null
            return SlashCommands.pick(items, answer) { stripAnsi(label(it)) }
        }
        val picker = Picker(
            sink = { text -> terminal.writer().print(text); terminal.writer().flush() },
            key = { timeout -> terminal.reader().read(if (timeout < 0) 0L else timeout) },
            theme = theme,
            rows = { terminal.height.takeIf { it > 0 } ?: 24 },
            width = ::width,
        )
        val saved = terminal.enterRawMode()
        val chosen = try {
            synchronized(out) { picker.choose(what, items.map(label), current) }
        } finally {
            terminal.attributes = saved
        }
        // The list pushed the screen up while it was open; the rows come back, the prompt above the bar.
        pullDown(picker.lastHeight - 1)
        return chosen?.let { items[it] }
    }

    /**
     * The colours: a list to choose one from and give it a new value, applied
     * at once and kept in the configuration. `/theme accent #5b8def` sets one
     * directly, `/theme reset` puts them all back; off a terminal it lists them.
     */
    private fun theme(argument: String?) {
        val words = argument?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        when {
            words.firstOrNull()?.lowercase() == "reset" -> {
                Theme.DEFAULTS.keys.forEach { theme.set(it, null) }
                keepTheme()
                say(theme.muted("The colours are Orknux's own again."))
            }
            words.size == 2 -> {
                val (name, hex) = words[0].lowercase() to words[1]
                if (!theme.set(name, hex)) throw OrknuxException(
                    if (name !in Theme.DEFAULTS) "There is no colour called $name. The colours are ${Theme.DEFAULTS.keys.joinToString(", ")}."
                    else "$hex is not a colour; write it as #rrggbb.",
                )
                keepTheme()
                say("  " + theme.paint(name, "████") + " " + theme.text(name) + theme.muted(" is now ${theme.hex(name)}."))
            }
            !region.ansi -> listTheme()
            else -> chooseColour()
        }
    }

    private fun listTheme() {
        say(theme.bold("Theme") + theme.muted(" (${theme.depth.name.lowercase()} colour, links ${if (links.osc8) "clickable" else "written out"})"))
        theme.colours.forEach { (name, hex) -> say("  " + theme.paint(name, "████") + " " + theme.text(name.padEnd(9)) + theme.muted(hex)) }
        say(theme.muted("/theme <name> #rrggbb changes one, /theme reset puts them back; kept in ${store.path}."))
    }

    private fun chooseColour() {
        var at = 0
        while (true) {
            val names = Theme.DEFAULTS.keys.toList()
            val rows = names.map { name ->
                val own = theme.hex(name) == Theme.DEFAULTS[name]
                theme.paint(name, "████") + " " + name.padEnd(9) + theme.muted(theme.hex(name) + if (own) "" else "  (yours)")
            } + theme.muted("Orknux's own colours, all of them")
            val chosen = chooseFrom("Theme", rows.indices.toList(), at) { rows[it] } ?: return
            if (chosen == names.size) {
                Theme.DEFAULTS.keys.forEach { theme.set(it, null) }
                keepTheme()
                say(theme.muted("The colours are Orknux's own again."))
                return
            }
            val name = names[chosen]
            at = chosen
            val typed = try {
                reader.readLine(theme.paint(name, "████") + " " + name + theme.muted(" (#rrggbb, empty for Orknux's own, Esc to keep) › "), null, null as org.jline.reader.MaskingCallback?, theme.hex(name)).trim().also { lastBlank = false }
            } catch (_: UserInterruptException) {
                continue
            } catch (_: org.jline.reader.EndOfFileException) {
                return
            }
            if (typed.isEmpty()) theme.set(name, null)
            else if (!theme.set(name, typed)) {
                say(theme.danger("$typed is not a colour; write it as #rrggbb."))
                continue
            }
            keepTheme()
        }
    }

    /** What differs from Orknux's own, into the configuration, and the bar and the lists redrawn in it. */
    private fun keepTheme() {
        config = store.update { it.copy(theme = theme.overrides()) }
        val accent = theme.hex("accent")
        listOf(LineReader.COMPLETION_STYLE_LIST_STARTING, LineReader.COMPLETION_STYLE_STARTING).forEach { reader.setVariable(it, "fg-rgb:$accent") }
        footer()
    }

    private fun showRules() {
        val rules = config.commands
        say(theme.bold("Local command rules") + theme.muted(" - Java regular expressions found in the command line; first match wins."))
        listOf("deny" to rules.deny, "ask" to rules.ask, "allow" to rules.allow).forEach { (name, list) ->
            say("  " + theme.accent(name) + theme.muted(if (list.isEmpty()) "  (none)" else ""))
            list.forEach { say("    " + theme.code(it)) }
        }
        say(theme.muted("Order: deny, ask, the ask mode, allow, then sudo and the agent's 'dangerous' are asked about. In ${store.path}."))
        handler.policy.problems.forEach { say(theme.warning(it)) }
    }

    companion object {
        /** How much of a release's notes /update shows before it says where the rest is. */
        const val RELEASE_NOTE_LINES = 40

        /**
         * A release's notes as the Markdown they are - headings, bullets, a blank
         * line between sections - and, where they run long, cut at a section's end
         * with word of the rest rather than in the middle of one.
         */
        fun releaseNotes(theme: Theme, markdown: String?, width: Int, most: Int = RELEASE_NOTE_LINES): List<String> {
            if (markdown.isNullOrBlank()) return emptyList()
            val lines = Markdown(theme, { width }).render(markdown.trim()).removeSuffix("\n").split('\n')
                .fold(mutableListOf<String>()) { kept, line -> if (!(line.isBlank() && kept.lastOrNull()?.isBlank() != false)) kept += line; kept }
            if (lines.size <= most) return lines.map { "  $it" }
            val cut = lines.subList(0, most).indexOfLast { it.isBlank() }.takeIf { it > 0 } ?: most
            return lines.subList(0, cut).map { "  $it" } + listOf("", theme.muted("  …and more: https://github.com/orknux/orknux-shell/blob/main/CHANGELOG.md"))
        }


        /** How long the command door may take to open at the start before the bar says it is not open. */
        const val DEVICE_GRACE_MILLIS = 15_000L

        /** How many commands the list under a / shows at once. */
        const val SLASH_ROWS = 8


        /** How much of the thinking the live block shows: its last lines, as the web chat's does. */
        const val THINKING_LINES = 5

        fun size(bytes: Long): String = when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }
}

/** The line reader, able to take back the list a completion left under the line - JLine keeps it until the line is done. */
class ShellLineReader(
    terminal: Terminal,
    private val resized: () -> Unit = {},
    /** What is drawn under the line being typed - the list of commands - in the place a completion list is drawn. */
    private val under: () -> List<String> = { emptyList() },
    /**
     * Told how many rows what was under the line gave up: the prompt sits on the
     * last row above the status bar, so lines drawn under it pushed the screen
     * up by as many, and they are pulled back down when the lines go.
     */
    private val shrunk: (Int) -> Unit = {},
) : org.jline.reader.impl.LineReaderImpl(terminal, "Orknux Shell", HashMap()) {
    private var cleaning = false

    fun dropList() {
        post = null
    }

    /**
     * The status line under the prompt, given as the lines the reader draws below
     * what is typed: they move down the window with the prompt, a completion list
     * takes their place while it is open, and they are drawn fresh every time.
     */
    override fun redisplay(flush: Boolean) {
        // Ours, drawn again from what is true now at every redisplay; a list JLine put there itself (Tab) is left be.
        if ((post == null || post === ours) && !cleaning) {
            val lines = under()
            drawn = lines
            post = if (lines.isEmpty()) null else ours
        } else if (post === ours && cleaning) {
            post = null
        }
        super.redisplay(flush)
        // Counted apart from `post`, which JLine clears by itself when a line is taken.
        val now = if (post === ours) drawn.size else 0
        if (now < shownRows) shrunk(shownRows - now)
        shownRows = now
    }

    /** How many of our rows are under the line now. */
    private var shownRows = 0

    private var drawn: List<String> = emptyList()
    private val ours = java.util.function.Supplier { org.jline.utils.AttributedString.fromAnsi(drawn.joinToString("\n")) }

    /** A line taken: drawn once more with nothing under it, so the status line is not left behind in the transcript. */
    override fun cleanup() {
        cleaning = true
        try {
            super.cleanup()
        } finally {
            cleaning = false
        }
    }

    /**
     * While a line is being read the reader holds the window-size signal, so a
     * handler set on the terminal never hears it: the bars kept the width they
     * were drawn at. The reader resizes as it does, and then the bars are
     * drawn again at the new width.
     */
    override fun handleSignal(signal: Terminal.Signal) {
        super.handleSignal(signal)
        if (signal == Terminal.Signal.WINCH) resized()
    }
}
