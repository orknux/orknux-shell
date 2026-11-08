package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompletionsTest {
    private val sources = object : CompletionSources {
        override fun agents() = listOf("Builder", "Research bot", "reviewer")
        override fun workspaces() = listOf("Home", "Ops")
        override fun skills() = listOf("code-review", "deploy")
        override fun marker() = "!"
        override fun models() = listOf("gpt-5", "Qwen3 32B")
        override fun chats() = listOf("Disk usage", "Deploy plan")
        override fun refs() = listOf("main", "feature/x")
        override fun attachments() = 2
        override fun links() = 3
        override fun files(prefix: String) = listOf("src/", "src.txt").filter { it.startsWith(prefix) }
    }
    private val completions = Completions(sources)
    private fun values(line: String) = completions.complete(line).map { it.value }

    @Test
    fun `slash alone lists every command, and a part completes it`() {
        // The Shell's commands, then the skills, typed with a slash too.
        assertEquals(SlashCommands.ALL.map { "/" + it.name } + listOf("/code-review", "/deploy"), values("/"))
        assertEquals(listOf("/deploy"), values("/dep"))
        assertEquals(listOf("/review", "/resume", "/rules"), values("/r"))
        assertEquals(listOf("/review", "/resume", "/rules"), values("/R"))
        assertEquals(listOf("/goal"), values("/go"))
    }

    @Test
    fun `arguments complete per command, case-insensitively`() {
        assertEquals(listOf("Builder"), values("/agent b"))
        assertEquals(listOf("Research bot", "reviewer"), values("/agent r"))
        assertEquals(listOf("Ops"), values("/workspace o"))
        assertEquals(listOf("code-review"), values("/skill c"))
        assertEquals(emptyList(), values("/skill code-review an"))
        assertEquals(listOf("ask"), values("/mode as"))
        assertEquals(listOf("collapsed"), values("/thinking c"))
        assertEquals(listOf("default"), values("/effort d"))
        assertEquals(listOf("1", "2", "all"), values("/detach "))
        assertEquals(listOf("1", "2", "3"), values("/open "))
        assertEquals(listOf("Deploy plan", "Disk usage").sorted(), values("/resume D").sorted())
        assertEquals(listOf("src/", "src.txt"), values("/attach sr"))
        assertEquals(listOf("--fix", "main", "feature/x"), values("/review "))
        assertEquals(listOf("main", "feature/x"), values("/review --fix "))
    }

    @Test
    fun `at an agent, and a skill after the marker anywhere in a message`() {
        assertEquals(listOf("@Research bot", "@reviewer"), values("@r"))
        assertEquals(listOf("!deploy"), values("please !de"))
        assertEquals(emptyList(), values("please de"))
    }

    @Test
    fun `the goal's keywords come in turn, and models after reviewer`() {
        assertEquals(listOf("stop"), values("/goal "))
        assertEquals(listOf("until"), values("/goal make the tests pass u"))
        assertEquals(listOf("max", "reviewer"), values("/goal make it pass until all green "))
        assertEquals(listOf("reviewer"), values("/goal x until y max 5 "))
        assertEquals(listOf("Qwen3 32B"), values("/goal x until y reviewer q"))
        assertTrue(values("/goal x until y max ").isEmpty())
    }

    @Test
    fun `an escaped space stays inside its word`() {
        assertEquals(listOf("/agent", "Research bot"), Completions.words("/agent Research\\ bot"))
        assertEquals(listOf("/agent", ""), Completions.words("/agent "))
    }
}
