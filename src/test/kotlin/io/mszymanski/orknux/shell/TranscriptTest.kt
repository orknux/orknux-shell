package io.mszymanski.orknux.shell

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptTest {
    @Test
    fun `each view shows its own lines and the shared ones`() {
        val t = Transcript()
        t.add("› hi")
        t.add("")
        t.add("▸ Thought for 2s", View.FOLDED)
        t.add("▾ Thought for 2s", View.FULL)
        t.add("  │ why", View.FULL)
        t.add("")
        t.add("ok")
        assertEquals(listOf("› hi", "", "▸ Thought for 2s", "", "ok"), t.lines(full = false))
        assertEquals(listOf("› hi", "", "▾ Thought for 2s", "  │ why", "", "ok"), t.lines(full = true))
    }

    @Test
    fun `a view that hides a piece leaves one blank where it was, not two`() {
        val t = Transcript()
        t.add("a")
        t.add("")
        t.add("detail", View.FULL)
        t.add("")
        t.add("b")
        assertEquals(listOf("a", "", "b"), t.lines(full = false))
    }

    @Test
    fun `the oldest lines go past the cap`() {
        val t = Transcript(cap = 3)
        listOf("1", "2", "3", "4").forEach(t::add)
        assertEquals(listOf("2", "3", "4"), t.lines(full = false))
    }
}
