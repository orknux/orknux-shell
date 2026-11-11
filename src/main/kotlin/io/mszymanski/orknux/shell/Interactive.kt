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

    /**
     * While a turn runs: the status bar's tokens and the count of subagents
     * kept current. The chat's own totals grow only when a turn ends; its
     * session's grow with every round, so they are read every few seconds
     * and shown where they are ahead.
     */
    private fun followLive() {
        Thread.ofVirtual().name("orknux-live-usage").start {
            // What the session had spent as the turn began, and the chat's totals then: what the session spends from
            // here is added to those, so the bar only grows and agrees with the chat's own totals when the turn ends.
            val c0 = conversationOrNull()
            val from = c0?.usage
            // A chat's first turn has no session yet: it is made by the turn, and everything it spends is this turn's.
            val base = c0?.llmSessionId?.let { id -> runCatching { api.session(id) }.getOrNull()?.let { it.inputTokens to it.outputTokens } } ?: (0L to 0L)
            while (phase == Phase.TURN) {
                try {
                    Thread.sleep(LIVE_MILLIS)
                } catch (_: InterruptedException) {
                    return@start
                }
                if (phase != Phase.TURN) break
                val c = conversationOrNull() ?: continue
                val id = c.llmSessionId ?: continue
                if (c !== c0 || from == null) {
                    countSessions(force = true)
                    continue
                }
                runCatching { api.session(id) }.getOrNull()?.let { s ->
                    val more = c.sessionSoFar(from.inputTokens + (s.inputTokens - base.first), from.outputTokens + (s.outputTokens - base.second))
                    if (more) footer()
                }
                countSessions(force = true)
            }
        }
    }

    /** Ctrl+T pressed at the prompt: the line is set aside, the list shown, and the line given back. */
    @Volatile private var sessionsAsked = false
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
        region.anchored = drawable && status != null
        terminal.handle(Terminal.Signal.INT) { if (phase == Phase.TURN) interruptTurn() }
        // The window resized: the terminal has rewrapped what it had, and the bar's rows and the scrolling area no
        // longer agree with it, so all of it is written again at the new size.
        terminal.handle(Terminal.Signal.WINCH) { repaint() }

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
        // How many lines the welcome is: a redraw keeps them at the top, as the start put them.
        welcomeLines = transcript.lines(unfolded).size
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
        val agent = Connection.agent(agents, config, named.second) { api.chats(workspace.id) }
        conversation = Conversation(api, workspace, agent, config.mode)
        remember()
        say(
            theme.muted("Workspace ") + theme.text(workspace.name) + theme.muted(" · agent ") +
                theme.text(agent?.name ?: "the one you last talked to") + theme.muted(" · mode ") + modeLabel(),
        )
        say(theme.muted("/help for commands · @agent to ask another agent · ${keyName("details")} expands thoughts and tool calls · ${keyName("sessions")} sessions · ${pasteKey()} pastes a picture · Shift+Tab switches mode · Ctrl+C interrupts · Ctrl+D quits"))
        // A newer Shell is said here, with the rest of the welcome, rather than above the prompt whenever the look
        // came back. The look is given a moment; slower than that, the status bar says it once it knows.
        startingNews?.let { news ->
            news.withdrawn?.let { say(theme.warning("This version, ${Version.VERSION}, was withdrawn: $it") + theme.muted(" · /update")) }
            news.newer?.let {
                say(theme.muted("Orknux Shell ") + theme.accent(it) + theme.muted(" is out - ") + theme.accent("/update") + theme.muted(" to install it."))
                updateWaiting = it
            }
        }
        say("")
    }

    /** What the look for an update at the start found, in time for the welcome; null where it found nothing or took too long. */
    private val startingNews: UpdateNews? by lazy {
        runCatching {
            java.util.concurrent.CompletableFuture.supplyAsync { DailyCheck.run(store, Updater(), force = true) }
                .get(STARTING_LOOK_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun remember() {
        val c = conversation
        // My Claude Code is not an agent of the workspace to start the next Shell on.
        if (c.claudeCode) return
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
        val r = ShellLineReader(terminal, { repaint() }, ::underPrompt, ::pullDown).apply {
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
        r.widgets["orknux-thinking"] = org.jline.reader.Widget { toggleDetails(); true }
        r.widgets["orknux-sessions"] = org.jline.reader.Widget {
            sessionsAsked = true
            terminal.raise(Terminal.Signal.INT)
            true
        }
        val keys = r.keyMaps[LineReader.MAIN]!!
        keys.bind(Reference("orknux-cycle-mode"), "\u001b[Z")
        bindShortcuts(r)
        // Ctrl+L clears as the Shell draws, the prompt back at the foot over the status bar: the line editor's own
        // left it on the top row, and everything after hung from there with the screen empty under it.
        r.widgets["orknux-clear-screen"] = org.jline.reader.Widget { repaint(); true }
        keys.bind(Reference("orknux-clear-screen"), KeyMap.ctrl('L'))
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
        bindPictures(r)
        return r
    }

    // ---- pictures: pasted in, copied out -------------------------------------------------------

    /** Pictures pasted into the line being written, shown in it as chips. */
    private val imageChips = ImageChips()

    /** Every picture an answer showed in this Shell, in order: `/copy-image <n>` numbers them so. */
    private val pictures = mutableListOf<ShownPicture>()

    /** The clipboard, found once; refused in words where this computer offers none. */
    private val clipboard: Result<Clipboard> by lazy { Clipboards.find() }

    /**
     * Ctrl+V (and Alt+V, for a terminal that keeps Ctrl+V for its own paste)
     * pastes the clipboard: a picture becomes a chip in the line and an
     * attachment of the next message, files copied in a file manager do the
     * same where they are pictures, and text is typed in as it is. A terminal's
     * own paste - and a file dragged onto it - arrives as text: a path to a
     * picture is made a chip as well. Backspace at a chip's end takes it out.
     */
    private fun bindPictures(r: LineReader) {
        val keys = r.keyMaps[LineReader.MAIN] ?: return
        r.widgets["orknux-paste"] = org.jline.reader.Widget { pasteClipboard(r); true }
        keys.bind(Reference("orknux-paste"), KeyMap.ctrl('V'))
        keys.bind(Reference("orknux-paste"), KeyMap.alt('v'))

        val deleting = r.widgets[LineReader.BACKWARD_DELETE_CHAR]
        if (deleting != null) {
            r.widgets[LineReader.BACKWARD_DELETE_CHAR] = org.jline.reader.Widget {
                val (chip, length) = imageChips.endingAt(r.buffer.toString(), r.buffer.cursor()) ?: return@Widget deleting.apply()
                r.buffer.backspace(length)
                imageChips.remove(chip)?.let { attachments.remove(it) }
                footer()
                true
            }
        }

        val pasting = r.widgets[LineReader.BEGIN_PASTE] ?: return
        r.widgets[LineReader.BEGIN_PASTE] = org.jline.reader.Widget {
            val before = r.buffer.toString()
            val at = r.buffer.cursor()
            val done = pasting.apply()
            val after = r.buffer.toString()
            val inserted = after.length - before.length
            if (inserted > 0 && after.startsWith(before.substring(0, at))) {
                val text = after.substring(at, at + inserted)
                val paths = PastedPaths.detect(text)?.takeIf { found -> found.all { Pictures.typeOf(it) != null } }
                if (paths != null) {
                    r.buffer.cursor(at + inserted)
                    r.buffer.backspace(inserted)
                    paths.forEach { pastePicture(r, Path.of(it)) }
                }
            }
            done
        }
    }

    /**
     * The key that pastes a picture here, as the person should press it. Alt+V
     * on Windows and in WSL: every terminal there - Windows Terminal, VS Code's,
     * the console - keeps Ctrl+V for its own text-only paste, so the key never
     * reaches the Shell, and WT_SESSION alone missed the ones that do not set it
     * and the WSL shells it is not passed into. Ctrl+V elsewhere, where a
     * terminal pastes with Ctrl+Shift+V and leaves Ctrl+V to the program.
     */
    private fun pasteKey(): String =
        if (!System.getenv("WT_SESSION").isNullOrBlank() || Platform.isWindows() || Clipboards.isWsl()) "Alt+V" else "Ctrl+V"

    private fun pasteClipboard(r: LineReader) {
        val board = clipboard.getOrElse {
            say(theme.warning(it.message ?: "No clipboard."))
            return
        }
        when (val held = try { board.read() } catch (failed: Exception) { say(theme.warning("The clipboard could not be read through ${board.name}: ${failed.message}")); return }) {
            is Clipped.Image -> pasteBytes(r, held.picture)
            is Clipped.Files -> {
                val (shown, other) = held.paths.partition { Pictures.typeOf(it) != null }
                shown.forEach { pastePicture(r, Path.of(it)) }
                // Anything else copied is typed as its path, which Enter offers to attach as a pasted path always was.
                if (other.isNotEmpty()) r.buffer.write(other.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it })
            }
            is Clipped.Text -> r.buffer.write(held.text.replace("\r\n", "\n"))
            Clipped.Empty -> say(theme.muted("The clipboard holds nothing to paste."))
        }
    }

    /**
     * `/paste`: the clipboard's picture attached to the next message, with no key
     * pressed - Windows Terminal keeps Ctrl+V and Ctrl+Shift+V for its own paste,
     * which carries text only, so a picture copied there never reaches the Shell
     * by a key. Listed with the other attachments rather than as a chip, since
     * no line is being written while a command runs.
     */
    private fun pasteToNext(message: String? = null) {
        val board = clipboard.getOrElse { throw OrknuxException(it.message ?: "No clipboard.") }
        when (val held = try { board.read() } catch (failed: Exception) { throw OrknuxException("The clipboard could not be read through ${board.name}: ${failed.message}") }) {
            is Clipped.Image -> {
                val picture = held.picture
                val name = "pasted-image-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.${picture.extension}"
                val kept = try {
                    api.uploadPicture(conversation.workspace.id, name, picture.contentType, picture.bytes)
                } catch (refused: OrknuxException) {
                    throw OrknuxException("The picture was not attached: ${refused.message}")
                }
                attachments += kept
                val size = picture.size?.let { (w, h) -> " ${w}×$h" } ?: ""
                if (message == null) {
                    say(theme.muted("Attached ") + theme.text(kept.filename) + theme.muted("$size - it goes with your next message."))
                    footer()
                } else {
                    // `/paste what is this?`: the picture and the question go together, now.
                    say(theme.muted("Attached ") + theme.text(kept.filename) + theme.muted(size))
                    turn(message)
                }
            }
            is Clipped.Files -> {
                val shown = held.paths.filter { Pictures.typeOf(it) != null }
                if (shown.isEmpty()) {
                    say(theme.muted("The clipboard holds files that are not pictures; /attach <path> attaches any file."))
                } else {
                    attach(shown)
                    if (message != null && attachments.isNotEmpty()) turn(message)
                }
            }
            is Clipped.Text -> say(theme.muted("The clipboard holds text, not a picture. Copy the picture itself (in a browser: right-click › Copy image)."))
            Clipped.Empty -> say(theme.muted("The clipboard holds nothing to paste."))
        }
    }

    /** `/version`: what is running, against what, and with which clipboard - the first things to know when something does not work. */
    private fun version() {
        say(theme.bold(theme.accent("Orknux Shell")) + theme.text(" ${Version.VERSION}"))
        say(theme.muted("  server     ") + api.client.server)
        say(theme.muted("  clipboard  ") + clipboard.fold({ it.name }, { theme.warning(it.message ?: "none") }))
        say(theme.muted("  terminal   ") + terminal.type + theme.muted(" · ${System.getProperty("os.name")}") + (if (Clipboards.isWsl()) theme.muted(" (WSL)") else ""))
    }

    private fun pastePicture(r: LineReader, file: Path) {
        val bytes = try { Files.readAllBytes(file) } catch (failed: java.io.IOException) { say(theme.danger("${file.fileName} could not be read: ${failed.message}")); return }
        val type = Pictures.sniff(bytes) ?: Pictures.typeOf(file.fileName.toString()) ?: "application/octet-stream"
        // The chip shows the path it came from, not just the name the upload keeps.
        pasteBytes(r, Picture(bytes, type), file.fileName.toString(), label = file.toString())
    }

    /** Uploads a pasted picture at once - as `/attach` uploads a file - and puts its chip in the line. */
    private fun pasteBytes(r: LineReader, picture: Picture, filename: String? = null, label: String? = null) {
        val name = filename ?: "pasted-image-${java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.${picture.extension}"
        val kept = try {
            api.uploadPicture(conversation.workspace.id, name, picture.contentType, picture.bytes)
        } catch (refused: OrknuxException) {
            say(theme.danger("The picture was not attached: ${refused.message}"))
            return
        }
        attachments += kept
        val chip = imageChips.add(kept, label ?: kept.filename, size(kept.sizeBytes))
        val buffer = r.buffer.toString()
        val at = r.buffer.cursor()
        // A chip is a word of its own.
        val space = if (at > 0 && !buffer[at - 1].isWhitespace()) " " else ""
        r.buffer.write("$space$chip ")
        footer()
    }

    /** `/copy-image [n]`: a picture an answer showed, put on the clipboard as an image - the last one unless told. */
    private fun copyImage(argument: String?) {
        if (pictures.isEmpty()) throw OrknuxException("No answer here has shown a picture yet.")
        if (argument == "list") {
            pictures.forEachIndexed { i, p -> say("  " + theme.accent("${i + 1}.") + " " + p.label + theme.muted("  " + p.url)) }
            return
        }
        val n = argument?.let { it.toIntOrNull()?.takeIf { n -> n in 1..pictures.size } ?: throw OrknuxException("There is no picture $it: there are ${pictures.size}. /copy-image list names them.") } ?: pictures.size
        val chosen = pictures[n - 1]
        val board = clipboard.getOrThrow()
        val picture = api.picture(chosen.url)
        board.write(picture)
        val size = picture.size?.let { (w, h) -> " $w×$h" } ?: ""
        say(theme.success("✓ ") + theme.muted("Picture $n (") + theme.text(chosen.label) + theme.muted("$size ${picture.extension}) is on the clipboard, through ${board.name}."))
    }

    /** The pictures a turn showed, numbered on from those before, and said so they can be copied. */
    private fun notePictures(renderer: TurnRenderer) {
        val fresh = renderer.pictures.filter { p -> pictures.none { it.url == p.url } }
        if (fresh.isEmpty()) return
        pictures += fresh
        val first = pictures.size - fresh.size + 1
        val which = if (fresh.size == 1) "picture $first" else "pictures $first-${pictures.size}"
        say(theme.muted("$which · /copy-image${if (fresh.size == 1 && first == pictures.size) "" else " <n>"} copies ${if (fresh.size == 1) "it" else "one"} to the clipboard"))
    }

    /** Which of the listed commands is highlighted. */
    @Volatile private var slashAt = 0

    /** The line the list was closed on with Esc: it stays closed until the line changes. */
    @Volatile private var slashDismissed: String? = null

    /** True while the list is drawn as one types, rather than on Tab. */
    /** The line the list is being worked out for: a / alone lists only the Shell's own commands. */
    @Volatile private var listingTyped: String? = null

    /**
     * What the list under a / shows now: while the line is one word starting
     * with /, the cursor at its end, and the list not closed with Esc. A / alone
     * lists the Shell's own commands; a letter after it narrows to commands and
     * skills alike.
     */
    private fun slashChoices(r: LineReader): List<Completion> {
        val typed = r.buffer.toString()
        if (r.buffer.cursor() != typed.length || typed == slashDismissed) return emptyList()
        return slashChoices(typed)
    }

    /** The same, for any line: the prompt's, or the one typed ahead while a turn runs. */
    private fun slashChoices(typed: String): List<Completion> {
        if (!Regex("""/[^\s/]*""").matches(typed)) return emptyList()
        listingTyped = typed
        return try {
            completions.complete(typed)
        } finally {
            listingTyped = null
        }
    }

    /** The list's rows: a window of [SLASH_ROWS] around the highlight, the highlighted one marked. */
    private fun slashRows(): List<String> {
        if (!::reader.isInitialized) return emptyList()
        val shown = slashChoices(reader)
        if (shown.isEmpty()) return emptyList()
        slashAt = slashAt.coerceIn(0, shown.lastIndex)
        return slashRows(shown, slashAt, "Enter runs")
    }

    /** [shown] as the list draws it, a window of [SLASH_ROWS] around [slashAt]; [enter] says what Enter does. */
    private fun slashRows(shown: List<Completion>, slashAt: Int, enter: String): List<String> {
        val top = (slashAt - SLASH_ROWS / 2).coerceIn(0, (shown.size - SLASH_ROWS).coerceAtLeast(0))
        val wide = shown.subList(top, minOf(shown.size, top + SLASH_ROWS)).maxOf { it.value.trimEnd().length } + 2
        return shown.subList(top, minOf(shown.size, top + SLASH_ROWS)).mapIndexed { i, c ->
            val at = top + i == slashAt
            val name = c.value.trimEnd().padEnd(wide)
            (if (at) theme.accent("› ") + theme.bold(theme.accent(name)) else "  " + theme.text(name)) + theme.muted(c.description ?: "")
        } + listOfNotNull(if (shown.size > SLASH_ROWS) theme.muted("  ${slashAt + 1} of ${shown.size} · ↑↓ · Tab completes · $enter · Esc closes") else theme.muted("  ↑↓ · Tab completes · $enter · Esc closes"))
    }

    private val completions: Completions by lazy {
        Completions(object : CompletionSources {
            override fun agents() = agents.map { it.name }
            override fun workspaces() = workspacesCached().map { it.name }
            override fun skills() = if (listingTyped == "/") emptyList() else skillsCached().map { it.key }
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

    /** The shortcuts set with /config bound at the prompt; the ones they replace are given back to the line editor. */
    private var bound = emptyMap<String, Int>()
    private var defaultMain: Map<String, org.jline.reader.Binding>? = null

    private fun bindShortcuts(r: LineReader) {
        val keys = r.keyMaps[LineReader.MAIN] ?: return
        // What the line editor had on each key before the Shell took any, so a key given up goes back to it.
        if (defaultMain == null) defaultMain = HashMap(keys.boundKeys)
        bound.values.forEach { code ->
            val old = code.toChar().toString()
            defaultMain?.get(old)?.let { keys.bind(it, old) } ?: keys.unbind(old)
        }
        val now = mapOf("details" to keyOf("details"), "sessions" to keyOf("sessions"))
        keys.bind(Reference("orknux-thinking"), now.getValue("details").toChar().toString())
        keys.bind(Reference("orknux-sessions"), now.getValue("sessions").toChar().toString())
        bound = now
    }

    // ---- the loop ----------------------------------------------------------------------------

    private fun loop() {
        while (true) {
            drainApprovals()
            countSessions()
            footer()
            alignToFoot()
            // A working subagent on the screen with no turn of this chat's going: followed as a turn is, until it
            // finishes or the screen goes back - the live block does not belong to this chat's turn alone.
            if (viewing != Transcript.MAIN && viewed?.working == true) {
                followAtPrompt()
                continue
            }
            val line = try {
                phase = Phase.READING
                if (approvals.isNotEmpty()) continue
                val buffer = prefill.also { prefill = null }
                reader.readLine(framedPrompt(), null, null as MaskingCallback?, buffer)
            } catch (interrupted: UserInterruptException) {
                if (wakeForApproval) {
                    wakeForApproval = false
                    prefill = interrupted.partialLine?.takeIf { it.isNotEmpty() }
                    continue
                }
                if (sessionsAsked) {
                    sessionsAsked = false
                    // No line is being read while the list is up: a redraw meanwhile must not write a prompt of its
                    // own, which left the line editor a half-written row it marked with a "~".
                    phase = Phase.OTHER
                    prefill = interrupted.partialLine?.takeIf { it.isNotEmpty() }
                    // The prompt's row, left behind by the interruption, gives way to the list; the line comes back after.
                    if (region.ansi) {
                        terminal.writer().print("\u001b[1A\u001b[2K")
                        terminal.writer().flush()
                    }
                    sessionsByKey()
                    continue
                }
                if (interrupted.partialLine.isNullOrEmpty()) say(theme.muted("(Ctrl+D or /quit to leave)"))
                continue
            } catch (_: EndOfFileException) {
                return
            } finally {
                phase = Phase.OTHER
            }
            // The chips of pasted pictures are not words: taken out, and those edited away detached.
            val taken = imageChips.take(line)
            taken.dropped.forEach { attachments.remove(it) }
            if (taken.dropped.isNotEmpty()) footer()
            val text = taken.text.replace("\\\n", "\n").trim()
            // A message goes to this chat's main session, so it is shown there: back to it first, where another is followed.
            if (text.isNotEmpty() && viewing != Transcript.MAIN && SlashCommands.parse(text) == null) {
                // On a subagent's screen: into its turn where one is going, and nowhere otherwise - never to another agent.
                val followed = viewed
                val left = followed != null && runCatching { api.postDuringSession(followed.sessionId, text) }.getOrDefault(false)
                if (left) {
                    userLines(text).forEach { say(it) }
                    say(theme.muted("  sent now - ${followed!!.agent} reads it at its next step"))
                    say("")
                } else {
                    if (region.ansi) {
                        terminal.writer().print("\u001b[1A\u001b[2K")
                        pullDown(1)
                    }
                    say(theme.warning("Not sent: ") + theme.muted("${followed?.agent ?: "this agent"} has finished, so nothing there would read it. ${keyName("sessions")}, Enter goes back to this chat to write to ${conversation.agent?.name ?: "the main agent"}."))
                }
                continue
            } else if (text.isNotEmpty()) showSent(line, if (taken.pictures.isEmpty()) text else line.trim())
            // The line the person entered is on the screen now, written by the line editor rather than through say():
            // what follows is after text, so the blank line it asks for is kept.
            if (text.isNotEmpty()) lastBlank = false
            if (text.isEmpty() && taken.pictures.isNotEmpty()) {
                // Pictures and no words: they are the message.
                showSent(line, line.trim())
                lastBlank = false
                try { turn("") } catch (refused: OrknuxException) { say(theme.danger(refused.message ?: "Refused.")); say("") }
                continue
            }
            if (text.isEmpty()) {
                // An empty Enter leaves no bare prompt behind: its row and the rule above it are cleared, and the
                // screen comes back down by both.
                if (region.ansi) {
                    terminal.writer().print("\u001b[1A\u001b[2K".repeat(2))
                    pullDown(2)
                }
                continue
            }
            try {
                if (!said(text)) return
                // What was written while that turn ran, sent now, each as if typed at the prompt.
                while (queued.isNotEmpty()) {
                    val next = queued.removeAt(0)
                    // It goes to this chat's main session and starts a turn there: shown there, not in a session followed.
                    if (viewing != Transcript.MAIN) showMain()
                    userLines(next).forEach(::say)
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
            // A blank line between the command typed and what it answers, as between a message and its answer.
            say("")
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
            say("")
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

    /** A message the person wrote, as the conversation shows it: the prompt's mark, and on its own background. */
    private fun userLines(text: String): List<String> =
        text.split('\n').mapIndexed { i, l -> theme.message((if (i == 0) prompt() else "  ") + theme.text(l)) }

    /**
     * The line just entered, written again on the message background: the line
     * editor left it as it was typed, on the rows above the cursor - [typed]
     * as it was, to count them - and they are cleared and written over.
     */
    private fun showSent(typed: String, text: String) {
        if (!region.ansi || theme.depth == ColourDepth.NONE) {
            text.split('\n').forEachIndexed { i, l -> transcript.add((if (i == 0) prompt() else "  ") + l) }
            return
        }
        val w = width()
        // The framed prompt draws a rule on the row above the input, so one extra row is cleared with it and left
        // out of the echo - the rule frames what is being written, not what was sent.
        val rows = 1 + typed.split('\n').sumOf { ((visibleLength(it) + 2 - 1) / w + 1).coerceAtLeast(1) }
        terminal.writer().print("\u001b[${rows}A" + "\u001b[2K\u001b[1B".repeat(rows) + "\u001b[${rows}A\r")
        terminal.writer().flush()
        lastBlank = false
        userLines(text).forEach(::say)
    }

    private fun ask(prompt: String): String = try {
        reader.readLine(prompt).trim().also { lastBlank = false }
    } catch (_: UserInterruptException) {
        ""
    }

    // ---- a turn ------------------------------------------------------------------------------

    /**
     * Sends [text] in [into] - the main conversation unless an `@agent` message chose another - and draws the answer.
     * A spoken turn says so to the server ([voice]), and [heard] is handed every event as well, for reading it aloud.
     */
    private fun turn(text: String, into: Conversation = conversation, voice: Boolean = false, heard: ((ChatEvent) -> Unit)? = null) {
        val output = object : TurnOutput {
            override fun line(text: String) = say(text, owner = Transcript.MAIN)
            override fun lines(folded: List<String>, full: List<String>) = sayEither(folded, full)
            override fun phase(key: String, text: String) = region.phase(key, text)
            override fun thinking(text: String?) {
                if (text != null && liveThinking == null) thinkingSince = System.currentTimeMillis()
                liveThinking = text
                // Kept for Ctrl+O, but drawn live only when asked for: by default the spinner says "Thinking"
                // and the text is read afterwards, from the "Thought for" line, as in Claude Code.
                region.thinkingHint = detailsHint()
                region.thinking(if (unfolded) text else null)
            }
            override fun width(): Int = this@Interactive.width()
        }
        val renderer = TurnRenderer(theme, output, if (config.thinking == "hidden") "hidden" else "collapsed", links)
        val sending = if (into === conversation) attachments.toList() else emptyList()
        // A blank line between what was asked and what comes back.
        say("")
        active = into
        lastTurn = renderer
        phase = Phase.TURN
        followLive()
        // The status bar stays where it is, at the foot of the screen, and is kept up to date by the ticker.
        val saved = terminal.enterRawMode()
        val raw = terminal.attributes
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        terminal.attributes = raw
        region.thinkingHint = detailsHint()
        region.phase("model", "Waiting for the model")
        region.start { below() }
        // Ten frames a second, and only while a turn runs: nothing ticks when nothing is happening.
        val ticker = Thread.ofPlatform().daemon().name("orknux-shell-ticker").start {
            var n = 0
            while (phase == Phase.TURN) {
                tickTurn()
                if (++n % 10 == 0) footer()
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        val keys = Thread.ofPlatform().daemon().name("orknux-shell-keys").start { keysDuringTurn() }
        val chatBefore = into.chat?.id
        try {
            into.send(text, sending.map { it.id }, voice) { event ->
                renderer.handle(event)
                // The reading is the turn's passenger: anything it trips over is its own, never the answer's.
                heard?.let { runCatching { it(event) }.onFailure { failure -> Trace.log("voice: $failure") } }
            }
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
        notePictures(renderer)
        if (chatBefore == null && api.startedWithoutOrigin) {
            say(theme.warning("This server does not know chats opened in Orknux Shell yet, so its agent cannot run commands here."))
        }
        if (!region.ansi) say(theme.muted(statusText()))
        // A blank line between an answer and the next prompt.
        say("")
        if (into === conversation) checkGoal()
        answerQuestions(into)
    }

    // ---- voice ---------------------------------------------------------------------------------

    /** The answer being read aloud in voice mode, which an interruption stops with the turn. */
    @Volatile private var spoken: Reading? = null

    /** What was heard and transcribed in voice mode, waiting to be the next turn. */
    private val heardQueue = LinkedBlockingQueue<String>()
    private val transcribing = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Voice mode: the web interface's Voice panel in a terminal. It listens,
     * sends what it heard as a spoken turn when the person stops talking, and
     * reads the answer aloud as it is written - and goes on listening the whole
     * time, so what is said while the agent works is the next turn rather than
     * lost. Space ends a turn early or cuts the reading short, M mutes, Esc
     * stops what is happening - the answer, or, while listening, voice mode.
     *
     * The workspace chooses the models, as it does for the browser; one with no
     * transcription model has nothing to hear with and says so, and one with no
     * speech model is answered on the screen alone.
     */
    private fun voice(argument: String?) {
        val headphones = when (argument?.lowercase()) {
            null -> false
            "headphones", "headset" -> true
            else -> throw OrknuxException("/voice, or /voice headphones where the microphone cannot hear the speakers.")
        }
        val workspace = conversation.workspace
        val settings = api.voiceSettings(workspace.id)
        if (settings.transcriptionModelId == null) {
            say(theme.warning("This workspace has no transcription model, so voice mode has nothing to hear you with.") +
                theme.muted(" An administrator of ${workspace.name} chooses one in the workspace's settings, as for the microphone in the browser."))
            return
        }
        val microphone = Sound.microphone().getOrElse {
            say(theme.danger("No microphone: ${it.message}.") + theme.muted(" Voice mode needs one this program can open - connected, allowed, and not held by another program."))
            return
        }
        var speaker: Speaker? = null
        when {
            settings.speechModelId == null ->
                say(theme.warning("This workspace has no speech model: ") + theme.muted("answers are shown here and not read aloud. An administrator chooses one in the workspace's settings."))
            else -> Sound.speakers()?.let { why ->
                say(theme.warning("No audio output ($why): ") + theme.muted("answers are shown here and not read aloud."))
            } ?: run { speaker = Speaker() }
        }
        val listener = Listener(
            read = { microphone.read() },
            settings = { settings },
            headphones = headphones,
            speaking = { spoken?.audible == true },
            thinking = { phase == Phase.TURN && spoken?.audible != true },
            onUtterance = { wav -> transcribe(workspace.id, wav) },
            onBargeIn = {
                // Talked over: what is being read stops, and a turn still being written is stopped on the server too.
                spoken?.stop()
                if (active != null) interruptTurn()
            },
            flush = { microphone.flush() },
        )
        heardQueue.clear()
        val ears = Thread.ofPlatform().daemon().name("orknux-voice-listen").start {
            runCatching { listener.run() }.onFailure { say(theme.danger("The microphone stopped: ${it.message ?: it.javaClass.simpleName}")) }
        }
        say(theme.bold(theme.accent("Voice mode")) + theme.muted(" · speak, and it answers when you stop" + if (headphones) " · talk over the answer to stop it" else ""))
        say(theme.muted("Space: done speaking, or cut the answer short · M: mute · Esc: stop the answer, or leave voice mode"))
        if (!headphones && speaker != null) say(theme.muted("With speakers the microphone does not listen while the answer is read; /voice headphones listens throughout."))
        say("")
        try {
            while (true) {
                val text = listen(listener) ?: break
                say("")
                userLines(text).forEach(::say)
                say(theme.muted("  spoken"))
                lastBlank = false
                val words = StringBuilder()
                val held = speaker
                val now = held?.let { s ->
                    Reading(
                        chunking = settings.chunking,
                        speak = { piece -> api.speak(workspace.id, piece) },
                        fetch = { url -> api.sound(url) },
                        play = { pcm -> s.play(pcm) },
                        stopPlaying = { s.stop() },
                        onFailure = { why -> say(theme.warning("Not read aloud: ") + theme.muted(why)) },
                    )
                }
                spoken = now
                // What is heard while the agent works waits under the spinner, as typing does, and Ctrl+S sends it now.
                val relay = Thread.ofVirtual().start {
                    try {
                        while (true) queued += heardQueue.take()
                    } catch (_: InterruptedException) {
                    }
                }
                try {
                    turn(text, voice = true) { event ->
                        if (now == null) return@turn
                        when (event) {
                            is ChatEvent.Chunk -> {
                                words.append(event.text)
                                now.push(words.toString(), false)
                            }
                            is ChatEvent.Said -> now.say(event.text)
                            is ChatEvent.Play -> now.sound(event.url)
                            is ChatEvent.Answered -> now.push(words.toString(), true)
                            else -> {}
                        }
                    }
                } finally {
                    relay.interrupt()
                    relay.join(500)
                }
                now?.push(words.toString(), true)
                if (now != null && !now.stopped) listenWhileSpeaking(now, listener)
                spoken = null
                // Said while the agent worked: the next turn, all of it as one, as the browser panel joins them.
                if (queued.isNotEmpty()) {
                    heardQueue.put(queued.joinToString(" "))
                    queued.clear()
                }
            }
        } finally {
            spoken?.stop()
            spoken = null
            microphone.close()
            ears.join(1_000)
            speaker?.close()
            queued.clear()
            heardQueue.clear()
            say(theme.muted("Voice mode is off."))
        }
    }

    /** Sends a recording to be transcribed, without holding the microphone up: what it says arrives in [heardQueue]. */
    private fun transcribe(workspaceId: String, wav: ByteArray) {
        transcribing.incrementAndGet()
        Thread.ofVirtual().name("orknux-voice-transcribe").start {
            try {
                val words = api.transcribe(workspaceId, wav).trim()
                if (words.isNotEmpty()) heardQueue.put(words)
            } catch (failure: OrknuxException) {
                say(theme.warning("Not heard: ") + theme.muted(failure.message ?: "that could not be transcribed."))
            } finally {
                transcribing.decrementAndGet()
            }
        }
    }

    /**
     * Listening, with the keyboard in raw mode and the live line saying so:
     * returns what was heard next, or null where the person left voice mode.
     */
    private fun listen(listener: Listener): String? {
        heardQueue.poll()?.let { return it }
        var left = false
        var heard: String? = null
        voiceKeys(
            line = {
                val meter = meter(listener.detector.level)
                when {
                    transcribing.get() > 0 -> "Transcribing what you said"
                    listener.muted -> theme.warning("Muted") + theme.muted(" · M to speak again")
                    else -> "Listening " + meter
                }
            },
            hint = "Space: done speaking · M: mute · Esc: leave voice mode",
            until = { heardQueue.poll()?.let { heard = it; true } ?: false },
        ) { key ->
            when (key) {
                ' '.code -> listener.finishNow()
                'm'.code, 'M'.code -> listener.muted = !listener.muted
                27, 3, 4, 'q'.code -> { left = true; return@voiceKeys true }
            }
            false
        }
        return if (left) null else heard
    }

    /** The answer still being read after the turn has ended: Space or Esc cuts in, and either way it is back to listening. */
    private fun listenWhileSpeaking(now: Reading, listener: Listener) {
        voiceKeys(
            line = { if (now.audible) "Speaking" else "Preparing speech" },
            hint = "Space or Esc: stop reading and listen" + if (listener.muted) " · muted" else "",
            until = { now.finished.isDone },
        ) { key ->
            if (key == ' '.code || key == 27 || key == 3 || key == 13) {
                now.stop()
                return@voiceKeys true
            }
            if (key == 'm'.code || key == 'M'.code) listener.muted = !listener.muted
            false
        }
    }

    /**
     * The live line of voice mode and the keys under it, until [until] holds or
     * [key] says to stop - the same raw keyboard and live block a turn uses.
     */
    private fun voiceKeys(line: () -> String, hint: String, until: () -> Boolean, key: (Int) -> Boolean) {
        val saved = terminal.enterRawMode()
        val raw = terminal.attributes
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        terminal.attributes = raw
        region.thinkingHint = null
        region.phase("voice", line())
        region.start { listOf("", theme.muted(hint)) + if (status != null) emptyList() else listOf(rule(), " " + statusText(), "", " " + shortcuts()) }
        val input = terminal.reader()
        try {
            var n = 0
            while (!until()) {
                val read = input.read(50)
                if (read >= 0) {
                    // An escape sequence - an arrow - is not Esc.
                    if (read == 27 && input.peek(30) != NonBlockingReader.READ_EXPIRED) { drainSequence(input); continue }
                    if (key(read)) return
                }
                if (++n % 2 == 0) {
                    region.phase("voice", line())
                    region.tick()
                }
            }
        } finally {
            region.stop()
            terminal.attributes = saved
        }
    }

    /** How loud the room is, as a bar of eight. */
    private fun meter(level: Double): String {
        val filled = (level * 8).toInt().coerceIn(0, 8)
        return theme.accent("▮".repeat(filled)) + theme.muted("▯".repeat(8 - filled))
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
        // A blank line between what came before - a heading, the command typed - and the list.
        say("")
        val picker = Picker(
            sink = { text -> terminal.writer().print(text); terminal.writer().flush() },
            key = { timeout -> terminal.reader().read(if (timeout < 0) 0L else timeout) },
            theme = theme,
            rows = { terminal.height.takeIf { it > 0 } ?: 24 },
            width = ::width,
        )
        val start = listStart()
        val saved = terminal.enterRawMode()
        val chosen = try {
            synchronized(out) { picker.chooseMany(what, rows) }
        } finally {
            terminal.attributes = saved
        }
        giveBack(picker, start)
        return chosen
    }

    /**
     * The turn an answer woke, drawn as it is written: the server runs it by
     * itself, and the Shell reads it the way it reads a goal's iterations.
     * Ctrl+C or Esc asks the server to stop it.
     */
    private fun followWake(chatId: String, into: Conversation) {
        val output = object : TurnOutput {
            override fun line(text: String) = say(text, owner = Transcript.MAIN)
            override fun lines(folded: List<String>, full: List<String>) = sayEither(folded, full)
            override fun phase(key: String, text: String) = region.phase(key, text)
            override fun thinking(text: String?) {
                if (text != null && liveThinking == null) thinkingSince = System.currentTimeMillis()
                liveThinking = text
                region.thinkingHint = detailsHint()
                region.thinking(if (unfolded) text else null)
            }
            override fun width(): Int = this@Interactive.width()
        }
        val renderer = TurnRenderer(theme, output, if (config.thinking == "hidden") "hidden" else "collapsed", links)
        // The same keys as a turn sent from here - Ctrl+C or Esc interrupts, Ctrl+O shows the thinking, typing queues -
        // and Ctrl+C a key rather than a signal: as a signal it reached a handler that knew only turns sent from here.
        active = into
        lastTurn = renderer
        phase = Phase.TURN
        followLive()
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
                tickTurn()
                if (++n % 10 == 0) footer()
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        val keys = Thread.ofPlatform().daemon().name("orknux-shell-keys").start { keysDuringTurn() }
        try {
            api.client.stream("/api/chats/$chatId/follow?wait=true", null, opened = { into.following(it) }) { frame -> ChatEvent.of(frame).let { into.heard(it); renderer.handle(it) } }
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

    /** A full-width rule in the brand's own colour, the line that frames the input above and below. */
    private fun rule(): String =
        theme.accent((if (LiveRegion.unicodeFor(terminal.encoding()?.name())) "─" else "-").repeat((width() - 1).coerceAtLeast(1)))

    /**
     * The editing prompt with a rule above it, so the line being written sits
     * between two rules of its own - this one and the status bar's. A two-line
     * prompt: the rule, then `› `, the cursor after it. The rule is redrawn with
     * the prompt, so it stays put through a clear, a resize or the list closing.
     */
    private fun framedPrompt(): String = rule() + "\n" + prompt()

    /** Under the prompt while a line is read: the list of commands under a /, where it is open. */
    private fun underPrompt(): List<String> =
        if (!::conversation.isInitialized || !region.ansi) emptyList()
        else slashRows().map { LiveRegion.clip(it, width() - 1) }

    /**
     * The prompt back on the row directly above the status bar, wherever
     * something left it - a long list, a resize, a split pane: the terminal is
     * asked where the cursor is, and the text is scrolled down by the rows
     * between. A terminal that does not answer within a moment is left as it is.
     */
    private fun alignToFoot() {
        if (!region.ansi || status == null) return
        val rows = terminal.height.takeIf { it > 0 } ?: return
        val row = cursorRow() ?: return
        val gap = (rows - barRows()) - row
        if (gap > 0) pullDown(gap)
    }

    /**
     * The row the cursor is on, as the terminal answers it (1-based), or null
     * where it does not answer within a moment.
     */
    private fun cursorRow(): Int? {
        val saved = terminal.enterRawMode()
        return try {
            terminal.writer().print("\u001b[6n")
            terminal.writer().flush()
            val input = terminal.reader()
            val answer = StringBuilder()
            val until = System.currentTimeMillis() + CURSOR_ANSWER_MILLIS
            while (System.currentTimeMillis() < until) {
                val c = input.read(25)
                if (c < 0) continue
                answer.append(c.toChar())
                if (c == 'R'.code) break
            }
            Regex("\u001b\\[(\\d+);(\\d+)R").find(answer)?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        } finally {
            terminal.attributes = saved
        }
    }

    /**
     * What a closed list pushed up comes back - only that. The list is drawn
     * at the cursor: at the foot it scrolled the screen up by all but its first
     * row, but the workspace list at the start is drawn just under the banner
     * on a clean screen and scrolled nothing, so giving back its height there
     * scrolled the banner down and left blank rows above it for the rest of
     * the conversation. [startRow] is where the list began; unknown, it is
     * taken to have been at the foot, which is where every other list opens.
     */
    private fun giveBack(picker: Picker, startRow: Int?) {
        val rows = terminal.height.takeIf { it > 0 }
        val pushed = if (startRow == null || rows == null) picker.lastHeight - 1
        else Picker.scrolledBy(startRow, picker.lastHeight, rows - barRows())
        pullDown(pushed)
    }

    /** Where a list is about to be drawn: asked of the terminal only where the screen is drawn on. */
    private fun listStart(): Int? = if (region.ansi) cursorRow() else null

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
            val following = viewing != Transcript.MAIN
            queued.forEachIndexed { i, text ->
                val label = if (following) "  queued for ${conversation.agent?.name ?: "main"} (main) · " else "  queued · "
                add(theme.muted(label) + theme.text(text) + (if (!following && i == queued.lastIndex && ahead.isEmpty()) theme.muted(" · ${keyName("send-now")} sends now") else ""))
            }
            if (queued.isNotEmpty() && region.active) add("")
            if (region.active) add(prompt() + ahead.toString() + theme.muted(if (ahead.isEmpty()) typingHint() else "▏"))
            if (region.active && aheadChoices.isNotEmpty()) {
                val at = aheadAt.coerceIn(0, aheadChoices.lastIndex)
                val runs = SlashCommands.parse(aheadChoices[at].value.trim())?.name in WHILE_WORKING
                addAll(slashRows(aheadChoices, at, if (runs) "Enter runs now" else "Enter queues it for after this turn"))
            }
        }
        // A blank line between what is happening and what is being written, and one under it, so the turn's last
        // line is not pressed against the status bar. Drawn here, in the turn, not as a row of the bar: a bar row
        // stood between the idle prompt and the rule as well, where nothing should.
        if (status != null) return listOf("") + typing + ""
        return listOf("") + typing + listOf(rule(), " " + statusText(), "", " " + shortcuts())
    }

    /** What the empty typing line says: where a message goes - this chat's main session, said while another is on the screen - and how to send it now. */
    private fun typingHint(): String {
        val followed = viewed?.takeIf { viewing != Transcript.MAIN }
        if (followed != null) {
            return if (followed.working) "type to send a message to ${followed.agent} now - Enter or ${keyName("send-now")}; it reads it at its next step"
            else "${followed.agent} has finished; ${keyName("sessions")}, Enter goes back to this chat to write to ${conversation.agent?.name ?: "the main agent"}"
        }
        return "type to queue a message for when this turn ends" + (if (queued.isEmpty()) " · ${keyName("send-now")} sends it now" else "")
    }

    /** Ctrl+C or Esc during a turn: the spinner stops at once, and the server is asked to stop. */
    private fun interruptTurn() {
        // In voice mode the answer being read stops with the answer being written.
        spoken?.stop()
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
                // Following from the prompt ends with the subagent, or with the screen back on this chat.
                if (promptFollow && (viewing == Transcript.MAIN || viewed?.working != true)) break
                approvals.poll()?.let { ask(it); continue }
                val read = input.read(50)
                if (read != NonBlockingReader.READ_EXPIRED) Trace.log("key $read")
                // The configurable ones first: a constant cannot be a branch of a `when` that /config changes.
                val c = when (read) {
                    keyOf("details") -> DETAILS
                    keyOf("sessions") -> SESSIONS
                    keyOf("send-now") -> SEND_NOW
                    else -> read
                }
                when (c) {
                    // Following from the prompt there is nothing of this chat's to interrupt: Ctrl+C and Esc go back.
                    3 -> if (promptFollow) showMain() else interruptTurn()
                    DETAILS -> toggleDetails()
                    SEND_NOW -> sendNow()
                    SESSIONS -> {
                        region.pause()
                        try {
                            sessionsByKey()
                        } finally {
                            region.resume()
                        }
                    }
                    // Esc closes the list of commands when it is open, and interrupts the turn when it is not.
                    27 -> if (input.peek(30) == NonBlockingReader.READ_EXPIRED) {
                        if (aheadChoices.isNotEmpty()) { aheadDismissed = ahead.toString(); aheadTyped() } else if (promptFollow) showMain() else interruptTurn()
                    } else when (sequence(input)) {
                        "[A", "OA" -> if (aheadChoices.isNotEmpty()) { aheadAt = (aheadAt - 1).coerceAtLeast(0); region.redraw() }
                        "[B", "OB" -> if (aheadChoices.isNotEmpty()) { aheadAt = (aheadAt + 1).coerceAtMost(aheadChoices.lastIndex); region.redraw() }
                    }
                    // Tab takes the command highlighted, to go on typing after it.
                    9 -> aheadChoices.getOrNull(aheadAt)?.let { chosen ->
                        ahead.setLength(0)
                        ahead.append(chosen.value.trimEnd()).append(' ')
                        aheadTyped()
                    }
                    // Typed while the agent works: kept under the spinner, and Enter queues it for when the turn ends -
                    // the command highlighted, where the list is open.
                    13, 10 -> {
                        aheadChoices.getOrNull(aheadAt)?.let { chosen -> ahead.setLength(0); ahead.append(chosen.value.trimEnd()) }
                        val typed = ahead.toString().trim()
                        // On a subagent's screen what is written goes to that subagent, now: there is no "after its turn"
                        // for the Shell to queue it for, and a message meant for the agent on the screen reaching another
                        // is the worst thing a queue can do.
                        if (typed.isNotEmpty() && viewing != Transcript.MAIN && SlashCommands.parse(typed) == null) {
                            sendNow()
                            continue
                        }
                        val now = SlashCommands.parse(typed)?.takeIf { it.name in WHILE_WORKING }
                        if (now != null) {
                            ahead.setLength(0)
                            aheadTyped()
                            runWhileWorking(typed, now)
                            continue
                        }
                        if (typed.isNotEmpty()) queued += typed
                        ahead.setLength(0)
                        aheadTyped()
                    }
                    127, 8 -> {
                        if (ahead.isNotEmpty()) ahead.setLength(ahead.length - 1)
                        aheadTyped()
                    }
                    21 -> { ahead.setLength(0); aheadTyped() }
                    else -> if (c >= 32) {
                        ahead.append(c.toChar())
                        aheadTyped()
                    }
                }
            }
        } catch (_: java.io.InterruptedIOException) {
        } catch (_: InterruptedException) {
        } catch (failure: Exception) {
            Trace.log("key read failed: $failure")
        }
    }

    /**
     * A command that can run while the agent works - one that neither starts a
     * turn, nor changes the conversation under it, nor needs the line editor -
     * run at once, the live block set aside while it writes; the rest are queued.
     */
    private fun runWhileWorking(typed: String, slash: Slash) {
        region.pause()
        try {
            say("")
            userLines(typed).forEach(::say)
            say("")
            try {
                command(slash)
            } catch (refused: OrknuxException) {
                say(theme.warning(refused.message ?: "Could not do that."))
            }
            say("")
        } finally {
            region.resume()
        }
    }

    /**
     * The send-now key (Ctrl+S) while the agent works: the line typed - or, with nothing
     * typed, every message queued - left for the running turn, which reads it
     * at its next step as the person speaking, rather than after the turn ends.
     * A chat whose answerer has no steps to read it between - My Claude Code, a
     * bare model - is told so, and the message stays queued.
     */
    private fun sendNow() {
        val typed = ahead.toString().trim()
        // Watching a subagent work, what is typed goes into its turn: that is the agent on the screen, and the one
        // being steered. What is queued is this chat's, and goes to this chat's main session as it always does.
        val followed = viewed
        // On a subagent's screen with nothing typed there is nothing to send: the queue is this chat's, not its.
        if (typed.isEmpty() && viewing != Transcript.MAIN) return
        if (typed.isNotEmpty() && viewing != Transcript.MAIN && followed != null) {
            val left = try {
                api.postDuringSession(followed.sessionId, typed)
            } catch (refused: OrknuxException) {
                say(theme.warning("Not sent: ") + theme.muted(refused.message ?: "the server refused it."))
                return
            }
            if (!left) {
                say(theme.warning("Not sent: ") + theme.muted("${followed.agent} has finished, so nothing there would read it. ${keyName("sessions")}, Enter goes back to this chat."))
                return
            }
            ahead.setLength(0)
            aheadTyped()
            // Room either side, as around anything said: the turn's own lines leave none before what comes next.
            say("")
            userLines(typed).forEach { say(it) }
            say(theme.muted("  sent now - ${followed.agent} reads it at its next step"))
            say("")
            return
        }
        val sending = if (typed.isNotEmpty()) listOf(typed) else queued.toList()
        if (sending.isEmpty()) return
        val into = active ?: conversation
        val chat = into.chat ?: return
        try {
            sending.forEach { api.postDuringTurn(chat.id, it) }
        } catch (refused: OrknuxException) {
            if (typed.isNotEmpty()) queued += typed
            ahead.setLength(0)
            aheadTyped()
            say(theme.warning("Not sent now: ") + theme.muted((refused.message ?: "this chat cannot be told anything mid-turn") + " It stays queued for when the turn ends."))
            return
        }
        if (typed.isNotEmpty()) ahead.setLength(0) else queued.clear()
        aheadTyped()
        sending.forEach { text ->
            into.sentNow()
            say("", owner = Transcript.MAIN)
            userLines(text).forEach { say(it, owner = Transcript.MAIN) }
            say(theme.muted("  sent now - the agent reads it at its next step"), owner = Transcript.MAIN)
        }
        // Room after it, as after anything said: the turn's first piece of work writes none of its own.
        say("", owner = Transcript.MAIN)
    }

    /**
     * Following a working subagent while no turn of this chat's is going: the
     * live block all the same - its phase line, the typing line, Enter into
     * its turn, Ctrl+O, Ctrl+T - on the keys a turn reads, until it finishes or
     * the screen goes back to this chat. Without this, a subagent followed
     * after this chat's turn had ended was lines appearing above a prompt, with
     * nothing to say what it was doing or where to write to it.
     */
    private fun followAtPrompt() {
        promptFollow = true
        phase = Phase.TURN
        val saved = terminal.enterRawMode()
        val raw = terminal.attributes
        raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
        terminal.attributes = raw
        region.thinkingHint = detailsHint()
        region.start { below() }
        val ticker = Thread.ofPlatform().daemon().start {
            var n = 0
            while (phase == Phase.TURN) {
                tickTurn()
                if (++n % 10 == 0) footer()
                try { Thread.sleep(100) } catch (_: InterruptedException) { break }
            }
        }
        try {
            keysDuringTurn()
        } finally {
            phase = Phase.OTHER
            promptFollow = false
            ticker.interrupt()
            region.stop()
            terminal.attributes = saved
            footer()
        }
    }

    /** Whether the live block is up for a followed subagent rather than a turn of this chat's. */
    @Volatile private var promptFollow = false

    private fun drainSequence(input: NonBlockingReader) {
        while (input.peek(5) >= 0) input.read(5)
    }

    /** The rest of an escape sequence - "[A" for Up - read whole, so nothing of it is taken as typing. */
    private fun sequence(input: NonBlockingReader): String {
        val sb = StringBuilder()
        while (input.peek(5) >= 0) sb.append(input.read(5).toChar())
        return sb.toString()
    }

    /**
     * The commands matching what is typed ahead, worked out when it changes
     * rather than at every frame - a frame is a tenth of a second - and the
     * line and the list drawn again.
     */
    private fun aheadTyped() {
        val typed = ahead.toString()
        if (typed != aheadDismissed) aheadDismissed = null
        val before = aheadChoices
        aheadChoices = if (aheadDismissed != null) emptyList() else runCatching { slashChoices(typed) }.getOrDefault(emptyList())
        if (aheadChoices.map { it.value } != before.map { it.value }) aheadAt = 0
        region.redraw()
    }

    @Volatile private var aheadChoices: List<Completion> = emptyList()
    @Volatile private var aheadAt = 0
    private var aheadDismissed: String? = null

    /** Ctrl+O and `/thinking`: the last turn's thinking in full, every stretch in order, and what is streaming now. */
    /** What the details key does now, said on the spinner's line - and how many messages sent into the turn wait to be read. */
    private fun detailsHint(): String {
        val waiting = (active ?: conversationOrNull())?.waitingToBeRead ?: 0
        return keyName("details") + (if (unfolded) " fold" else " expand") +
            (if (waiting > 0) " · $waiting waiting to be read at its next step" else "")
    }

    /** One frame of the live block: the hint is read again each time, since what it says changes under the turn. */
    private fun tickTurn() {
        region.thinkingHint = detailsHint()
        region.tick()
    }

    /** The byte [action]'s shortcut arrives as: the one set with /config, or its default. */
    private fun keyOf(action: String): Int = Keys.code(Keys.specFor(action, config.keys))!!

    /** [action]'s shortcut as a person reads it: Ctrl+O. */
    private fun keyName(action: String): String = Keys.label(Keys.specFor(action, config.keys))

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
    private fun say(text: String, view: View = View.BOTH, owner: String = viewing) {
        // A line is one row: text with newlines in it is its lines, each said, or the rows under it go uncounted.
        if ('\n' in text) {
            text.removeSuffix("\n").split('\n').forEach { say(it.removeSuffix("\r"), view, owner) }
            return
        }
        transcript.add(text, view, owner)
        // Kept for its own session, and written only while that one is on the screen.
        if (owner != viewing || !Transcript.shown(view, unfolded)) return
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

    /** How many of the transcript's first lines are the welcome, which a redraw keeps at the top. */
    private var welcomeLines = 0

    /** Every line said, for writing the screen again: on Ctrl+O, and when the window changes size. */
    private val transcript = Transcript()

    /** Whether thoughts and tool calls are shown in full rather than folded. Ctrl+O switches it, at any time. */
    @Volatile private var unfolded = config.thinking == "expanded"

    /** A piece of work, kept both ways; the screen shows the one [unfolded] chooses. */
    private fun sayEither(folded: List<String>, full: List<String>) {
        folded.forEach { say(it, View.FOLDED, Transcript.MAIN) }
        full.forEach { say(it, View.FULL, Transcript.MAIN) }
    }

    /**
     * Ctrl+O: thoughts and tool calls in full, or folded again - everything
     * already on the screen as well as what comes next, at the prompt or while
     * a turn runs. Written lines cannot be changed in place, so the screen is
     * written again from the transcript, as it is when the window is resized.
     */
    private fun toggleDetails() {
        unfolded = !unfolded
        if (::region.isInitialized) {
            region.thinkingHint = detailsHint()
            region.thinking(if (unfolded) liveThinking else null)
        }
        repaint()
    }

    /**
     * The window written again from the transcript: cleared, its scrollback
     * with it - or the old view would be left above the new - the lines in the
     * view chosen, at the window's size now, the status bar pinned again, and
     * the live block or the prompt under the last line, at the foot.
     */
    private fun repaint() {
        if (!::region.isInitialized || !region.ansi) return
        val rows = terminal.height.takeIf { it > 0 } ?: return
        val lines = transcript.lines(unfolded, viewing)
        val draw = {
            val w = terminal.writer()
            w.print(LiveRegion.SYNC_START + "\u001b[r\u001b[H\u001b[2J\u001b[3J")
            w.flush()
            runCatching {
                status?.resize()
                status?.reset()
            }
            // The bar first, on the empty screen: a reset bar makes room for itself by moving the cursor up as many
            // rows as it has, which after the text put the prompt that many rows into it.
            footer()
            w.print("\u001b[H")
            // The welcome at the top and the conversation at the foot, the gap between, where it all fits - as the
            // start lays it out. Written from the top and pulled down whole, the welcome came down with it.
            val room = rows - barRows()
            val below = if (region.active) region.lines().size.coerceAtLeast(1) else 1
            val wide = width()
            fun rowsOf(l: List<String>) = l.sumOf { ((visibleLength(it) - 1) / wide + 1).coerceAtLeast(1) }
            val head = if (viewing == Transcript.MAIN) lines.take(welcomeLines) else emptyList()
            val rest = lines.drop(head.size)
            val gap = room - rowsOf(lines) - below
            head.forEach { w.print(it + "\r\n") }
            if (gap > 0) w.print("\r\n".repeat(gap))
            rest.forEach { w.print(it + "\r\n") }
            region.forget()
            region.dropHeld()
            // So the live block puts its blank line under the last one written, as it does after any other line.
            region.afterLine(lines.lastOrNull().orEmpty())
            w.flush()
        }
        synchronized(out) { if (region.active) region.exclusive(draw) else draw() }
        if (region.active) region.redraw() else if (::reader.isInitialized && phase == Phase.READING) runCatching { (reader as? ShellLineReader)?.redrawPrompt() }
        terminal.writer().print(LiveRegion.SYNC_END)
        terminal.writer().flush()
    }

    private val startedAt = System.currentTimeMillis()

    /**
     * The status bar's height: its rule, its row, a blank, and the shortcuts row
     * under that - the blank so the shortcuts read as their own line and not a
     * wrap of the status. Fixed, since JLine's status cannot change height under
     * text without losing where the cursor is; the workspace is in the bar at the
     * top of the window instead.
     */
    private fun barRows(): Int = 4

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
        // A plain rule, a column in from the edge: a bar, not the last line of the answer. What is attached is said
        // on the status line and in the line's own chip, never on the rule - a picture pasted there was named twice.
        val dash = if (region.ansi && LiveRegion.unicodeFor(terminal.encoding()?.name())) "─" else "-"
        val rule = theme.accent(dash.repeat((width() - 1).coerceAtLeast(1)))
        val lines = listOf(rule, " " + statusText(), "", " " + shortcuts())
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

    /**
     * Attachments waiting for the next message that the line being written does
     * not already name with a chip - a `/attach`ed or `/paste`d file, not a
     * picture pasted into the line. These get the 📎 on the status line; a chip
     * in the line is reminder enough for the rest, and naming them twice was the
     * clutter on the rule that is gone.
     */
    private fun unnamedAttachments(): List<Attachment> =
        attachments.filter { a -> imageChips.attachments.none { it.id == a.id } }

    private fun statusText(): String = statusParts().let { (left, right) -> oneRow(left, right) }

    /**
     * The keys worth keeping in sight, on the bar's bottom row so they are there
     * without a `/help` - the few that do something at any moment. Dimmed, as the
     * furniture it is, not what is going on. The welcome line says the same once,
     * in full, as the window scrolls; this is the short standing copy.
     */
    private fun shortcuts(): String = theme.muted(
        listOf(
            "/help",
            "${keyName("details")} details",
            "${keyName("sessions")} sessions",
            "Shift+Tab mode",
            "${pasteKey()} paste",
            "Ctrl+C stop",
            "Ctrl+D quit",
        ).joinToString(" · ")
    )

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
        // A session followed: its agent and what it has spent, rather than this chat's, which is not on the screen.
        viewed?.let { v ->
            return listOf(
                theme.text(v.agent) + theme.muted(if (v.working) " (followed)" else " (followed · finished)"),
                theme.muted("↑${TurnRenderer.tokens(v.inputTokens)} ↓${TurnRenderer.tokens(v.outputTokens)}") + (v.cost?.let { theme.muted(" · $it") } ?: ""),
                theme.muted("${keyName("sessions")}, Enter: back to main"),
            ).joinToString(theme.muted(" · ")) to theme.muted("workspace ") + theme.text(conversation.workspace.name)
        }
        val c = conversation
        val u = c.usage
        // Whether the agent can run commands on this computer: the door Orknux Shell keeps open to the server for it.
        // Said only when something is wrong: an agent here runs commands on this computer as a matter of course, and a
        // light that is always on says nothing. Off, while the door to the server for them is down.
        // The door takes a moment to open at the start, which is not news.
        val settled = System.currentTimeMillis() - startedAt > DEVICE_GRACE_MILLIS
        val device = if (::device.isInitialized && !device.connected && settled) theme.warning("commands cannot run here: reconnecting") else null
        // What is attached and waiting for the next message, said here rather than on the rule. Not while a turn runs:
        // the message it went with is in flight, no longer waiting, and the sent line already shows it.
        val waiting = if (phase != Phase.TURN) unnamedAttachments() else emptyList()
        val attached = when {
            waiting.isEmpty() -> null
            waiting.size == 1 -> theme.accent("📎 ") + theme.text(waiting.single().filename) + theme.muted(" ${size(waiting.single().sizeBytes)}")
            else -> theme.accent("📎 ") + theme.text("${waiting.size} attached")
        }
        val left = listOfNotNull(
            attached,
            // No fallback word: "agent" read as an agent called that. Before the server has chosen, nothing is said.
            (c.agent?.name ?: c.chat?.agentName)?.let(theme::text),
            modeLabel(),
            c.effort?.let { theme.muted("effort ") + theme.text(it) },
            goal?.takeIf { it.running }?.let { theme.accent("goal ${it.progress}") },
            theme.muted("↑${TurnRenderer.tokens(u.inputTokens)} ↓${TurnRenderer.tokens(u.outputTokens + c.writingSoFar)} (${TurnRenderer.tokens(u.cachedInputTokens)} cached)"),
            // The cost only where the model has a price; a local model's has none, and a dash read as a glitch.
            u.cost?.let { theme.muted(TurnRenderer.cost(it)) },
            c.speeds.said()?.let { theme.muted(it) },
            sessionCount.takeIf { it > 0 }?.let { theme.text("$it subagent" + (if (it == 1) "" else "s")) + theme.muted(" " + keyName("sessions")) },
            device,
        ).joinToString(theme.muted(" · "))
        // The workspace is in the bar at the top of the window, where one is rather than what is going on.
        val update = updateWaiting?.let { theme.accent("update to $it") + theme.muted(" · /update") + theme.muted("   ") }.orEmpty()
        return left to update + theme.muted("workspace ") + theme.text(c.workspace.name)
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
            "config" -> configure(argument)
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
            "version" -> version()
            "paste" -> pasteToNext(slash.argument?.trim()?.takeIf { it.isNotEmpty() })
            "voice" -> voice(argument)
            "copy-image" -> copyImage(argument)
            "answer" -> answerQuestions(conversation, askedFor = true)
            "sessions" -> sessions()
            "orkx-web-chat" -> openInBrowser(
                "this chat",
                "/chat/" + (conversation.chat?.id ?: throw OrknuxException("There is no chat yet: it starts with your first message.")),
            )
            // The session on the screen: a subagent's while it is followed, this chat's own otherwise.
            "orkx-web-session" -> viewed?.let { v ->
                openInBrowser("${v.agent}'s session log", "/workspace/${conversation.workspace.id}/sessions/${v.sessionId}")
            } ?: openInBrowser(
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
        // What was being read belongs to the chat it was read from; another chat starts on its own.
        if (readingRoot != own) {
            reading = null
            readingRoot = own
        }
        val found = buildList {
            api.session(own)?.let { add(Found(it, MAIN)) }
            // The rest under it, active ones first; the chat's own stays on top, whichever one is being read.
            addAll(buildList {
                api.subagents(own).forEach { add(Found(it, "asked")) }
                sideChats.values.forEach { side -> side.llmSessionId?.let { id -> api.session(id)?.let { add(Found(it, "@agent", side.chat?.id)) } } }
            }.sortedByDescending { it.session.active })
        }
        sessionCount = (found.size - 1).coerceAtLeast(0)
        if (found.isEmpty()) {
            say(theme.muted("No sessions found for this chat."))
            return
        }
        // "Active" as the server means it - an agent at work in it now, or a moment ago - not a claim that a turn is running.
        val active = found.count { it.session.active }
        // The count in the list's own title: written as a line, it stayed in the conversation after the list was gone.
        val title = "Sessions of this chat" + theme.muted(" · ${found.size}, $active active")
        val shown = found.indexOfFirst { if (reading == null) it.kind == MAIN else it.session.sessionId == reading }.coerceAtLeast(0)
        // Following another, the highlight starts on main - Ctrl+T, Enter is the way back; the one followed stays marked.
        val chosen = chooseFrom(title, found, if (reading != null) 0 else shown, closeKey = keyOf("sessions")) { f ->
            val s = f.session
            (if (s.active) theme.success("● active ") else theme.muted("○ idle   ")) + theme.text(s.agent) + theme.muted(" (${f.kind})") +
                (if (found.indexOf(f) == shown) theme.accent(" · reading") else "") +
                theme.muted(" · ↑${TurnRenderer.tokens(s.inputTokens)} ↓${TurnRenderer.tokens(s.outputTokens)}" + (s.cost?.let { " · $it" } ?: "") +
                    " · " + TurnRenderer.oneLine(s.about))
        } ?: return
        open(chosen)
    }

    /** A session of this chat as /sessions lists it: whose it is, and the chat behind it where it is a side chat. */
    private data class Found(val session: Subagent, val kind: String, val chat: String? = null)

    /**
     * A session read here, in the terminal: its transcript written out under a
     * line saying whose it is. The chat's own takes you back to it. What you
     * write still goes to this chat, whichever one is being read - and the next
     * /sessions lists the same family, from this chat's session, with the one
     * being read marked.
     */
    private fun open(found: Found) {
        val s = found.session
        when {
            found.kind == MAIN -> showMain()
            else -> follow(found)
        }
    }

    /** Back to this chat: the screen written again with its lines, those that came while another was followed too. */
    private fun showMain() {
        reading = null
        viewing = Transcript.MAIN
        viewed = null
        region.focused = false
        region.focusDetail = emptyList()
        region.focusPhase(null, null)
        repaint()
        footer()
    }

    /**
     * Another session of this chat on the screen, followed as it goes: the
     * screen written again with its lines - none, the first time, then its
     * transcript so far - and every few moments what it has done since. The
     * live block says what it is doing, and the status bar what it has spent.
     * This chat's turn goes on meanwhile, kept and shown again on going back.
     */
    private fun follow(found: Found) {
        val s = found.session
        reading = s.sessionId
        viewing = s.sessionId
        viewed = s
        region.focused = true
        region.focusPhase(if (s.working) "model" else null, if (s.working) "Waiting for the model" else null)
        repaint()
        if (followed[s.sessionId] == null) {
            say(theme.bold(theme.accent(s.agent)) + theme.muted(" · " + found.kind + (s.about.takeIf { it.isNotBlank() }?.let { " · " + TurnRenderer.oneLine(it) } ?: "")))
            say(theme.muted("What you write here goes to ${s.agent}, into its turn, where it reads it at its next step - Enter or ${keyName("send-now")}. ${keyName("sessions")}, Enter goes back to this chat."))
            say("")
            followed[s.sessionId] = 0
        }
        footer()
        val chat = found.chat
        Thread.ofVirtual().name("orknux-follow-session").start {
            val markdown = Markdown(theme, { width() }, links)
            while (viewing == s.sessionId) {
                runCatching {
                    if (chat != null) {
                        // A side chat is a chat: its history, once, and its own turns are drawn where they are sent.
                        if (followed[s.sessionId] == 0) {
                            printHistory(api.messages(chat))
                            followed[s.sessionId] = -1
                        }
                    } else {
                        val all = api.sessionLines(s.sessionId, FOLLOW_LINES)
                        val from = followed[s.sessionId] ?: 0
                        // A thought still being written grows on the server after it first appears: written into the
                        // transcript then, it would stay as its first words. It is shown live until something follows it.
                        val settled = if (viewed?.working == true && all.lastOrNull()?.kind == "THINKING") all.dropLast(1) else all
                        if (settled.size > from && viewing == s.sessionId) {
                            settled.drop(from).forEach { line -> sessionLine(line, markdown).forEach { (text, view) -> say(text, view, s.sessionId) } }
                            followed[s.sessionId] = settled.size
                        }
                        if (viewing == s.sessionId) followedPhase(s.sessionId, all.lastOrNull(), all.size)
                    }
                    api.session(s.sessionId)?.let { viewed = it }
                    footer()
                }
                try {
                    Thread.sleep(FOLLOW_MILLIS)
                } catch (_: InterruptedException) {
                    return@start
                }
            }
        }
    }

    /**
     * The followed session's live line, as this chat's turn would say it: what
     * the server says its model is writing - the prompt being read, a tool call
     * being spelled out, the answer - and, where it writes nothing, the last
     * thing in its transcript: a tool called and not yet answered is running,
     * anything else is thinking. Nothing once it has finished.
     */
    private fun followedPhase(sessionId: String, last: SessionLine?, lines: Int) {
        val writing = api.sessionWriting(sessionId)
        // The server says exactly whether the ask's turn is going; nothing is drawn once it is not.
        val finished = viewed?.working == false
        val w = width()
        region.focusDetail = emptyList()
        when {
            finished -> region.focusPhase(null, null)
            writing?.promptTotal != null && writing.promptTotal > 0 -> {
                val done = writing.promptProcessed ?: 0
                region.focusPhase(
                    "reading", "Reading the prompt ${done * 100 / writing.promptTotal}%" +
                        theme.muted(" · ${TurnRenderer.tokens(writing.promptTotal)} tokens, ${TurnRenderer.tokens(writing.promptCached ?: 0)} cached"),
                    writing.since,
                )
            }
            writing?.tool != null -> region.focusPhase("writing:${writing.tool}", "Preparing a tool call: " + theme.accent(writing.tool) + theme.muted(" · ${writing.chars} chars"), writing.since)
            writing != null && writing.chars > 0 -> region.focusPhase("writing-answer", "Writing the answer" + theme.muted(" · ${writing.chars} chars"), writing.since)
            last?.kind == "TOOL" && last.result == null -> {
                val tool = last.actor?.takeIf { it.isNotBlank() } ?: "tool"
                region.focusPhase("call:$lines", "Running " + theme.accent(tool) + ": " + theme.muted(TurnRenderer.argumentSummary(last.content, w - tool.length - 24)))
            }
            last?.kind == "THINKING" -> {
                region.focusPhase("thinking:$lines", theme.thinking("Thinking"))
                // Its text as it streams, where the details are shown - as this chat's own thinking is drawn live.
                region.focusDetail = if (unfolded) TurnRenderer.wrapped(last.content, w - 4).takeLast(4).map { "  " + theme.muted("│ ") + theme.dim(theme.muted(it)) } else emptyList()
                return
            }
            // A tool answered and nothing written since: the model has not begun on it, or the server has not said.
            else -> region.focusPhase("model", "Waiting for the model")
        }
    }

    /** The session on the screen: [Transcript.MAIN] for this chat's own, or the id of one followed from /sessions. */
    @Volatile private var viewing: String = Transcript.MAIN

    /** The followed session as last read, for the status bar and the live block. */
    @Volatile private var viewed: Subagent? = null

    /** How many of each followed session's lines have been written: -1 for a side chat whose history is. */
    private val followed = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The session /sessions last opened, and the chat's session it belongs to; null while this chat itself is read. */
    @Volatile private var reading: String? = null
    private var readingRoot: String? = null

    /** How many sessions this chat has besides its own - the agents it asked, its side chats - for the status bar. */
    @Volatile private var sessionCount = 0
    private var sessionCountFor: String? = null
    @Volatile private var sessionCountAt = 0L

    /** Counted again in the background, at most every few seconds; the bar is drawn again when it changes. */
    private fun countSessions(force: Boolean = false) {
        val own = conversationOrNull()?.llmSessionId
        if (own == null) {
            sessionCount = sideChats.size
            return
        }
        val now = System.currentTimeMillis()
        if (!force && own == sessionCountFor && now - sessionCountAt < SESSION_COUNT_MILLIS) return
        sessionCountAt = now
        sessionCountFor = own
        Thread.ofVirtual().name("orknux-session-count").start {
            val asked = runCatching { api.subagents(own).size }.getOrNull() ?: return@start
            val count = asked + sideChats.values.count { it.llmSessionId != null }
            if (count != sessionCount) {
                sessionCount = count
                footer()
            }
        }
    }

    /**
     * Ctrl+T: the list /sessions shows, from wherever one is - at the prompt or
     * while a turn runs, the live block set aside for it - and Ctrl+T again
     * closes it, as Esc does.
     */
    private fun sessionsByKey() {
        runCatching { sessions() }.onFailure { say(theme.warning(it.message ?: "Could not list the sessions.")) }
    }

    /** Opens a page of the server in the browser, and says its address too - clickable where the terminal draws links. */
    private fun openInBrowser(what: String, path: String) {
        val url = links.absolute(path)
        val opened = runCatching { Links.open(url) }.isSuccess
        say(theme.muted(if (opened) "Opened $what in the browser: " else "Open $what in the browser: ") + links.link(url, url) { theme.accent(it) })
    }

    // ---- updates -----------------------------------------------------------------------------

    /**
     * In the background, at the start and then every hour: a newer Shell is
     * said once in the conversation and kept in the status bar until /update.
     */
    private fun lookForUpdate() {
        Thread.ofVirtual().name("orknux-shell-update-check").start {
            // The welcome has looked already, where it had the time; after that the bar alone says it, rather than a
            // line landing above the prompt in the middle of whatever is being written.
            var first = startingNews == null
            while (true) {
                val news = runCatching { DailyCheck.run(store, Updater(), force = first) }.getOrNull()
                first = false
                news?.newer?.takeIf { it != updateWaiting }?.let {
                    updateWaiting = it
                    footer()
                }
                try {
                    Thread.sleep(Updater.CHECK_EVERY)
                } catch (_: InterruptedException) {
                    return@start
                }
            }
        }
    }

    /** A newer Shell the daily look found, said in the status bar until /update installs it. */
    @Volatile private var updateWaiting: String? = null

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
        updateWaiting = null
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
        say("")
        say(theme.bold("Keys"))
        listOf(
            "Enter" to "send - while the agent works, queue it for when the turn ends",
            "${keyName("send-now")} while the agent works" to "send it now: the agent reads it at its next step",
            "Alt+Enter, Shift+Enter, or \\ at the end" to "a new line",
            "Tab" to "complete commands, agents, workspaces, skills and files",
            "Shift+Tab" to "switch mode: auto, ask, plan",
            keyName("details") to "expand thoughts and tool calls, and fold them again",
            keyName("sessions") to "this chat's sessions - its own and its agents'",
            (if (pasteKey() == "Alt+V") "Alt+V (Ctrl+V is the terminal's own paste here)" else "Ctrl+V (or Alt+V)") to "paste: a picture on the clipboard is attached to the next message, as a chip Backspace takes out",
            "Ctrl+L" to "clear the screen",
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
        // My Claude Code at the end, as the browser's picker has it, where this server offers it: the person's own
        // Claude Code answering, through the Orknux channel plugin - said whether it is connected now.
        val claude = api.claudeCode()?.takeIf { it.available }
        val mine = claude?.let { Agent(CLAUDE_CODE, "My Claude Code", if (it.connected) "your own Claude Code, connected now" else "your own Claude Code - not connected: start it with the Orknux channel", true, emptyList(), emptyList()) }
        val offered = agents + listOfNotNull(mine)
        val now = if (conversation.claudeCode) offered.lastIndex
        else agents.indexOfFirst { it.id == (conversation.agent?.id ?: conversation.chat?.agentId) }.coerceAtLeast(0)
        val chosen = if (argument == null) {
            chooseFrom("Agent", offered, now) { a -> theme.text(a.name) + (a.description?.let { theme.muted(" - " + TurnRenderer.oneLine(it)) } ?: "") } ?: return
        } else {
            SlashCommands.pick(offered, argument) { it.name } ?: throw OrknuxException("No agent \"$argument\" here.")
        }
        if (chosen.id == CLAUDE_CODE) {
            conversation.chooseClaudeCode()
            say(theme.muted("Agent ") + theme.text("My Claude Code") + theme.muted(if (claude?.connected == true) " - your own Claude Code answers here." else " - it answers once your Claude Code is started with the Orknux channel."))
            return
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
        conversation.agent = Connection.agent(agents, config, null) { api.chats(chosen.id) }
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
                "user" -> userLines(m.content).forEach(::say)
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
                "@", side.agent?.name ?: chat.agentName ?: "", chat.title, if (active === side) "running" else "answered",
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
        api.sessionLines(sessionId).forEach { line -> sessionLine(line, markdown).forEach { (text, view) -> say(text, view) } }
    }

    /**
     * One event of a session as the screen shows it, drawn as this chat's own
     * turn draws it: a blank line before it, a thought folded to a line and a
     * tool call to one - "✓ http_get …" - each written out whole under Ctrl+O.
     * Folded first, then whole: [View] says which the screen shows.
     */
    private fun sessionLine(line: SessionLine, markdown: Markdown): List<Pair<String, View>> {
        val w = width()
        fun both(lines: List<String>) = listOf("" to View.BOTH) + lines.map { it to View.BOTH }
        return when (line.kind) {
            // What the person wrote into the turn comes back as a USER line in their name once the agent has read it:
            // drawn as read, not as a second copy of what the screen already shows they sent.
            "USER" -> if (line.actor != null && line.actor == config.user) both(listOf(theme.success("✓ ") + theme.muted("read: " + TurnRenderer.oneLine(line.content))))
            else both(userLines(TurnRenderer.clip(TurnRenderer.oneLine(line.content), w * 3)))
            "AGENT" -> both(markdown.render(line.content).removeSuffix("\n").split('\n'))
            "TOOL" -> {
                val tool = line.actor?.takeIf { it.isNotBlank() } ?: "tool"
                val result = line.result ?: ""
                listOf("" to View.BOTH) +
                    (TurnRenderer.calledLine(theme, tool, line.content, result, false, null, w) to View.FOLDED) +
                    TurnRenderer.detailLines(listOf(Detail.Tool(tool, line.content, result, false, null)), theme, w)
                        .dropLastWhile { it.isEmpty() }.map { it to View.FULL }
            }
            "THINKING" -> {
                // Its own duration, so a long think in a followed session says "Thought for 75s" as this chat's does,
                // not a bare "Thought" that reads as silence.
                val block = ThinkingBlock(line.content, line.millis ?: -1)
                listOf("" to View.BOTH) + TurnRenderer.fold(block, "collapsed", theme, w).map { it to View.FOLDED } +
                    TurnRenderer.fold(block, "expanded", theme, w).map { it to View.FULL }
            }
            else -> emptyList()
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
            override fun line(text: String) = say(text, owner = Transcript.MAIN)
            override fun lines(folded: List<String>, full: List<String>) = sayEither(folded, full)
            override fun phase(key: String, text: String) {
                val g = goal
                if (region.active && g != null) region.phase("goal:$key", "Goal · iteration ${g.iteration + 1} of ${g.maxIterations ?: "∞"} · " + stripAnsi(text).lowercase())
            }
            override fun width(): Int = this@Interactive.width()
        }
        while (goal?.running == true) {
            val renderer = TurnRenderer(theme, output, if (config.thinking == "hidden") "hidden" else "collapsed", links)
            var followed = false
            phase = Phase.TURN
        followLive()
            region.phase("goal", "Goal · iteration ${(goal?.iteration ?: 0) + 1} of ${goal?.maxIterations ?: "∞"} · working")
            region.start { below() }
            val ticker = Thread.ofPlatform().daemon().start {
                var n = 0
                while (phase == Phase.TURN) {
                    tickTurn()
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
                    ChatEvent.of(frame).let { conversation.heard(it); renderer.handle(it) }
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
    private fun <T> chooseFrom(what: String, items: List<T>, current: Int = 0, closeKey: Int? = null, label: (T) -> String): T? {
        if (items.isEmpty()) return null
        if (!::region.isInitialized || !region.ansi) {
            say(theme.bold(what))
            items.forEachIndexed { i, item -> say("  " + theme.accent("${i + 1}.") + " " + label(item)) }
            val answer = ask(theme.muted("Number (Enter to cancel) › "))
            if (answer.isEmpty()) return null
            return SlashCommands.pick(items, answer) { stripAnsi(label(it)) }
        }
        // A blank line between what came before - a heading, the command typed - and the list.
        say("")
        val picker = Picker(
            sink = { text -> terminal.writer().print(text); terminal.writer().flush() },
            key = { timeout -> terminal.reader().read(if (timeout < 0) 0L else timeout) },
            theme = theme,
            rows = { terminal.height.takeIf { it > 0 } ?: 24 },
            width = ::width,
            closeKey = closeKey,
        )
        val start = listStart()
        val saved = terminal.enterRawMode()
        val chosen = try {
            synchronized(out) { picker.choose(what, items.map(label), current) }
        } finally {
            terminal.attributes = saved
        }
        // What the list pushed the screen up by while it was open comes back, the prompt above the bar.
        giveBack(picker, start)
        return chosen?.let { items[it] }
    }

    /**
     * The shortcuts: a list to choose one from and press its new key, applied
     * at once and kept in the configuration. `/config send-now ctrl+s` sets one
     * directly, `/config send-now default` puts it back. Only Ctrl+letter, and
     * not a letter the terminal or the line already means something by.
     */
    private fun configure(argument: String?) {
        val words = argument?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (words.size >= 2) {
            val action = Keys.ACTIONS.firstOrNull { it.name == words[0].lowercase() }
                ?: throw OrknuxException("No shortcut \"${words[0]}\": they are " + Keys.ACTIONS.joinToString(", ") { it.name } + ".")
            if (words[1].lowercase() in setOf("default", "reset")) {
                setKey(action.name, null)
                return
            }
            setKey(action.name, words.drop(1).joinToString("+").lowercase())
            return
        }
        if (!region.ansi) {
            Keys.ACTIONS.forEach { say("  " + theme.accent(it.name.padEnd(9)) + theme.text(Keys.label(Keys.specFor(it.name, config.keys)).padEnd(8)) + theme.muted(it.what)) }
            say(theme.muted("/config <shortcut> ctrl+<letter> sets one; /config <shortcut> default puts it back."))
            return
        }
        val chosen = chooseFrom("Shortcuts", Keys.ACTIONS) { a ->
            theme.text(a.name.padEnd(9)) + theme.accent(Keys.label(Keys.specFor(a.name, config.keys)).padEnd(8)) + theme.muted(a.what)
        } ?: return
        say(theme.muted("Press the new key for ") + theme.text(chosen.name) + theme.muted(" - Ctrl and a letter; Esc keeps ${Keys.label(Keys.specFor(chosen.name, config.keys))}."))
        val saved = terminal.enterRawMode()
        val pressed = try {
            terminal.reader().read(0L)
        } finally {
            terminal.attributes = saved
        }
        if (pressed == 27 || pressed < 0) {
            say(theme.muted("Kept."))
            return
        }
        val spec = Keys.specOf(pressed) ?: throw OrknuxException("That is not Ctrl and a letter; nothing was changed.")
        setKey(chosen.name, spec)
    }

    /** One shortcut changed, or put back with null: checked, kept, bound at the prompt, and said. */
    private fun setKey(action: String, spec: String?) {
        if (spec != null) {
            if (Keys.code(spec) == null) {
                throw OrknuxException(
                    "\"$spec\" cannot be a shortcut: only Ctrl and a letter, and not one the terminal or the line already " +
                        "uses (Ctrl+C, D, H, I, J, L, M, Q, U, Z). Nothing was changed.",
                )
            }
            Keys.ACTIONS.firstOrNull { it.name != action && Keys.specFor(it.name, config.keys) == spec }?.let {
                throw OrknuxException("${Keys.label(spec)} is ${it.name} already; give one of them another key first. Nothing was changed.")
            }
        }
        config = store.update { it.copy(keys = if (spec == null) it.keys - action else it.keys + (action to spec)) }
        if (::reader.isInitialized) bindShortcuts(reader)
        if (::region.isInitialized) region.thinkingHint = detailsHint()
        footer()
        say(theme.text(action) + theme.muted(" is ") + theme.accent(Keys.label(Keys.specFor(action, config.keys))) + theme.muted(if (spec == null) " again, its default." else " now; kept in ${store.path}."))
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

        /**
         * What may run while a turn is going: nothing that starts one (skill, review, goal, agent), switches the
         * conversation (resume, clear, workspace), or reads a line with the editor the turn is not using (answer,
         * theme). The rest wait in the queue for the turn to end.
         */
        val WHILE_WORKING = setOf(
            "attach", "detach", "open", "thinking", "effort", "subagents", "sessions", "usage", "mode", "rules",
            "orkx-web-chat", "orkx-web-session", "orkx-web-agent-settings", "update", "help", "copy-image", "version",
        )

        /** What the configurable keys become inside the turn's key loop, below anything a key can be. */
        const val DETAILS = -100
        const val SESSIONS = -101
        const val SEND_NOW = -102
        const val SESSION_COUNT_MILLIS = 5_000L
        const val LIVE_MILLIS = 4_000L
        const val FOLLOW_MILLIS = 1_500L
        const val FOLLOW_LINES = 1_000

        /** The chat's own session, as /sessions names it: the one the others were asked from. */
        const val MAIN = "main"

        /** How long the terminal has to say where the cursor is. */
        const val CURSOR_ANSWER_MILLIS = 800L

        /** How long the welcome waits for the look for an update. */
        const val STARTING_LOOK_MILLIS = 2_000L

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
    /** The prompt and the line drawn again from nothing: the screen under them was cleared. */
    fun redrawPrompt() {
        redrawLine()
        redisplay()
        flush()
    }

    override fun handleSignal(signal: Terminal.Signal) {
        super.handleSignal(signal)
        if (signal == Terminal.Signal.WINCH) resized()
    }
}
