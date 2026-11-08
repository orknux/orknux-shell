package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PickerTest {
    private val theme = Theme(depth = ColourDepth.NONE)

    /** A picker fed [keys] as typed, which records what it drew. */
    private fun picker(keys: String, out: StringBuilder = StringBuilder(), rows: Int = 30): Picker {
        val queue = ArrayDeque(keys.map { it.code })
        return Picker({ out.append(it) }, { timeout -> queue.removeFirstOrNull() ?: if (timeout < 0) -1 else -2 }, theme, { rows }, { 100 })
    }

    private val names = listOf("AzureAgent", "Browser helper", "Desktop helper", "Shell helper")

    @Test
    fun `arrows move the highlight and Enter takes it`() {
        assertEquals(2, picker("\u001b[B\u001b[B\r").choose("Agent", names))
        assertEquals(0, picker("\u001b[A\u001b[A\r").choose("Agent", names, current = 1))
        assertEquals(3, picker("jjjjjj\r").choose("Agent", names), "it stops at the last row")
        assertEquals(1, picker("\r").choose("Agent", names, current = 1), "it starts on the current one")
    }

    @Test
    fun `a number takes that row, and Esc, q or Ctrl+C leave without one`() {
        assertEquals(2, picker("3").choose("Agent", names))
        assertNull(picker("\u001b").choose("Agent", names))
        assertNull(picker("q").choose("Agent", names))
        assertNull(picker("\u0003").choose("Agent", names))
    }

    @Test
    fun `the list is drawn with the highlight, and taken away when done`() {
        val out = StringBuilder()
        val p = picker("\u001b[B\r", out)
        p.choose("Agent", names)
        val text = stripAnsi(out.toString())
        assertTrue(text.contains("› 2. Browser helper"), text)
        assertEquals(5, p.lastHeight, "the title and four rows")
        assertTrue(out.endsWith(LiveRegion.SHOW_CURSOR), "the cursor comes back")
        assertTrue(out.toString().contains(LiveRegion.CLEAR_LINE))
    }

    @Test
    fun `a long list shows a window that follows the highlight`() {
        val many = (1..40).map { "chat $it" }
        val out = StringBuilder()
        assertEquals(29, picker("\u001b[6~\u001b[6~\r", out, rows = 16).choose("Chats", many, current = 9))
        val last = stripAnsi(out.toString()).substringAfterLast("Chats")
        assertTrue(last.contains("› 30. chat 30"), last)
        assertFalse(last.contains(" 1. chat 1\r"), "rows above the window are not drawn")
    }

    @Test
    fun `one choice is a list of one, and two are a list of two`() {
        assertEquals(0, picker("\r").choose("Workspaces", listOf("Private")))
        val p = picker("\r")
        assertEquals(1, p.choose("Workspaces", listOf("Private", "Team"), current = 1))
        assertEquals(3, p.lastHeight, "the title and two rows")
    }

    @Test
    fun `moving the highlight rewrites the rows in place, and never blanks the list between keys`() {
        val out = StringBuilder()
        picker("\u001b[B\r", out).choose("Agent", names)
        val second = out.toString().split(LiveRegion.SYNC_START)[2].substringBefore(LiveRegion.SYNC_END)
        assertTrue(second.startsWith("\r\u001b[4A"), "back to the title row first")
        assertEquals(5, second.split(LiveRegion.CLEAR_LINE).size - 1, "each row cleared just before it is written")
        assertFalse(second.contains("\u001b[B"), "not a pass that clears every row before drawing any")
    }

    @Test
    fun `an unknown slash word is caught, a path is not`() {
        assertEquals("agnet", SlashCommands.unknown("/agnet"))
        assertEquals("agent", SlashCommands.nearest("agnet"))
        assertNull(SlashCommands.unknown("/etc/hosts is broken"))
        assertNull(SlashCommands.unknown("/agents"), "an alias is a command")
        assertEquals("agent", SlashCommands.parse("/agents")?.name)
    }
}
