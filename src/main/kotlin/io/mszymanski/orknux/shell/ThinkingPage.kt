package io.mszymanski.orknux.shell

/**
 * The last turn in full - its thinking, and every tool call with what it was
 * given and what it answered - read in a page of its own, on the terminal's alternate screen: what
 * the conversation's screen holds is left exactly as it was and comes back
 * when the page closes. Up and Down (or k and j) scroll a line, PgUp, PgDn
 * and Space a page, Home and End to either end; Ctrl+O, Esc or q close it.
 */
class ThinkingPage(
    /** What the page shows, asked again as it is drawn: a turn still running adds to it. */
    private val lines: () -> List<String>,
    private val theme: Theme,
    private val rows: () -> Int,
    private val width: () -> Int,
    private val sink: (String) -> Unit,
    private val key: (Long) -> Int,
    /** A turn still running: drawn again every moment, and kept at its end while it is read there. */
    private val live: Boolean = false,
    private val title: String = "The last turn, in full",
) {
    constructor(fixed: List<String>, theme: Theme, rows: () -> Int, width: () -> Int, sink: (String) -> Unit, key: (Long) -> Int) :
        this({ fixed }, theme, rows, width, sink, key)

    /** Ctrl+C closed it, rather than Ctrl+O, Esc or q: the caller interrupts the turn too. */
    var interrupted = false
        private set

    fun show() {
        var top = if (live) Int.MAX_VALUE else 0
        // The wheel as Up and Down while the page is open (alternate scroll): without it a terminal has nothing to
        // scroll on this screen, and the wheel did nothing at all.
        sink(ALTERNATE_ON + WHEEL_AS_ARROWS_ON + LiveRegion.HIDE_CURSOR)
        try {
            while (true) {
                val now = lines()
                val height = (rows() - 2).coerceAtLeast(1)
                val last = (now.size - height).coerceAtLeast(0)
                val atEnd = top >= last
                top = top.coerceIn(0, last)
                val sb = StringBuilder(LiveRegion.SYNC_START).append("\u001b[H")
                sb.append(LiveRegion.CLEAR_LINE).append(theme.bold(title)).append(theme.muted("  ↑↓ PgUp PgDn scroll · Ctrl+O, Esc or q to close")).append("\r\n")
                for (i in 0 until height) {
                    sb.append(LiveRegion.CLEAR_LINE).append(LiveRegion.clip(now.getOrElse(top + i) { "" }, width() - 1)).append("\r\n")
                }
                val shown = if (now.isEmpty()) 0 else (top + height).coerceAtMost(now.size)
                sb.append(LiveRegion.CLEAR_LINE).append(theme.muted("  ${top + 1}-$shown of ${now.size}" + if (live) " · still running" else ""))
                sb.append(LiveRegion.SYNC_END)
                sink(sb.toString())
                val c = key(if (live) REFRESH_MILLIS else -1)
                // Read at the end, it stays at the end as the turn writes more.
                if (live && atEnd) top = Int.MAX_VALUE
                when (c) {
                    TIMEOUT -> {}
                    3 -> { interrupted = true; return }
                    15, 'q'.code, -1 -> return
                    'j'.code -> top = top.coerceAtMost(last) + 1
                    'k'.code -> top = top.coerceAtMost(last) - 1
                    ' '.code -> top = top.coerceAtMost(last) + height
                    27 -> when (escape()) {
                        "" -> return
                        "[A", "OA" -> top = top.coerceAtMost(last) - 1
                        "[B", "OB" -> top = top.coerceAtMost(last) + 1
                        "[5~" -> top = top.coerceAtMost(last) - height
                        "[6~" -> top = top.coerceAtMost(last) + height
                        "[H", "OH", "[1~" -> top = 0
                        "[F", "OF", "[4~" -> top = Int.MAX_VALUE
                    }
                    else -> if (c < TIMEOUT) return
                }
            }
        } finally {
            sink(LiveRegion.SHOW_CURSOR + WHEEL_AS_ARROWS_OFF + ALTERNATE_OFF)
        }
    }

    /** The rest of an escape sequence, or "" for a lone Esc. */
    private fun escape(): String {
        val first = key(40)
        if (first < 0) return ""
        val sb = StringBuilder().append(first.toChar())
        while (true) {
            val c = key(10)
            if (c < 0) break
            sb.append(c.toChar())
            if (c.toChar().isLetter() || c == '~'.code) break
        }
        return sb.toString()
    }

    companion object {
        /** What [key] answers when no key came in time. */
        const val TIMEOUT = -2
        const val REFRESH_MILLIS = 300L
        const val ALTERNATE_ON = "\u001b[?1049h"
        const val ALTERNATE_OFF = "\u001b[?1049l"
        const val WHEEL_AS_ARROWS_ON = "\u001b[?1007h"
        const val WHEEL_AS_ARROWS_OFF = "\u001b[?1007l"
    }
}
