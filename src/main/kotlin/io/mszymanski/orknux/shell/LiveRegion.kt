package io.mszymanski.orknux.shell

/**
 * The live part of the screen while a turn runs, and the only thing that
 * writes to the terminal while it does.
 *
 * The region - the thinking streaming now, a spinner with what is happening
 * and for how long, the attachments and the status line - sits under the
 * transcript and is redrawn in place: back up to its first line, clear its
 * rows, draw it again. A transcript line is written by clearing
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
    /** How to see the thinking, said after it on its line while there is thinking to see; null for nothing. */
    @Volatile var thinkingHint: String? = null

    /** Whether the transcript's last line is a blank one; where it is not, the region keeps a blank row above itself. */
    private var afterBlank = true

    val frames: List<String> = if (unicode) BRAILLE else ASCII

    /** Starts drawing: the cursor hidden, the region drawn where the cursor is. */
    fun start(afterBlank: Boolean = true, below: () -> List<String>) = synchronized(lock) {
        this.below = below
        this.afterBlank = afterBlank
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
        thinkingHint = null
        asking = null
        if (ansi) sink(SHOW_CURSOR)
    }

    /** While another screen is up - the full-turn page - nothing is drawn; lines wait, and are written after. */
    @Volatile var paused = false
        private set
    private val held = mutableListOf<String>()

    fun pause() = synchronized(lock) { paused = true }

    /** Back on the conversation's screen: what came meanwhile is written, and the region drawn again. */
    fun resume() = synchronized(lock) {
        paused = false
        // The conversation's screen comes back as it was, the region's rows on it and the cursor where it was:
        // they are erased and drawn again from there, as on any other redraw.
        val waiting = held.toList()
        held.clear()
        waiting.forEach { line(it) }
        if (waiting.isEmpty()) redraw()
    }

    /** A permanent line into the transcript, above the region. */
    fun line(text: String) = synchronized(lock) {
        if (paused) {
            held += text
            return
        }
        if (!ansi) {
            sink(stripAnsi(text) + "\n")
            return
        }
        val frameStart = if (active) SYNC_START else ""
        sink(frameStart)
        erase()
        sink(text + "\r\n")
        afterBlank = stripAnsi(text).isBlank()
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
        if (!active || !ansi || paused) return
        sink(SYNC_START)
        erase()
        draw()
        sink(SYNC_END)
    }

    /** The region's lines as they are now. */
    fun lines(): List<String> {
        val out = mutableListOf<String>()
        val w = (width() - 1).coerceAtLeast(10)
        // Room between the work written so far and what is happening now, as between the pieces of work.
        if (!afterBlank) out += ""
        val spinner = theme.accent(frames[frame % frames.size]) + " "
        // The thinking shown live is under its own header, which carries the spinner: a second "Thinking" line
        // under it said the same thing twice.
        val thinkingNow = thinking != null && phaseKey == "thinking" && stopped == null
        thinking?.let { text ->
            out += (if (thinkingNow) spinner else "") + theme.thinking("Thinking") + theme.muted(" · ${(clock() - thinkingStarted) / 1000}s") +
                (thinkingHint?.let { theme.dim(theme.muted(" · $it")) } ?: "")
            // Asked for and nothing written yet - between a tool's answer and the model's next words - said, rather
            // than an empty space that reads as the key having done nothing.
            if (text.isBlank()) out += "  " + theme.muted("│ ") + theme.dim(theme.muted("nothing written yet"))
            else TurnRenderer.wrapped(text, w - 4).takeLast(THINKING_LINES).forEach { out += "  " + theme.muted("│ ") + theme.dim(theme.muted(it)) }
        }
        val elapsed = (clock() - phaseStarted) / 1000
        if (!thinkingNow) {
            val hint = thinkingHint?.let { theme.dim(theme.muted(" · $it")) } ?: ""
            out += stopped?.let { theme.warning("■ ") + it } ?: (spinner + phaseText + theme.muted(" · ${elapsed}s") + hint)
        }
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

    /**
     * Back to the region's first row, with its own rows cleared and nothing
     * else: the status bar is pinned at the foot of the screen, under the
     * scrolling area, and clearing to the end of the screen took it with it.
     */
    private fun erase() {
        if (!ansi || drawnRows == 0) return
        val up = drawnRows - 1
        val out = StringBuilder("\r")
        if (up > 0) out.append("\u001b[").append(up).append('A')
        repeat(drawnRows) { row ->
            out.append(CLEAR_LINE)
            if (row < up) out.append("\u001b[B")
        }
        if (up > 0) out.append("\u001b[").append(up).append('A')
        sink(out.toString())
        drawnRows = 0
    }

    /** Runs [block] with nothing else drawing: for the status bar, which is written from outside the region. */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    companion object {
        val BRAILLE = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
        val ASCII = listOf("|", "/", "-", "\\")
        const val THINKING_LINES = 5
        const val CLEAR_LINE = "\u001b[2K"
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
