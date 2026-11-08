package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The live region against a fake terminal: a string the frames are written into. */
class LiveRegionTest {
    private val theme = Theme(depth = ColourDepth.NONE)

    private class Screen {
        val written = StringBuilder()
        val writes = mutableListOf<String>()
        fun sink(text: String) = synchronized(this) {
            written.append(text)
            writes += text
        }
    }

    @Test
    fun `frames are drawn in place - no newline per frame`() {
        val screen = Screen()
        var now = 0L
        val region = LiveRegion(screen::sink, { 80 }, ansi = true, theme = theme, clock = { now })
        region.phase("thinking", "Thinking")
        region.start { emptyList() }
        val before = screen.writes.size
        repeat(30) {
            now += 100
            region.tick()
        }
        val ticks = screen.writes.drop(before).joinToString("")
        assertFalse(ticks.contains('\n'), "a tick wrote a newline")
        assertTrue(ticks.contains("\r\u001b[J"), "each frame clears from the line start")
        assertTrue(ticks.contains("Thinking · 3s"), ticks)
        region.stop()
        assertTrue(screen.written.endsWith(LiveRegion.SHOW_CURSOR))
    }

    @Test
    fun `a region of several rows climbs back over all of them`() {
        val screen = Screen()
        val region = LiveRegion(screen::sink, { 80 }, ansi = true, theme = theme, clock = { 0 })
        region.phase("thinking", "Thinking")
        region.start { listOf("status") }
        region.thinking("one\ntwo")
        screen.writes.clear()
        region.tick()
        val frame = screen.writes.joinToString("")
        // Thinking header, two lines of it, the spinner and the status: five rows, so four up.
        assertTrue(frame.contains("\r\u001b[4A\u001b[J"), frame)
        assertEquals(4, frame.split("\r\n").size - 1)
    }

    @Test
    fun `phases switch at once and their clock starts again`() {
        val screen = Screen()
        var now = 0L
        val region = LiveRegion(screen::sink, { 80 }, ansi = true, theme = theme, clock = { now })
        region.phase("thinking", "Thinking")
        region.start { emptyList() }
        now = 5_000
        region.tick()
        assertTrue(region.lines().single().endsWith("Thinking · 5s"))
        region.phase("writing:terminal_run", "Preparing a tool call: terminal_run · 12 chars")
        assertTrue(screen.writes.last().contains("Preparing a tool call: terminal_run · 12 chars · 0s") || screen.writes.joinToString("").contains("12 chars · 0s"))
        now = 7_000
        region.phase("writing:terminal_run", "Preparing a tool call: terminal_run · 312 chars")
        assertTrue(region.lines().single().endsWith("312 chars · 2s"), region.lines().single())
        region.halt("Interrupted")
        assertEquals("■ Interrupted", region.lines().single())
    }

    @Test
    fun `off a terminal it writes plain lines and no escape at all`() {
        val screen = Screen()
        val region = LiveRegion(screen::sink, { 80 }, ansi = false, theme = Theme(depth = ColourDepth.TRUE))
        region.phase("thinking", "Thinking")
        region.start { listOf("status") }
        region.tick()
        region.line(Theme(depth = ColourDepth.TRUE).accent("an answer"))
        region.line(Links.hyperlink("https://x.example", "a link"))
        region.stop()
        assertEquals("an answer\na link\n", screen.written.toString())
        assertFalse(screen.written.contains('\u001b'))
    }

    @Test
    fun `text and ticks from two threads never split a line`() {
        val screen = Screen()
        val region = LiveRegion(screen::sink, { 80 }, ansi = true, theme = theme)
        region.phase("thinking", "Thinking")
        region.start { listOf("status line") }
        val go = CountDownLatch(1)
        val ticker = Thread { go.await(); repeat(2_000) { region.tick() } }
        val writer = Thread { go.await(); repeat(500) { region.line("transcript line number $it of the answer") } }
        ticker.start(); writer.start(); go.countDown(); ticker.join(); writer.join()
        region.stop()
        // Replay the escapes: every transcript line must arrive whole, between its own clear and its own CRLF.
        val text = screen.written.toString()
        val lines = Regex("\u001b\\[J(transcript line number \\d+ of the answer)\r\n").findAll(text).map { it.groupValues[1] }.toList()
        assertEquals((0 until 500).map { "transcript line number $it of the answer" }, lines)
        assertFalse(Regex("transcript line number \\d+ of[^\r]*⠋").containsMatchIn(text))
    }

    @Test
    fun `a line wider than the terminal is clipped, its escapes kept`() {
        val clipped = LiveRegion.clip("\u001b[1mabcdefghij\u001b[22m", 6)
        assertEquals("abcde…", stripAnsi(clipped))
        assertTrue(clipped.startsWith("\u001b[1m"))
    }

    @Test
    fun `braille where the terminal speaks UTF-8, bars on a legacy console`() {
        assertTrue(LiveRegion.unicodeFor("UTF-8", "Linux", emptyMap()))
        assertFalse(LiveRegion.unicodeFor("ISO-8859-1", "Linux", emptyMap()))
        assertFalse(LiveRegion.unicodeFor("UTF-8", "Windows 10", emptyMap()))
        assertTrue(LiveRegion.unicodeFor("UTF-8", "Windows 11", mapOf("WT_SESSION" to "1")))
        assertEquals(listOf("|", "/", "-", "\\"), LiveRegion({}, { 80 }, true, theme, unicode = false).frames)
    }
}
