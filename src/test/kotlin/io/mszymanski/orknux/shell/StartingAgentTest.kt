package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which agent the Shell starts on, named before anything is said.
 *
 * It used to leave the choice to the server and print "agent" until the first
 * answer said otherwise, which read as an agent called "agent". It now works out
 * what the server would choose - the agent of the newest chat - so the welcome
 * and the status bar name it from the start.
 */
class StartingAgentTest {

    private fun agent(id: String, name: String) = Agent(id, name, null, true, emptyList(), emptyList())

    private fun chat(agentId: String?, at: String) =
        Chat("c$at", "1", "title", agentId, null, at, 0, 0, 0, "auto")

    private val builder = agent("1", "Builder")
    private val researcher = agent("2", "Researcher")
    private val agents = listOf(builder, researcher)

    @Test
    fun `with nothing remembered, the agent of the newest chat, under its own name`() {
        val chats = listOf(
            chat("1", "2026-11-01T10:00:00Z"),
            chat("2", "2026-11-08T10:00:00Z"),
        )
        val chosen = Connection.agent(agents, Config(), null) { chats }
        assertEquals("Researcher", chosen?.name)
    }

    @Test
    fun `the agent remembered from the last run comes first`() {
        val chosen = Connection.agent(agents, Config(agentId = "1"), null) { listOf(chat("2", "2026-11-08T10:00:00Z")) }
        assertEquals("Builder", chosen?.name)
    }

    @Test
    fun `a remembered agent that is gone falls to the newest chat's`() {
        val chosen = Connection.agent(agents, Config(agentId = "99"), null) { listOf(chat("2", "2026-11-08T10:00:00Z")) }
        assertEquals("Researcher", chosen?.name)
    }

    @Test
    fun `a chat whose agent is gone or switched off is passed over`() {
        val chats = listOf(chat("1", "2026-11-01T10:00:00Z"), chat("42", "2026-11-08T10:00:00Z"))
        assertEquals("Builder", Connection.lastTalkedTo(agents, chats)?.name)
    }

    @Test
    fun `a newest chat with no agent - My Claude Code - leaves the choice to the server`() {
        val chats = listOf(chat("1", "2026-11-01T10:00:00Z"), chat(null, "2026-11-08T10:00:00Z"))
        assertNull(Connection.lastTalkedTo(agents, chats))
    }

    @Test
    fun `no chats, or chats that cannot be read, leave it to the server`() {
        assertNull(Connection.agent(agents, Config(), null) { emptyList() })
        assertNull(Connection.agent(agents, Config(), null) { throw OrknuxException("offline") })
    }
}
