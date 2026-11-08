package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParsingTest {

    private fun frames(wire: String): List<SseFrame> {
        val parser = SseParser()
        return (wire.split("\n") + "").mapNotNull { parser.line(it) }
    }

    @Test
    fun `server-sent events are named frames ended by a blank line`() {
        val got = frames("event: chunk\ndata: {\"text\":\"Hel\"}\n\n: keep-alive\n\nevent: done\r\ndata: {\"inputTokens\":12}\r\n\r\n")
        assertEquals(listOf(SseFrame("chunk", "{\"text\":\"Hel\"}"), SseFrame("done", "{\"inputTokens\":12}")), got)
    }

    @Test
    fun `several data lines are one payload`() {
        assertEquals(listOf(SseFrame("message", "a\nb")), frames("data: a\ndata: b\n\n"))
    }

    @Test
    fun `every frame the chat sends becomes an event`() {
        fun of(event: String, data: String) = ChatEvent.of(SseFrame(event, data))
        assertEquals(ChatEvent.Chunk("hi"), of("chunk", """{"text":"hi"}"""))
        assertEquals(ChatEvent.Thinking("hm"), of("thinking", """{"text":"hm"}"""))
        assertEquals(ChatEvent.Thought("hm", 1200), of("thought", """{"text":"hm","millis":1200}"""))
        assertEquals(ChatEvent.Writing("terminal_run", 42), of("writing", """{"tool":"terminal_run","chars":42}"""))
        assertEquals(ChatEvent.Writing(null, 3), of("writing", """{"tool":null,"chars":3}"""))
        assertEquals(ChatEvent.Call(0, "web_search", """{"query":"x"}"""), of("call", """{"at":0,"tool":"web_search","arguments":"{\"query\":\"x\"}"}"""))
        assertEquals(ChatEvent.Called(0, "ok", false), of("called", """{"at":0,"result":"ok","failed":false,"link":null}"""))
        assertEquals(
            ChatEvent.Done(100, 20, 80, 0.0123, 900, null),
            of("done", """{"inputTokens":100,"outputTokens":20,"cachedInputTokens":80,"cost":0.0123,"millis":900}"""),
        )
        assertEquals(ChatEvent.Done(null, null, null, null, 5, null), of("done", """{"millis":5}"""))
        assertEquals(ChatEvent.Answered, of("answered", "{}"))
        assertEquals(ChatEvent.Error("no model"), of("error", """{"reason":"no model"}"""))
        assertEquals(ChatEvent.Compacting, of("compacting", "{}"))
        assertEquals(ChatEvent.Titled("Disk usage"), of("titled", """{"title":"Disk usage"}"""))
        assertEquals(ChatEvent.Session("12"), of("session", """{"id":"12"}"""))
        assertTrue(of("widgets", """{"widgets":[]}""") is ChatEvent.Other)
        assertTrue(of("chunk", "not json") is ChatEvent.Other)
    }

    @Test
    fun `slash commands are taken apart, and a path is not one`() {
        assertEquals(Slash("agent", "Research bot"), SlashCommands.parse("/agent Research bot"))
        assertEquals(Slash("agent", null), SlashCommands.parse("  /agent  "))
        assertEquals(Slash("quit", null), SlashCommands.parse("/exit"))
        assertEquals(Slash("mode", "plan"), SlashCommands.parse("/MODE plan"))
        assertNull(SlashCommands.parse("/etc/hosts is broken"))
        assertNull(SlashCommands.parse("hello /agent"))
        assertNull(SlashCommands.parse("/"))
        assertEquals(listOf("review", "resume", "rules"), SlashCommands.completing("/r").map { it.name })
    }

    @Test
    fun `picking by number, exact name, prefix and substring`() {
        val names = listOf("Builder", "Build checker", "Research")
        assertEquals("Research", SlashCommands.pick(names, "3") { it })
        assertEquals("Builder", SlashCommands.pick(names, "builder") { it })
        assertEquals("Build checker", SlashCommands.pick(names, "build c") { it })
        assertNull(SlashCommands.pick(names, "buil") { it })
        assertEquals("Research", SlashCommands.pick(names, "sear") { it })
        assertNull(SlashCommands.pick(names, "9") { it })
    }

    @Test
    fun `the command line`() {
        assertEquals("version", Cli.parse(arrayOf("--version")).command)
        val login = Cli.parse(arrayOf("login", "https://o.example.com", "--token", "orkx_1"))
        assertEquals("login", login.command)
        assertEquals("https://o.example.com", login.positional)
        assertEquals("orkx_1", login.token)
        val print = Cli.parse(arrayOf("-p", "hello there", "--workspace", "Ops", "--mode", "ask"))
        assertEquals("hello there", print.print)
        assertEquals("Ops", print.workspace)
        assertEquals("ask", print.mode)
    }
}
