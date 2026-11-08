package io.mszymanski.orknux.shell

/**
 * The live part of the screen while a turn runs, and the only thing that
 * writes to the terminal while it does.
 *
 * The region - the thinking streaming now, a spinner with what is happening
 * and for how long, the attachments and the status line - sits under the
 * transcript and is redrawn in place: back up to its first line, clear to the
 * end of the screen, draw it again. A transcript line is written by clearing
 * the region, writing the line, and drawing the region under it, so the region
 * moves down with the text and is never torn. Every write is under one lock,
 * so a tick of the spinner can never land in the middle of a line.
 *
 * Each region line is clipped to the terminal's width: a line that wrapped
 * would take two rows, and the redraw would climb one row too few.
 *
 * Off a terminal ([ansi] false) it writes plain lines and draws nothing live.
 */
class LiveRegion(
    private val sink: (String) -> Unit,
    private val width: () -> Int,
    val ansi: Boolean,
    private val theme: Theme,
    private val unicode: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var drawnRows = 0
    private var frame = 0

    @Volatile var active = false
        private set

    private var phaseKey: String? = null
    private var phaseText: String = ""
    private var phaseStarted = 0L
    private var stopped: String? = null
    private var thinking: String? = null
    private var thinkingStarted = 0L
    private var below: () -> List<String> = { emptyList() }
    /** Extra lines between the spinner and what is below, such as the approval's choices. */
    private var asking: String? = null

    val frames: List<String> = if (unicode) BRAILLE else ASCII

    /** Starts drawing: the cursor hidden, the region drawn where the cursor is. */
    fun start(below: () -> List<String>) = synchronized(lock) {
        this.below = below
        active = true
        stopped = null
        drawnRows = 0
        if (ansi) sink(HIDE_CURSOR)
        draw()
    }

    /** Stops drawing: the region cleared, the cursor shown, and the screen the line editor's again. */
    fun stop() = synchronized(lock) {
        if (!active) return
        erase()
        active = false
        phaseKey = null
        thinking = null
        asking = null
        if (ansi) sink(SHOW_CURSOR)
    }

    /** A permanent line into the transcript, above the region. */
    fun line(text: String) = synchronized(lock) {
        if (!ansi) {
            sink(stripAnsi(text) + "\n")
            return
        }
        val frameStart = if (active) SYNC_START else ""
        sink(frameStart)
        erase()
        sink(text + "\r\n")
        drawnRows = 0
        if (active) {
            draw()
            sink(SYNC_END)
        }
    }

    /**
     * What is happening now. The clock starts again only when [key] changes, so a
     * tool call being written can say how far it has got without its time resetting.
     */
    fun phase(key: String, text: String) = synchronized(lock) {
        if (key != phaseKey) {
            phaseKey = key
            phaseStarted = clock()
        }
        phaseText = text
        stopped = null
        redraw()
    }

    /** The spinner stops at once, and says why. */
    fun halt(text: String) = synchronized(lock) {
        stopped = text
        redraw()
    }

    fun thinking(text: String?) = synchronized(lock) {
        if (text != null && thinking == null) thinkingStarted = clock()
        thinking = text
        redraw()
    }

    fun asking(text: String?) = synchronized(lock) {
        asking = text
        redraw()
    }

    /** One frame of the spinner. Nothing while stopped or off a terminal. */
    fun tick() = synchronized(lock) {
        frame++
        if (stopped == null) redraw()
    }

    /** Draws again, for a resize or a status that changed. */
    fun redraw() = synchronized(lock) {
        if (!active || !ansi) return
        sink(SYNC_START)
        erase()
        draw()
        sink(SYNC_END)
    }

    /** The region's lines as they are now. */
    fun lines(): List<String> {
        val out = mutableListOf<String>()
        val w = (width() - 1).coerceAtLeast(10)
        thinking?.let { text ->
            out += theme.thinking("Thinking") + theme.muted(" · ${(clock() - thinkingStarted) / 1000}s")
            TurnRenderer.wrapped(text, w - 4).takeLast(THINKING_LINES).forEach { out += "  " + theme.muted("│ ") + theme.dim(theme.muted(it)) }
        }
        val elapsed = (clock() - phaseStarted) / 1000
        out += stopped?.let { theme.warning("■ ") + it }
            ?: (theme.accent(frames[frame % frames.size]) + " " + phaseText + theme.muted(" · ${elapsed}s"))
        asking?.let { out += it }
        out += below()
        return out.map { clip(it, w) }
    }

    private fun draw() {
        if (!active || !ansi) return
        val rows = lines()
        sink("\r" + rows.joinToString("\r\n"))
        drawnRows = rows.size
    }

    /** Back to the region's first row, and everything from there down cleared. */
    private fun erase() {
        if (!ansi || drawnRows == 0) return
        sink("\r" + (if (drawnRows > 1) "\u001b[${drawnRows - 1}A" else "") + CLEAR_DOWN)
        drawnRows = 0
    }

    companion object {
        val BRAILLE = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        val ASCII = listOf("|", "/", "-", "\\")
        const val THINKING_LINES = 5
        const val CLEAR_DOWN = "\u001b[J"
        const val HIDE_CURSOR = "\u001b[?25l"
        const val SHOW_CURSOR = "\u001b[?25h"
        /** Synchronized output: a terminal that knows it shows the frame whole; one that does not ignores it. */
        const val SYNC_START = "\u001b[?2026h"
        const val SYNC_END = "\u001b[?2026l"

        /** Braille where the terminal speaks UTF-8 and is not a legacy Windows console. */
        fun unicodeFor(encoding: String?, os: String = System.getProperty("os.name"), env: Map<String, String> = System.getenv()): Boolean {
            val utf8 = encoding?.uppercase()?.replace("-", "")?.contains("UTF8") == true
            if (!utf8) return false
            if (Platform.isWindows(os) && !env.containsKey("WT_SESSION") && env["TERM_PROGRAM"] != "vscode") return false
            return true
        }

        /** [text] cut to [width] visible columns, its escapes kept and closed. */
        fun clip(text: String, width: Int): String {
            if (visibleLength(text) <= width) return text
            val out = StringBuilder()
            var seen = 0
            var i = 0
            val escape = Regex("^(\u001b\\[[0-9;?]*[A-Za-z]|\u001b]8;[^\u001b]*\u001b\\\\)")
            while (i < text.length && seen < width - 1) {
                val m = escape.find(text.substring(i, minOf(text.length, i + 300)))
                if (m != null) {
                    out.append(m.value)
                    i += m.value.length
                    continue
                }
                val cp = text.codePointAt(i)
                out.appendCodePoint(cp)
                i += Character.charCount(cp)
                seen++
            }
            return out.append("…").append(Theme.RESET).toString()
        }
    }
}
