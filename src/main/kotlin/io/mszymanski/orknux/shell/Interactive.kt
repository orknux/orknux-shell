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
        runCatching { status?.update(listOf(AttributedString(""), AttributedString(""))) }
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

        device = DeviceLoop(api.client, handler, deviceReport())
        device.start()
        footer()
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
     * The whole window, from the top: what the shell had on it is scrolled
     * up into the terminal's scrollback - not wiped, and not an alternate
     * screen, so the conversation itself stays in the scrollback too and a
     * mouse wheel reads back through it - and the chat is written from the
     * foot of a clean screen up, its prompt directly above the status bar.
     */
    private fun takeScreen() {
        val rows = terminal.height.takeIf { it > 0 } ?: return
        // Left on the last row rather than taken to the top: everything is written at the foot and scrolls up,
        // so the prompt always sits directly above the status bar, as in Claude Code.
        terminal.writer().print("\u001b[$rows;1H" + "\n".repeat(rows))
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
        say(theme.muted("/help for commands · @agent to ask another agent · Shift+Tab switches mode · Ctrl+C interrupts · Ctrl+D quits"))
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
        val r = ShellLineReader(terminal).apply {
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
            option(LineReader.Option.AUTO_MENU_LIST, true)
        }
        /*
         * The commands are listed as a / is typed, and narrowed with every key
         * after it, as in Claude Code: nobody should have to know that Tab is
         * what shows them. Only while the line is one word starting with /,
         * and the cursor at its end; past that, Tab completes as before.
         */
        for (name in listOf(LineReader.SELF_INSERT, LineReader.BACKWARD_DELETE_CHAR)) {
            val plain = r.widgets[name] ?: continue
            r.widgets[name] = org.jline.reader.Widget {
                val done = plain.apply()
                val typed = r.buffer.toString()
                if (r.buffer.cursor() == typed.length && Regex("""/[^\s/]*""").matches(typed)) autoList(r, typed) else closeList(r)
                done
            }
        }
        r.widgets["orknux-close-list"] = org.jline.reader.Widget { closeList(r); true }
        r.widgets["orknux-cycle-mode"] = org.jline.reader.Widget { cycleMode(); true }
        r.widgets["orknux-newline"] = org.jline.reader.Widget { r.buffer.write("\n"); true }
        r.widgets["orknux-thinking"] = org.jline.reader.Widget { showThinking(); true }
        val keys = r.keyMaps[LineReader.MAIN]!!
        keys.bind(Reference("orknux-cycle-mode"), "\u001b[Z")
        keys.bind(Reference("orknux-thinking"), KeyMap.ctrl('O'))
        keys.bind(Reference("orknux-close-list"), KeyMap.esc())
        listOf("\u001b\r", "\u001b\n", "\u001b[13;2u", "\u001b[27;2;13~").forEach { keys.bind(Reference("orknux-newline"), it) }
        keys.bind(Reference(LineReader.ACCEPT_LINE), KeyMap.ctrl('M'))
        return r
    }

    /** How many rows a list under the prompt pushed the screen up; given back when it goes. */
    private var pushedUp = 0

    /** Takes the list away, and the screen back down so the prompt is directly above the status bar again. */
    private fun closeList(r: LineReader) {
        if (pushedUp == 0) return
        (r as? ShellLineReader)?.dropList()
        r.callWidget(LineReader.REDISPLAY)
        pullDown(pushedUp)
        pushedUp = 0
    }

    /**
     * Moves the scrolling area's text down [rows] rows and the cursor with it:
     * what a list, or an empty line entered, pushed up comes back, and the
     * prompt is on the last row above the bar. Blank rows come in at the top.
     */
    private fun pullDown(rows: Int) {
        if (rows <= 0 || !region.ansi) return
        terminal.writer().print("\u001b[${rows}T\u001b[${rows}B")
        terminal.writer().flush()
    }

    /** True while the list is drawn as one types, rather than on Tab. */
    @Volatile private var listingAsTyped = false

    /**
     * The list under a / as it is typed - only where it fits on the screen,
     * since a list that does not fit asks "do you wish to see all" and takes
     * the next key as the answer. A / alone lists the Shell's own commands;
     * a letter after it narrows to commands and skills alike.
     */
    private fun autoList(r: LineReader, typed: String) {
        listingAsTyped = true
        try {
            val count = completions.complete(typed).size
            if (count in 1..(terminal.height - 8).coerceAtLeast(5)) {
                r.callWidget(LineReader.LIST_CHOICES)
                // The prompt sits on the last row, so a list under it pushed the screen up by its own height.
                pushedUp = maxOf(pushedUp, count)
            } else {
                closeList(r)
            }
        } finally {
            listingAsTyped = false
        }
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
            // Entered with a list under it: the list is gone and the line moved on by one, as at the foot it would have.
            if (pushedUp > 0) {
                pullDown(pushedUp - 1)
                pushedUp = 0
            }
            val text = line.replace("\\\n", "\n").trim()
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
            } catch (refused: OrknuxException) {
                say(theme.danger(refused.message ?: "Refused."))
            } catch (bad: IllegalArgumentException) {
                say(theme.danger(bad.message ?: "That cannot be done."))
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
        reader.readLine(prompt).trim()
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
                region.thinking(text)
            }
            override fun width(): Int = this@Interactive.width()
        }
        val renderer = TurnRenderer(theme, output, config.thinking, links)
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
        region.phase("thinking", "Thinking")
        region.start { below() }
        // Ten frames a second, and only while a turn runs: nothing ticks when nothing is happening.
        val ticker = Thread.ofPlatform().daemon().name("orknux-shell-ticker").start {
            var n = 0
            while (phase == Phase.TURN) {
                region.tick()
                if (++n % 10 == 0 && status != null) footer()
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
        renderer.linkList().forEach(::say)
        if (chatBefore == null && api.startedWithoutOrigin) {
            say(theme.warning("This server does not know chats opened in Orknux Shell yet, so its agent cannot run commands here."))
        }
        if (status == null) say(theme.muted(statusText()))
        // A blank line between an answer and the next prompt.
        say("")
        if (into === conversation) checkGoal()
    }

    /** What sits under the spinner: the files waiting to go, and the status line. */
    private fun below(): List<String> =
        if (status != null) emptyList() else listOfNotNull(chips().takeIf { attachments.isNotEmpty() }, statusText())

    /** Ctrl+C or Esc during a turn: the spinner stops at once, and the server is asked to stop. */
    private fun interruptTurn() {
        val said = active?.interrupt() ?: return
        region.halt("Interrupted")
        say(theme.warning(said))
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
                    15 -> showThinking()
                    27 -> if (input.peek(30) == NonBlockingReader.READ_EXPIRED) interruptTurn() else drainSequence(input)
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
    private fun showThinking() {
        val blocks = (lastTurn?.blocks?.toList() ?: lastThinking).ifEmpty { lastThinking }
        val live = liveThinking
        if (blocks.isEmpty() && live == null) {
            say(theme.muted("No thinking in the last turn."))
            return
        }
        blocks.forEach { block -> TurnRenderer.fold(block, "expanded", theme, width()).forEach(::say) }
        live?.let { TurnRenderer.fold(ThinkingBlock(it, System.currentTimeMillis() - thinkingSince), "expanded", theme, width()).forEach(::say) }
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
                if (suspended) region.start { below() }
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
            val extra = listOfNotNull(
                "${result.seconds}s",
                if (approval == "person") "approved" else null,
                if (result.truncated) "output cut to the last ${CommandRunner.OUTPUT_LIMIT / 1000}k" else null,
                if (result.timedOut) "stopped at its time limit" else null,
            ).joinToString(" · ")
            say(theme.dim(theme.muted("$ " + TurnRenderer.clip(TurnRenderer.oneLine(request.command), width() - 4))))
            say(theme.dim("  ⎿ ") + exit + theme.dim(theme.muted(" · $extra")))
        }

        override fun refused(request: RunRequest, why: String) {
            say(theme.dim(theme.muted("$ " + TurnRenderer.clip(TurnRenderer.oneLine(request.command), width() - 4))))
            say(theme.dim("  ⎿ ") + theme.danger(why))
        }

        override fun notice(text: String) = say(theme.muted(text))
        override fun error(text: String) = say(theme.warning(text))
    }

    // ---- the screen --------------------------------------------------------------------------

    /**
     * One line into the transcript, above the editor and the status line, whoever
     * is holding the keyboard. Written as it is - an OSC 8 link included, which
     * the line editor's own styling would strip.
     */
    private fun say(text: String) {
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

    private fun width() = terminal.width.takeIf { it > 0 } ?: 100

    /** The status line under the editor, with the files waiting to go; while a turn runs, the region draws it instead. */
    private fun footer() {
        if (::region.isInitialized && region.active && status == null) {
            region.redraw()
            return
        }
        val st = status ?: return
        // A blank row above it and a column in from the edge: the bar is a bar, not the last line of the answer.
        val lines = listOf("") + listOfNotNull(chips().takeIf { attachments.isNotEmpty() }, statusText()).map { " $it" }
        val draw = { runCatching { st.update(lines.map { AttributedString.fromAnsi(LiveRegion.clip(it, width() - 2)) }) } }
        synchronized(out) { if (::region.isInitialized) region.exclusive(draw) else draw() }
    }

    private fun chips(): String = attachments.mapIndexed { i, a ->
        theme.accent("📎 ") + theme.text(a.filename) + theme.muted(" ${size(a.sizeBytes)} ") + theme.muted("×${i + 1}")
    }.joinToString("  ")

    private fun statusText(): String {
        val c = conversation
        val u = c.usage
        val device = if (::device.isInitialized && device.connected) theme.success("●") + theme.muted(" local") else theme.muted("○ local")
        return listOfNotNull(
            theme.text(c.workspace.name),
            theme.text(c.agent?.name ?: c.chat?.agentName ?: "agent"),
            modeLabel(),
            c.effort?.let { theme.muted("effort ") + theme.text(it) },
            goal?.takeIf { it.running }?.let { theme.accent("goal ${it.progress}") },
            theme.muted("↑${TurnRenderer.tokens(u.inputTokens)} ↓${TurnRenderer.tokens(u.outputTokens)} (${TurnRenderer.tokens(u.cachedInputTokens)} cached)"),
            // The cost only where the model has a price; a local model's has none, and a dash read as a glitch.
            u.cost?.let { theme.muted(TurnRenderer.cost(it)) },
            device,
        ).joinToString(theme.muted(" · "))
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
            "theme" -> showTheme()
            "rules" -> showRules()
            "attach" -> if (argument == null) listAttachments() else attach(PastedPaths.tokens(slash.argument) ?: throw OrknuxException("A quote is not closed."))
            "detach" -> detach(argument)
            "open" -> open(argument)
            "thinking" -> thinking(argument)
            "effort" -> effort(argument)
            "subagents" -> subagents(argument)
            "review" -> review(argument)
            "goal" -> goal(argument)
        }
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
        say(theme.muted("Continuing ") + theme.text(chosen.title))
    }

    /** A chat's history as the transcript would have drawn it, thinking folded. Returns the thinking it held. */
    private fun printHistory(messages: List<Message>): List<ThinkingBlock> {
        val markdown = Markdown(theme, { width() }, links)
        val thoughts = mutableListOf<ThinkingBlock>()
        val foldAs = if (config.thinking == "expanded") "collapsed" else config.thinking
        messages.forEach { m ->
            when (m.role.lowercase()) {
                "user" -> say(theme.accent("› ") + theme.text(m.content))
                "assistant" -> {
                    m.thinking?.takeIf { it.isNotBlank() }?.let { text ->
                        val block = ThinkingBlock(text, m.thinkingMillis ?: -1)
                        thoughts += block
                        TurnRenderer.fold(block, foldAs, theme, width()).forEach(::say)
                    }
                    markdown.render(m.content).removeSuffix("\n").split('\n').forEach(::say)
                }
                "thought" -> {
                    val block = ThinkingBlock(m.content, m.thinkingMillis ?: -1)
                    thoughts += block
                    TurnRenderer.fold(block, foldAs, theme, width()).forEach(::say)
                }
                "tool" -> say(theme.muted("● " + TurnRenderer.clip(TurnRenderer.oneLine(m.content), width() - 4)))
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
        val n = argument?.toIntOrNull() ?: if (found.size == 1) 1 else throw OrknuxException("Which one? /open 1 to ${found.size}")
        val (_, url) = found.getOrNull(n - 1) ?: throw OrknuxException("There is no link $n; the last answer had ${found.size}.")
        runCatching { Links.open(url) }.onFailure { throw OrknuxException("Could not open a browser: ${it.message}. The address: $url") }
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
                    if (++n % 10 == 0 && status != null) footer()
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

    private fun showTheme() {
        say(theme.bold("Theme") + theme.muted(" (${theme.depth.name.lowercase()} colour, links ${if (links.osc8) "clickable" else "written out"})"))
        theme.colours.forEach { (name, hex) -> say("  " + theme.paint(name, "████") + " " + theme.text(name.padEnd(9)) + theme.muted(hex)) }
        say(theme.muted("Change any of them under \"theme\" in ${store.path}, as #rrggbb."))
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
class ShellLineReader(terminal: Terminal) : org.jline.reader.impl.LineReaderImpl(terminal, "Orknux Shell", HashMap()) {
    fun dropList() {
        post = null
    }
}
