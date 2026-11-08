package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderingTest {
    private val plain = Theme(depth = ColourDepth.NONE)
    private val colour = Theme(depth = ColourDepth.TRUE)

    @Test
    fun `markdown without colour reads as plain text`() {
        val md = Markdown(plain)
        val out = md.render("# Title\n\nSome **bold** and `code`.\n- one\n- two\n1. first\n> quoted\n```kotlin\nval x = 1\n```\n[site](https://x.example)")
        assertEquals(
            listOf("Title", "", "Some bold and code.", "  • one", "  • two", "  1. first", "│ quoted", "  kotlin", "  val x = 1", "", "site (https://x.example)"),
            out.removeSuffix("\n").split("\n"),
        )
    }

    @Test
    fun `markdown in colour is bold, coloured and code-marked`() {
        val md = Markdown(colour)
        val line = md.inline("a **b** `c` *d*")
        assertTrue(line.contains("\u001b[1mb\u001b[22m"), line)
        assertTrue(line.contains("\u001b[38;2;192;132;252mc"), line)
        assertTrue(line.contains("\u001b[3md\u001b[23m"), line)
        assertEquals("a b c d", stripAnsi(line))
    }

    @Test
    fun `stars inside code stay stars, and a lone star is not emphasis`() {
        assertEquals("2 * 3 = x*y", stripAnsi(Markdown(colour).inline("2 * 3 = `x*y`")))
    }

    @Test
    fun `streamed markdown gives back only whole lines until it is finished`() {
        val md = Markdown(plain)
        assertEquals("", md.feed("Hello **wor"))
        assertEquals("Hello world\n", md.feed("ld**\nand"))
        assertEquals("and more\n", md.feed(" more") + md.finish())
    }

    @Test
    fun `a fence opened in one piece and closed in another is still code`() {
        val md = Markdown(plain)
        val out = md.feed("```\n# not a heading\n") + md.feed("```\n# heading\n") + md.finish()
        assertEquals(listOf("", "  # not a heading", "", "heading"), out.removeSuffix("\n").split("\n"))
    }

    @Test
    fun `a turn is drawn as thinking, a tool line, its result and the answer`() {
        val lines = mutableListOf<String>()
        val indicators = mutableListOf<String?>()
        val renderer = TurnRenderer(plain, object : TurnOutput {
            override fun line(text: String) { lines += text }
            override fun phase(key: String, text: String) { indicators += text }
        })
        renderer.handle(ChatEvent.Thinking("Let me look"))
        renderer.handle(ChatEvent.Writing("terminal_run", 12))
        renderer.handle(ChatEvent.Call(0, "terminal_run", """{"command":"df -h","dangerous":false}"""))
        renderer.handle(ChatEvent.Called(0, """{"exitCode":0,"stdout":"Filesystem 1G"}""", false))
        renderer.handle(ChatEvent.Chunk("The disk is **fine**."))
        renderer.handle(ChatEvent.Answered)
        assertTrue(lines[0].startsWith("▸ Thought for"), lines[0])
        assertTrue(lines[0].endsWith("(Ctrl+O to read)"), lines[0])
        assertTrue(Regex("""✓ terminal_run df -h · \d+\.\ds · exit 0 · Filesystem 1G""").matches(lines[1]), lines[1])
        assertEquals("The disk is fine.", lines[2])
        assertEquals(3, lines.size)
        assertTrue(indicators.any { it == "Preparing a tool call: terminal_run · 12 chars" }, indicators.toString())
        assertTrue(indicators.any { it?.startsWith("Running terminal_run: df -h") == true }, indicators.toString())
        assertTrue(renderer.answered)
    }

    @Test
    fun `summaries are one line and clipped`() {
        assertEquals("a=x, n=2", TurnRenderer.argumentSummary("""{"a":"x","n":2}""", 80))
        assertEquals("ls -la", TurnRenderer.argumentSummary("""{"command":"ls   -la","cwd":"/"}""", 80))
        assertEquals("abcdefg…", TurnRenderer.clip("abcdefghijk", 8))
        assertEquals("first (+2 lines)", TurnRenderer.resultSummary("first\nsecond\nthird", 80))
        assertEquals("1.5k", TurnRenderer.tokens(1500))
        assertEquals("$0.0012", TurnRenderer.cost(0.0012))
        assertEquals("–", TurnRenderer.cost(null))
    }

    @Test
    fun `the approval box shows the command, where, why and the dangerous marker`() {
        val box = ApprovalBox.lines(
            plain, RunRequest("rm -rf build", "/work", null, dangerous = true, reason = "Clean the build"),
            Decision(Verdict.ASK, "The agent marked it dangerous."), "/work", 80,
        )
        val text = box.joinToString("\n")
        assertTrue(text.contains("$ rm -rf build"))
        assertTrue(text.contains("in /work"))
        assertTrue(text.contains("why Clean the build"))
        assertTrue(text.contains("▲ dangerous"))
        assertTrue(box.drop(1).dropLast(1).all { visibleLength(it) == visibleLength(box[1]) }, "rows are one width")
        assertTrue(ApprovalBox.choices(plain, ApprovalChoice.PATTERN).contains("› Approve for pattern (p)"))
    }
}
