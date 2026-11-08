package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoalTest {
    @Test
    fun `a goal is what, until when, and optionally how often and judged by whom`() {
        assertEquals(GoalRequest("make the tests pass", "all of them are green", null, null), Goals.parse("make the tests pass until all of them are green"))
        assertEquals(GoalRequest("fix it", "it builds", 5, null), Goals.parse("fix it until it builds max 5"))
        assertEquals(GoalRequest("fix it", "it builds", 5, "Qwen3 32B"), Goals.parse("fix it until it builds max 5 reviewer Qwen3 32B"))
        assertEquals(GoalRequest("fix it", "it builds", 3, "gpt-5"), Goals.parse("fix it until it builds reviewer gpt-5 max 3"))
        assertEquals(GoalRequest("fix it", "it builds", null, "gpt-5"), Goals.parse("fix it UNTIL it builds reviewer gpt-5"))
        assertFailsWith<IllegalArgumentException> { Goals.parse("fix it") }
        assertFailsWith<IllegalArgumentException> { Goals.parse("fix it until it builds max 0") }
    }

    @Test
    fun `a server without goals says so in words`() {
        assertTrue(GoalApi.notOffered("Validation error (FieldUndefined@[startChatGoal]) : Field 'startChatGoal' in type 'Mutation' is undefined"))
        assertTrue(!GoalApi.notOffered("No chat with id 7"))
    }

    @Test
    fun `each iteration leaves its verdict`() {
        val theme = Theme(depth = ColourDepth.NONE)
        assertEquals("◆ Goal · iteration 2 of 5 · not met yet - Two tests still fail", Goals.verdictLine(GoalState("g", "c", 2, 5, "RUNNING", "Two tests\nstill fail"), theme))
        assertEquals("◆ Goal · iteration 3 of 5 · met", Goals.verdictLine(GoalState("g", "c", 3, 5, "MET", null), theme))
        assertEquals("3/5", GoalState("g", "c", 3, 5, "RUNNING", null).progress)
    }
}
