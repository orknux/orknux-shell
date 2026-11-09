package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * A list closed gives back exactly the rows it took, wherever it was drawn.
 *
 * The workspace list at the start is drawn just under the banner on a clean
 * screen. It scrolled nothing, but closing it pulled the text down by its
 * height anyway, so the banner sat a few rows down with blank rows above it for
 * the rest of the conversation. Each case draws the same screen with the list
 * and without it, through a small terminal that knows the sequences the picker
 * and the give-back write, and the two must match.
 */
class PickerGiveBackTest {
    private val theme = Theme(depth = ColourDepth.NONE)

    /** Rows 1..[bottom] scroll; the rows under them are the status bar's. Enough of VT100 for these writes. */
    private class Screen(rows: Int, val bottom: Int, val width: Int = 60) {
        val cells = MutableList(rows) { StringBuilder() }
        var row = 1
        var col = 0

        private fun line(r: Int) = cells[r - 1]

        private fun scrollUp() {
            for (r in 1 until bottom) cells[r - 1] = cells[r]
            cells[bottom - 1] = StringBuilder()
        }

        private fun scrollDown(n: Int) = repeat(n) {
            for (r in bottom downTo 2) cells[r - 1] = cells[r - 2]
            cells[0] = StringBuilder()
        }

        fun write(text: String) {
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c == '\u001b' && text.getOrNull(i + 1) == '[' -> {
                        val end = (i + 2 until text.length).first { text[it].isLetter() }
                        val args = text.substring(i + 2, end)
                        val n = args.toIntOrNull() ?: 1
                        when (text[end]) {
                            'A' -> row = (row - n).coerceAtLeast(1)
                            'B' -> row = (row + n).coerceAtMost(bottom)
                            'T' -> scrollDown(n)
                            'K' -> line(row).setLength(0)
                            // h / l: cursor shown or hidden, synchronized output - nothing drawn.
                        }
                        i = end + 1
                        continue
                    }
                    c == '\r' -> col = 0
                    c == '\n' -> if (row == bottom) scrollUp() else row += 1
                    else -> {
                        val l = line(row)
                        while (l.length < col) l.append(' ')
                        if (col < l.length) l.setCharAt(col, c) else l.append(c)
                        col += 1
                    }
                }
                i += 1
            }
        }

        fun text(): List<String> = cells.map { it.toString().trimEnd() }
    }

    private fun say(screen: Screen, line: String) = screen.write(line + "\r\n")

    /** The list drawn at the cursor and taken with Enter, then what it pushed up given back. */
    private fun pick(screen: Screen, items: List<String>, giveBack: (start: Int, height: Int) -> Int) {
        val start = screen.row
        val keys = ArrayDeque(listOf('\r'.code))
        val picker = Picker(screen::write, { keys.removeFirstOrNull() ?: -1 }, theme, { screen.cells.size }, { screen.width })
        picker.choose("Workspaces", items)
        val rows = giveBack(start, picker.lastHeight)
        if (rows > 0) screen.write("\u001b[${rows}T\u001b[${rows}B")
    }

    private val workspaces = listOf("Private", "OKO", "Shared", "Sandbox")

    private val fixed = { start: Int, height: Int, bottom: Int -> Picker.scrolledBy(start, height, bottom) }

    /** The start: banner, a blank line, the list, then the welcome - as setUp writes it. */
    private fun start(withList: Boolean, giveBack: (Int, Int) -> Int): List<String> {
        val screen = Screen(rows = 24, bottom = 22)
        say(screen, "Orknux Shell 1.4.0 · https://orknux.invalid")
        say(screen, "")
        if (withList) pick(screen, workspaces, giveBack)
        say(screen, "Workspace OKO · agent OKO Bot · mode Ask")
        return screen.text()
    }

    @Test
    fun `the workspace list at the start leaves the banner on the top row`() {
        val bottom = 22
        val without = start(withList = false) { _, _ -> 0 }
        val with = start(withList = true) { s, h -> fixed(s, h, bottom) }
        assertEquals(without, with)
        assertEquals("Orknux Shell 1.4.0 · https://orknux.invalid", with.first())
    }

    @Test
    fun `giving back the list's whole height there is what pushed the banner down`() {
        val with = start(withList = true) { _, h -> h - 1 }
        assertNotEquals(start(withList = false) { _, _ -> 0 }, with, "the old give-back must reproduce the gap")
        assertEquals("", with.first(), "a blank row above the banner")
    }

    /**
     * At the foot the list did scroll the screen, so its rows are given back as
     * before. What went into the scrollback stays there - a terminal cannot
     * scroll it back down - so the check is that the text and the cursor are
     * on the same rows as they would have been without the list.
     */
    @Test
    fun `a list opened at the foot still gives back what it pushed up`() {
        val bottom = 22
        fun foot(withList: Boolean): Pair<List<String>, Int> {
            val screen = Screen(rows = 24, bottom = bottom)
            (1..40).forEach { say(screen, "line $it") }
            // chooseFrom's blank line between the command and the list.
            say(screen, "")
            if (withList) pick(screen, workspaces) { s, h -> fixed(s, h, bottom) }
            return screen.text() to screen.row
        }
        val (without, rowWithout) = foot(withList = false)
        val (with, rowWith) = foot(withList = true)
        assertEquals(rowWithout, rowWith, "the cursor is back on the row above the bar")
        val pushed = Picker.scrolledBy(bottom, workspaces.size + 1, bottom)
        assertEquals(without.drop(pushed), with.drop(pushed), "every row the list did not push into the scrollback is where it was")
    }

    @Test
    fun `scrolled by counts only the rows past the foot`() {
        assertEquals(4, Picker.scrolledBy(startRow = 22, height = 5, bottom = 22), "at the foot: all but the first row")
        assertEquals(0, Picker.scrolledBy(startRow = 3, height = 5, bottom = 22), "near the top: none")
        assertEquals(2, Picker.scrolledBy(startRow = 20, height = 5, bottom = 22), "partly past the foot")
    }
}
