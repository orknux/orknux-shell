package io.mszymanski.orknux.shell

/**
 * The last turn in full - its thinking, and every tool call with what it was
 * given and what it answered - read in a page of its own, on the terminal's alternate screen: what
 * the conversation's screen holds is left exactly as it was and comes back
 * when the page closes. Up and Down (or k and j) scroll a line, PgUp, PgDn
 * and Space a page, Home and End to either end; Ctrl+O, Esc or q close it.
 */
class ThinkingPage(
    private val lines: List<String>,
    private val theme: Theme,
    private val rows: () -> Int,
    private val width: () -> Int,
    private val sink: (String) -> Unit,
    private val key: (Long) -> Int,
) {
    fun show() {
        var top = 0
        sink(ALTERNATE_ON + LiveRegion.HIDE_CURSOR)
        try {
            while (true) {
                val height = (rows() - 2).coerceAtLeast(1)
                val last = (lines.size - height).coerceAtLeast(0)
                top = top.coerceIn(0, last)
                val sb = StringBuilder(LiveRegion.SYNC_START).append("\u001b[H")
                sb.append(LiveRegion.CLEAR_LINE).append(theme.bold("The last turn, in full")).append(theme.muted("  ↑↓ PgUp PgDn scroll · Ctrl+O, Esc or q to close")).append("\r\n")
                for (i in 0 until height) {
                    sb.append(LiveRegion.CLEAR_LINE).append(LiveRegion.clip(lines.getOrElse(top + i) { "" }, width() - 1)).append("\r\n")
                }
                val shown = if (lines.isEmpty()) 0 else (top + height).coerceAtMost(lines.size)
                sb.append(LiveRegion.CLEAR_LINE).append(theme.muted("  ${top + 1}-$shown of ${lines.size}"))
                sb.append(LiveRegion.SYNC_END)
                sink(sb.toString())
                when (val c = key(-1)) {
                    15, 3, 'q'.code, -1 -> return
                    'j'.code -> top++
                    'k'.code -> top--
                    ' '.code -> top += height
                    27 -> when (escape()) {
                        "" -> return
                        "[A", "OA" -> top--
                        "[B", "OB" -> top++
                        "[5~" -> top -= height
                        "[6~" -> top += height
                        "[H", "OH", "[1~" -> top = 0
                        "[F", "OF", "[4~" -> top = last
                    }
                    else -> if (c < -1) return
                }
            }
        } finally {
            sink(LiveRegion.SHOW_CURSOR + ALTERNATE_OFF)
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
        const val ALTERNATE_ON = "\u001b[?1049h"
        const val ALTERNATE_OFF = "\u001b[?1049l"
    }
}
