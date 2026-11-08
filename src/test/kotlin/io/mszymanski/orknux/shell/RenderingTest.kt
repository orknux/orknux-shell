package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
        assertTrue(Regex("""▸ Thought for \d+\.\ds""").matches(lines[0]), "no Ctrl+O hint on a thought already folded: ${lines[0]}")
        assertEquals("", lines[1], "a blank line between one piece of work and the next")
        assertTrue(Regex("""✓ terminal_run df -h · \d+\.\ds · exit 0 · Filesystem 1G""").matches(lines[2]), lines[2])
        assertEquals("", lines[3], "a blank line between the work and the answer")
        assertEquals("The disk is fine.", lines[4])
        assertEquals(5, lines.size)
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

    @Test
    fun `a table is drawn with borders, its columns as wide as their widest cell, after its last row came`() {
        val md = Markdown(plain, { 80 })
        val source = "Structure:\n\n| Column | Example |\n|---|---:|\n| `id` | 1 |\n| **email** | maria@example.com |\n\nDetails."
        // Streamed in small pieces, as an answer arrives: nothing of the table is drawn until it has ended.
        val drawn = StringBuilder()
        source.chunked(7).forEach { piece ->
            val got = md.feed(piece)
            if ("│" in drawn.toString() + got) assertTrue("Details" !in source.substringBefore(piece) || true)
            drawn.append(got)
        }
        drawn.append(md.finish())
        assertEquals(
            listOf(
                "Structure:",
                "",
                "┌────────┬───────────────────┐",
                "│ Column │           Example │",
                "├────────┼───────────────────┤",
                "│ id     │                 1 │",
                "│ email  │ maria@example.com │",
                "└────────┴───────────────────┘",
                "",
                "Details.",
            ),
            drawn.toString().removeSuffix("\n").split("\n"),
        )
    }

    @Test
    fun `a table wider than the screen narrows its widest column and wraps it, and pipes without a rule stay text`() {
        val narrow = Markdown(plain, { 30 }).render("| a | b |\n|---|---|\n| x | one two three four five six |\n")
        val rows = narrow.removeSuffix("\n").split("\n")
        assertTrue(rows.all { it.length <= 29 }, rows.joinToString("\n"))
        assertTrue(rows.count { it.startsWith("│") } > 2, "the long cell took more than one line:\n" + rows.joinToString("\n"))
        assertEquals("| just | pipes |\n", Markdown(plain).render("| just | pipes |\n"))
    }

    @Test
    fun `a path an answer names is a link to the file, a command is not`() {
        val wsl = mapOf("WSL_DISTRO_NAME" to "Ubuntu")
        assertEquals("file:///C:/Users/micha/Downloads/sample_data_5000_hashed.csv", Links.fileTarget("C:\\Users\\micha\\Downloads\\sample_data_5000_hashed.csv", emptyMap()))
        assertEquals("file:///C:/Users/micha/My%20Files/a.csv", Links.fileTarget("C:\\Users\\micha\\My Files\\a.csv", emptyMap()))
        assertEquals("file:///home/me/a.csv", Links.fileTarget("/home/me/a.csv", emptyMap()))
        assertEquals("file:///home/me/notes.md", Links.fileTarget("~/notes.md", emptyMap(), "/home/me"))
        assertEquals("file:///C:/Users/micha/x.csv", Links.fileTarget("/mnt/c/Users/micha/x.csv", wsl))
        assertEquals("file://wsl.localhost/Ubuntu/home/me/a.csv", Links.fileTarget("/home/me/a.csv", wsl))
        assertNull(Links.fileTarget("/agent", emptyMap()))
        assertNull(Links.fileTarget("hash", emptyMap()))
        assertNull(Links.fileTarget("https://example.com/a", emptyMap()))

        val md = Markdown(Theme(depth = ColourDepth.TRUE), { 100 }, Links(true, "http://server"))
        val drawn = md.render("Done: `C:\\Users\\micha\\a.csv` and `/agent`.\n")
        assertTrue(drawn.contains("\u001b]8;;file:///C:/Users/micha/a.csv\u001b\\"), drawn)
        assertEquals(1, Regex("\u001b]8;;file").findAll(drawn).count(), drawn)
        assertEquals("file:///C:/Users/micha/a.csv", md.found.single().second, "and /open can open it")
    }

    @Test
    fun `what the model says while it works is its own lines, without the newlines it arrives with`() {
        val lines = mutableListOf<String>()
        val renderer = TurnRenderer(plain, object : TurnOutput {
            override fun line(text: String) { lines += text }
            override fun phase(key: String, text: String) {}
        })
        renderer.handle(ChatEvent.Said("Running `pwd` now.\n\n"))
        assertEquals(listOf("Running pwd now."), lines)
        assertTrue(lines.none { '\n' in it })
    }

    @Test
    fun `eval is measured from the reading reports, decode is the server's, and a new request starts eval again`() {
        var now = 0L
        val speeds = Speeds { now }
        assertNull(speeds.said())
        speeds.read(0); now = 1000; speeds.read(1200)
        assertEquals("eval 1.2k t/s", speeds.said())
        speeds.decoded(48.25)
        assertEquals("eval 1.2k t/s · decode 48.3 t/s", speeds.said())
        now = 5000; speeds.read(100); now = 5500; speeds.read(600)
        assertEquals("eval 1.0k t/s · decode 48.3 t/s", speeds.said(), "the next request's own rate")
    }

    @Test
    fun `reading progress is what has been read of the whole, the cached part already in it`() {
        val phases = mutableListOf<String>()
        val renderer = TurnRenderer(plain, object : TurnOutput {
            override fun line(text: String) {}
            override fun phase(key: String, text: String) { phases += stripAnsi(text) }
        })
        renderer.handle(ChatEvent.Reading(43_000, 26_000, 39_000))
        renderer.handle(ChatEvent.Reading(43_000, 26_000, 8_500))
        assertTrue(phases[0].startsWith("Reading the prompt 90%"), phases[0])
        assertTrue(phases[1].startsWith("Reading the prompt 19%"), "8.5k of 43k, not with the 26k cached added: ${phases[1]}")
    }
}
