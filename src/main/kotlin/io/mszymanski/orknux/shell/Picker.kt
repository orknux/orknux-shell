package io.mszymanski.orknux.shell

/**
 * A list to choose from with the keys: Up and Down (or k and j) move, Home and
 * End jump, a number picks that row, Enter takes the highlighted one, Esc,
 * Ctrl+C or q leaves without choosing.
 *
 * Drawn at the cursor, at the foot of the screen, a window of the list where it
 * is longer than the room there is; it takes itself away when done, so what
 * follows is the command's answer and not a menu left behind. Keys are read
 * one at a time through [key]: a character, or [TIMEOUT] when none came.
 */
class Picker(
    private val sink: (String) -> Unit,
    private val key: (Long) -> Int,
    private val theme: Theme,
    private val rows: () -> Int,
    private val width: () -> Int,
) {
    /** How many rows the list took when it was last drawn: what it pushed the screen up by, less one. */
    var lastHeight = 0
        private set

    /** The index chosen, or null. [labels] may carry colour; [current] is where the highlight starts. */
    fun choose(title: String, labels: List<String>, current: Int = 0): Int? = pick(title, labels, current, many = false)?.first

    /**
     * Several chosen: Space ticks and unticks the highlighted row, Enter takes
     * the ticked ones - or the highlighted one, where none is ticked. Null for Esc.
     */
    fun chooseMany(title: String, labels: List<String>): Set<Int>? =
        pick(title, labels, 0, many = true)?.let { (at, ticked) -> ticked.ifEmpty { setOf(at) } }

    private fun pick(title: String, labels: List<String>, current: Int, many: Boolean): Pair<Int, Set<Int>>? {
        if (labels.isEmpty()) return null
        val ticked = sortedSetOf<Int>()
        var at = current.coerceIn(0, labels.lastIndex)
        // At least three rows where the screen is tiny, but never more than there are choices: one workspace is one row.
        val window = (rows() - 6).coerceAtLeast(3).coerceAtMost(labels.size)
        var top = (at - window / 2).coerceIn(0, labels.size - window)
        var drawn = 0
        var typed = ""

        fun frame(): List<String> {
            if (at < top) top = at
            if (at >= top + window) top = at - window + 1
            val out = mutableListOf(theme.bold(title) + theme.muted(if (many) "  ↑↓ move · Space tick · Enter take · Esc leave" else "  ↑↓ choose · Enter take · Esc leave"))
            for (i in top until top + window) {
                val number = theme.muted("${i + 1}.".padStart(labels.size.toString().length + 1)) + " " +
                    (if (!many) "" else if (i in ticked) theme.accent("[x] ") else theme.muted("[ ] "))
                out += if (i == at) theme.accent("› ") + number + theme.bold(stripAnsi(labels[i]))
                else "  " + number + labels[i]
            }
            if (labels.size > window) out += theme.muted("  ${top + 1}-${top + window} of ${labels.size}")
            return out.map { LiveRegion.clip(it, width() - 1) }
        }

        fun erase() {
            if (drawn == 0) return
            val up = drawn - 1
            val sb = StringBuilder("\r")
            if (up > 0) sb.append("\u001b[").append(up).append('A')
            repeat(drawn) { row -> sb.append(LiveRegion.CLEAR_LINE); if (row < up) sb.append("\u001b[B") }
            if (up > 0) sb.append("\u001b[").append(up).append('A')
            sink(sb.toString())
        }

        /*
         * Each frame written over the last in place, one synchronized write:
         * back to the first row, every row cleared and written as it goes, and
         * rows the frame no longer has cleared after it. Erasing all of it first
         * and drawing again was a blank list between keys - a flash on every arrow.
         */
        fun draw() {
            val lines = frame()
            val sb = StringBuilder(LiveRegion.SYNC_START).append('\r')
            if (drawn > 1) sb.append("\u001b[").append(drawn - 1).append('A')
            lines.forEachIndexed { i, line ->
                if (i > 0) sb.append("\r\n")
                sb.append(LiveRegion.CLEAR_LINE).append(line)
            }
            val left = drawn - lines.size
            if (left > 0) {
                repeat(left) { sb.append("\r\n").append(LiveRegion.CLEAR_LINE) }
                sb.append("\u001b[").append(left).append('A')
            }
            sb.append(LiveRegion.SYNC_END)
            sink(sb.toString())
            drawn = lines.size
        }

        sink(LiveRegion.HIDE_CURSOR)
        try {
            draw()
            while (true) {
                val c = key(-1)
                when {
                    c < 0 -> return null
                    c == 13 || c == 10 -> return at to ticked.toSet()
                    many && c == ' '.code -> if (!ticked.remove(at)) ticked.add(at)
                    c == 3 || c == 'q'.code -> return null
                    c == 27 -> when (escape()) {
                        "[A", "OA" -> at = (at - 1).coerceAtLeast(0)
                        "[B", "OB" -> at = (at + 1).coerceAtMost(labels.lastIndex)
                        "[H", "OH", "[1~" -> at = 0
                        "[F", "OF", "[4~" -> at = labels.lastIndex
                        "[5~" -> at = (at - window).coerceAtLeast(0)
                        "[6~" -> at = (at + window).coerceAtMost(labels.lastIndex)
                        "" -> return null
                    }
                    c == 'k'.code -> at = (at - 1).coerceAtLeast(0)
                    c == 'j'.code -> at = (at + 1).coerceAtMost(labels.lastIndex)
                    c in '0'.code..'9'.code -> {
                        // A number takes that row; two digits are read where the list is that long.
                        typed += c.toChar()
                        val n = typed.toInt()
                        if (n in 1..labels.size) at = n - 1
                        if (typed.length >= labels.size.toString().length || n * 10 > labels.size) {
                            typed = ""
                            if (n in 1..labels.size && !many) return at to ticked.toSet()
                        }
                    }
                }
                draw()
            }
        } finally {
            lastHeight = drawn
            erase()
            drawn = 0
            sink("\r" + LiveRegion.SHOW_CURSOR)
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
        const val TIMEOUT = -2
    }
}
