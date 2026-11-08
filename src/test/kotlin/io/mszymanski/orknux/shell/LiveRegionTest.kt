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
        assertTrue(ticks.contains("\r\u001b[2K"), "each frame clears its row from the line start")
        assertTrue(ticks.contains("Thinking · 3s"), ticks)
        region.stop()
        assertTrue(screen.written.endsWith(LiveRegion.SHOW_CURSOR))
    }

    @Test
    fun `a session followed shows its own phase in place of this chat's turn, and nothing once it has finished`() {
        val screen = Screen()
        var now = 0L
        val region = LiveRegion(screen::sink, { 80 }, ansi = true, theme = theme, clock = { now })
        region.phase("thinking", "Thinking")
        region.start { listOf("› type to queue") }
        region.focused = true
        region.focusPhase("call:3", "Running http_get: https://www.iana.org", since = -4_000)
        val lines = region.lines().map { stripAnsi(it) }
        assertTrue(lines.any { it.contains("Running http_get: https://www.iana.org · 4s") }, lines.toString())
        assertFalse(lines.any { it.contains("Thinking") }, "this chat's own phase is not drawn while another is followed: $lines")
        assertTrue(lines.last().contains("type to queue"))

        region.focusPhase(null, null)
        val finished = region.lines().map { stripAnsi(it) }
        assertFalse(finished.any { it.contains("Running") || it.contains("Thinking") }, "a finished session draws no phase: $finished")

        region.focused = false
        assertTrue(region.lines().map { stripAnsi(it) }.any { it.contains("Thinking") }, "back on this chat, its own phase again")
        region.stop()
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
        // The thinking's header - which carries the spinner, so "Thinking" is said once - two lines of it, and the
        // status: four rows, so three up.
        assertTrue(frame.contains("\r\u001b[3A" + "\u001b[2K\u001b[B".repeat(3) + "\u001b[2K\u001b[3A"), frame)
        assertEquals(1, Regex("Thinking").findAll(frame).count(), frame)
        // Never to the end of the screen: the status bar is pinned below.
        assertFalse(frame.contains("\u001b[J"), frame)
        assertEquals(3, frame.split("\r\n").size - 1)
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
        val lines = Regex("\u001b\\[2K(?:\u001b\\[\\d+A)?(transcript line number \\d+ of the answer)\r\n").findAll(text).map { it.groupValues[1] }.toList()
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

    @Test
    fun `a blank row above the spinner where the last line written is not blank, and none after a blank one`() {
        val out = StringBuilder()
        val region = LiveRegion({ out.append(it) }, { 80 }, true, Theme(depth = ColourDepth.NONE))
        region.start { emptyList() }
        region.phase("thinking", "Thinking")
        assertEquals(1, region.lines().size, "a turn starts after the blank line under the message")
        region.line("▸ Thought for 1.0s")
        assertEquals(listOf("", "Thinking"), region.lines().map { stripAnsi(it).substringBefore(" ·") }.map { it.removePrefix("⠋ ").removePrefix("⠙ ") }.let { listOf(it[0], it[1].substringAfter(' ')) })
        region.line("")
        assertEquals(1, region.lines().size)
        region.stop()
    }
}
