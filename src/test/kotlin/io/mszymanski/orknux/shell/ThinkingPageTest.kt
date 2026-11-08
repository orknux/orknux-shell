package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThinkingPageTest {
    private fun page(keys: String, lines: List<String>, rows: Int = 6): String {
        val out = StringBuilder()
        val queue = ArrayDeque(keys.map { it.code })
        ThinkingPage(lines, Theme(depth = ColourDepth.NONE), { rows }, { 80 }, { out.append(it) }, { queue.removeFirstOrNull() ?: -1 }).show()
        return out.toString()
    }

    @Test
    fun `the page opens on the alternate screen, scrolls, and leaves the conversation's screen as it was`() {
        val lines = (1..20).map { "line $it" }
        val out = page("jj\u000f", lines)
        assertTrue(out.startsWith(ThinkingPage.ALTERNATE_ON), "on its own screen")
        assertTrue(out.endsWith(ThinkingPage.ALTERNATE_OFF), "and back to the conversation's, untouched")
        val last = stripAnsi(out.split(LiveRegion.SYNC_START).last())
        assertTrue(last.contains("line 3") && !last.contains("line 2\r"), last)
        assertTrue(last.contains("3-6 of 20"), last)
    }

    @Test
    fun `Esc, q and Ctrl+O each close it`() {
        for (key in listOf("\u001b", "q", "\u000f")) {
            val out = page(key, listOf("a"))
            assertEquals(1, out.split(LiveRegion.SYNC_START).size - 1, "one frame, then closed by ${key.map { it.code }}")
            assertFalse(out.contains("Thinking") && !out.endsWith(ThinkingPage.ALTERNATE_OFF))
        }
    }

    @Test
    fun `the turn in full has every thought and every tool call, a command's output as the lines it printed`() {
        val lines = TurnRenderer.detailLines(
            listOf(
                Detail.Thought(ThinkingBlock("Look for csv files.", 1200)),
                Detail.Tool("terminal_run", """{"command":"find . -iname '*.csv'","dangerous":false}""", """{"exitCode":0,"stdout":"./a.csv\n./b.csv\n","stderr":""}""", false, 100),
            ),
            Theme(depth = ColourDepth.NONE), 80,
        ).map { stripAnsi(it).trimEnd() }
        assertTrue(lines.contains("Look for csv files.") || lines.any { it.endsWith("Look for csv files.") }, lines.toString())
        assertTrue(lines.contains("✓ terminal_run · 0.1s"), lines.toString())
        assertTrue(lines.any { it.contains("\"command\" : \"find . -iname '*.csv'\"") }, "the arguments, whole: $lines")
        assertTrue(lines.containsAll(listOf("    exitCode 0", "    ./a.csv", "    ./b.csv")), lines.toString())
    }
}
