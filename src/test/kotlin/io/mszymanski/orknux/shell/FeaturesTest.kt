package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Links, pasted paths, attachments, the thinking chain and `/review`. */
class FeaturesTest {
    private val plain = Theme(depth = ColourDepth.NONE)

    // ---- links -------------------------------------------------------------------------------

    @Test
    fun `links are OSC 8 hyperlinks where the terminal draws them`() {
        val md = Markdown(plain, { 100 }, Links(osc8 = true, server = "https://o.example.com"))
        val line = md.inline("See [the run](/workspaces/1/runs/9) and https://docs.example.com/x.")
        assertEquals(
            "See \u001b]8;;https://o.example.com/workspaces/1/runs/9\u001b\\the run\u001b]8;;\u001b\\ and " +
                "\u001b]8;;https://docs.example.com/x\u001b\\https://docs.example.com/x\u001b]8;;\u001b\\.",
            line,
        )
        assertEquals("See the run and https://docs.example.com/x.", stripAnsi(line))
        assertEquals(listOf("the run" to "https://o.example.com/workspaces/1/runs/9", "https://docs.example.com/x" to "https://docs.example.com/x"), md.found)
    }

    @Test
    fun `plain links write the address out, made absolute`() {
        val md = Markdown(plain, { 100 }, Links(osc8 = false, server = "http://localhost:8080"))
        assertEquals("Open the issue (http://localhost:8080/issues/4) or http://x.example (it)", md.inline("Open [the issue](/issues/4) or http://x.example (it)"))
        assertEquals("(http://a.example/b_(c))", md.inline("(http://a.example/b_(c))"))
        assertEquals("[n](//cdn.example/x) stays", Links(false, "http://h").let { "[n](//cdn.example/x) stays".takeIf { _ -> it.absolute("//cdn.example/x") == "//cdn.example/x" }!! })
    }

    @Test
    fun `whether links are clickable is the setting's, or the terminal's`() {
        assertTrue(Links.osc8For("osc8", ColourDepth.NONE, emptyMap(), "Linux"))
        assertTrue(!Links.osc8For("plain", ColourDepth.TRUE, emptyMap(), "Linux"))
        assertTrue(Links.osc8For("auto", ColourDepth.TRUE, mapOf("TERM" to "xterm-256color"), "Linux"))
        assertTrue(!Links.osc8For("auto", ColourDepth.INDEXED, mapOf("TERM" to "dumb"), "Linux"))
        assertTrue(!Links.osc8For("auto", ColourDepth.INDEXED, mapOf("TERM_PROGRAM" to "Apple_Terminal"), "Mac OS X"))
        assertTrue(Links.osc8For("auto", ColourDepth.TRUE, mapOf("WT_SESSION" to "x"), "Windows 11"))
        assertTrue(!Links.osc8For("auto", ColourDepth.INDEXED, emptyMap(), "Windows 10"))
        assertTrue(!Links.osc8For("auto", ColourDepth.NONE, emptyMap(), "Linux"))
    }

    @Test
    fun `an answer's links are listed at its end, numbered`() {
        val lines = mutableListOf<String>()
        val renderer = TurnRenderer(plain, object : TurnOutput {
            override fun line(text: String) { lines += text }
            override fun phase(key: String, text: String) {}
        }, links = Links(false, "http://h"))
        renderer.handle(ChatEvent.Chunk("Read [this](/a) and [that](https://b.example)."))
        renderer.handle(ChatEvent.Answered)
        assertEquals(listOf("Links (/open <n>):", "  1. this http://h/a", "  2. that https://b.example"), renderer.linkList())
    }

    // ---- pasted paths ------------------------------------------------------------------------

    @Test
    fun `a pasted path is found bare, quoted, escaped, as a file address and on Windows`() {
        val files = setOf("/home/a/report.pdf", "/home/a/my notes.txt", "C:\\Users\\a\\My Report.pdf", "/tmp/a.png", "/tmp/b.png", "\\\\server\\share\\x.txt")
        // What a file address means is the system's: \home\a\my notes.txt on Windows.
        val fromAddress = Path.of(java.net.URI("file:///home/a/my%20notes.txt")).toString()
        val exists = { p: String -> p in files || p == fromAddress }
        assertEquals(listOf("/home/a/report.pdf"), PastedPaths.detect("/home/a/report.pdf", "/home/a", exists))
        assertEquals(listOf("/home/a/report.pdf"), PastedPaths.detect("  ~/report.pdf ", "/home/a", exists))
        assertEquals(listOf("/home/a/my notes.txt"), PastedPaths.detect("'/home/a/my notes.txt'", "/home/a", exists))
        assertEquals(listOf("/home/a/my notes.txt"), PastedPaths.detect("\"/home/a/my notes.txt\"", "/home/a", exists))
        assertEquals(listOf("/home/a/my notes.txt"), PastedPaths.detect("/home/a/my\\ notes.txt", "/home/a", exists))
        assertEquals(listOf("/home/a/my notes.txt"), PastedPaths.detect("/home/a/my notes.txt", "/home/a", exists))
        assertEquals(listOf(fromAddress), PastedPaths.detect("file:///home/a/my%20notes.txt", "/home/a", exists))
        assertEquals(listOf("C:\\Users\\a\\My Report.pdf"), PastedPaths.detect("C:\\Users\\a\\My Report.pdf", "/home/a", exists))
        assertEquals(listOf("C:\\Users\\a\\My Report.pdf"), PastedPaths.detect("\"C:\\Users\\a\\My Report.pdf\"", "/home/a", exists))
        assertEquals(listOf("\\\\server\\share\\x.txt"), PastedPaths.detect("\\\\server\\share\\x.txt", "/home/a", exists))
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), PastedPaths.detect("/tmp/a.png /tmp/b.png", "/home/a", exists))
    }

    @Test
    fun `a sentence, a missing file or one path among words is not an attachment`() {
        val exists = { p: String -> p == "/tmp/a.png" }
        assertNull(PastedPaths.detect("what is in /tmp/a.png", "/h", exists))
        assertNull(PastedPaths.detect("/tmp/missing.png", "/h", exists))
        assertNull(PastedPaths.detect("/tmp/a.png and more", "/h", exists))
        assertNull(PastedPaths.detect("hello", "/h") { true })
        assertNull(PastedPaths.detect("'/tmp/a.png", "/h", exists))
    }

    @Test
    fun `an upload is one multipart file per request, and a refusal is said in words`(@TempDir dir: Path) {
        StubOrknux(emptyList()).use { stub ->
            val file = dir.resolve("report one.pdf")
            Files.write(file, ByteArray(1500) { (it % 251).toByte() })
            val client = OrknuxClient(stub.url, cookies = mapOf("SESSION" to "s3cr3t"))
            val kept = Api(client).upload("1", file)
            assertEquals(Attachment(41, "report one.pdf", "application/pdf", 1500), kept)
            val upload = stub.uploads.single()
            assertTrue(upload.contentType.startsWith("multipart/form-data; boundary="), upload.contentType)
            assertTrue(upload.body.contains("Content-Disposition: form-data; name=\"files\"; filename=\"report one.pdf\"\r\nContent-Type: application/pdf\r\n\r\n"))
            assertEquals("/api/workspaces/1/attachments", upload.path)
            assertEquals(stub.url, upload.origin)
            assertEquals(1500, upload.fileBytes)

            val big = dir.resolve("huge.bin")
            Files.write(big, ByteArray(10) { 1 })
            stub.refuseUploads = true
            val refused = assertFailsWith<OrknuxException> { Api(client).upload("1", big) }
            assertEquals("huge.bin is 30 MB, and files here may be at most 20 MB.", refused.message)
        }
    }

    // ---- thinking ----------------------------------------------------------------------------

    private class Recorder : TurnOutput {
        val lines = mutableListOf<String>()
        val live = mutableListOf<String?>()
        override fun line(text: String) { lines += text }
        override fun phase(key: String, text: String) {}
        override fun thinking(text: String?) { live += text }
    }

    private fun stream(wire: String, renderer: TurnRenderer) {
        val parser = SseParser()
        (wire.split("\n") + "").forEach { line -> parser.line(line)?.let { renderer.handle(ChatEvent.of(it)) } }
    }

    @Test
    fun `thinking streams live, then folds, and think-call-think-answer is two blocks in order`() {
        val out = Recorder()
        val renderer = TurnRenderer(plain, out)
        stream(
            """
            event: thinking
            data: {"text":"First I "}

            event: thinking
            data: {"text":"look."}

            event: thought
            data: {"text":"First I look.","millis":2000}

            event: call
            data: {"at":0,"tool":"terminal_run","arguments":"{\"command\":\"ls\"}"}

            event: called
            data: {"at":0,"result":"{\"exitCode\":0,\"stdout\":\"a\"}","failed":false}

            event: thinking
            data: {"text":"Now I know."}

            event: chunk
            data: {"text":"Done."}

            event: answered
            data: {}
            """.trimIndent(),
            renderer,
        )
        assertEquals(listOf("First I ", "First I look.", null, "Now I know.", null), out.live)
        assertEquals(2, renderer.blocks.size)
        assertEquals(ThinkingBlock("First I look.", 2000), renderer.blocks[0])
        assertEquals("Now I know.", renderer.blocks[1].text)
        assertEquals("▸ Thought for 2.0s  (Ctrl+O to read)", out.lines[0])
        assertTrue(Regex("""✓ terminal_run ls · \d+\.\ds · exit 0 · a""").matches(out.lines[1]), out.lines[1])
        assertTrue(out.lines[2].startsWith("▸ Thought for"), out.lines[2])
        // A blank line where the work ends and the answer begins.
        assertEquals("", out.lines[3])
        assertEquals("Done.", out.lines[4])
        assertEquals(5, out.lines.size)
    }

    @Test
    fun `expanded writes the thinking out, hidden leaves it out`() {
        val expanded = Recorder()
        TurnRenderer(plain, expanded, "expanded").apply {
            handle(ChatEvent.Thinking("one\ntwo"))
            handle(ChatEvent.Chunk("x\n"))
        }
        assertEquals(listOf("▾ Thought", "  │ one", "  │ two", "", "x"), expanded.lines.map { it.replace(Regex(" for \\d+\\.\\ds"), "") })
        val hidden = Recorder()
        TurnRenderer(plain, hidden, "hidden").apply {
            handle(ChatEvent.Thinking("secret"))
            handle(ChatEvent.Chunk("x\n"))
        }
        assertEquals(listOf("x"), hidden.lines)
        assertTrue(hidden.live.none { it == "secret" })
    }

    @Test
    fun `asking an agent shows in the live line and in the transcript`() {
        val indicators = mutableListOf<String?>()
        val lines = mutableListOf<String>()
        val renderer = TurnRenderer(plain, object : TurnOutput {
            override fun line(text: String) { lines += text }
            override fun phase(key: String, text: String) { indicators += text }
        })
        renderer.handle(ChatEvent.Call(0, "ask_agent", """{"agent":"Researcher","question":"What is new?"}"""))
        renderer.handle(ChatEvent.Called(0, """{"asked":"Researcher","session":"12"}""", false))
        assertTrue("Asking Researcher…" in indicators, indicators.toString())
        assertTrue(lines[0].startsWith("✓ ask_agent asked Researcher · "), lines[0])
    }

    // ---- review ------------------------------------------------------------------------------

    @Test
    fun `review targets`() {
        assertEquals(ReviewRequest(null, false), Review.parse(null))
        assertEquals(ReviewRequest("main", false), Review.parse("main"))
        assertEquals(ReviewRequest("src/app", true), Review.parse("--fix src/app"))
        assertEquals(ReviewRequest(null, true), Review.parse("--fix"))
        assertFailsWith<IllegalArgumentException> { Review.parse("main src") }
    }

    private fun git(dir: Path, vararg args: String) {
        val ran = Review.git(dir, *args)
        check(ran.exit == 0) { "git ${args.joinToString(" ")} failed" }
    }

    private fun repository(dir: Path): Path {
        assumeTrue(runCatching { Review.git(dir, "--version").exit == 0 }.getOrDefault(false), "git is not installed")
        git(dir, "init", "-q", "-b", "main")
        git(dir, "config", "user.email", "t@example.com")
        git(dir, "config", "user.name", "Test")
        git(dir, "config", "commit.gpgsign", "false")
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/a.kt"), "fun a() = 1\n")
        Files.writeString(dir.resolve("README.md"), "hello\n")
        git(dir, "add", ".")
        git(dir, "commit", "-q", "-m", "first")
        return dir
    }

    @Test
    fun `uncommitted changes are staged, unstaged and untracked text files`(@TempDir dir: Path) {
        repository(dir)
        Files.writeString(dir.resolve("src/a.kt"), "fun a() = 2\n")
        Files.writeString(dir.resolve("src/new.kt"), "fun b() = 3\n")
        Files.write(dir.resolve("blob.bin"), byteArrayOf(0, 1, 2, 0))
        Files.writeString(dir.resolve("README.md"), "hello again\n")
        git(dir, "add", "README.md")
        val diff = Review.gather(Review.parse(null), dir)
        assertEquals(listOf("README.md", "src/a.kt", "src/new.kt"), diff.files)
        assertTrue(diff.diff.contains("-fun a() = 1\n+fun a() = 2"))
        assertTrue(diff.diff.contains("+++ b/src/new.kt\n@@ -0,0 +1,1 @@\n+fun b() = 3\n"))
        assertTrue(diff.diff.contains("+hello again"))
        assertTrue(!diff.diff.contains("blob.bin"))
        assertNull(diff.cut)

        val under = Review.gather(Review.parse("src"), dir)
        assertEquals(listOf("src/a.kt", "src/new.kt"), under.files)
    }

    @Test
    fun `a branch is reviewed from where it left the target`(@TempDir dir: Path) {
        repository(dir)
        git(dir, "checkout", "-q", "-b", "feature")
        Files.writeString(dir.resolve("src/a.kt"), "fun a() = 42\n")
        git(dir, "commit", "-q", "-am", "change")
        Files.writeString(dir.resolve("uncommitted.txt"), "not part of it\n")
        val diff = Review.gather(Review.parse("main"), dir)
        assertEquals(listOf("src/a.kt"), diff.files)
        assertTrue(diff.diff.contains("+fun a() = 42"))
        assertTrue(diff.description.contains("since it left main"))
    }

    @Test
    fun `nothing to review, and outside a repository, are refused in words`(@TempDir dir: Path) {
        val outside = Files.createDirectories(dir.resolve("plain"))
        assumeTrue(runCatching { Review.git(outside, "--version").exit == 0 }.getOrDefault(false), "git is not installed")
        val notRepo = assertFailsWith<OrknuxException> { Review.gather(Review.parse(null), outside) }
        assertTrue(notRepo.message!!.contains("is not inside a git repository"), notRepo.message)
        val repo = repository(Files.createDirectories(dir.resolve("repo")))
        val nothing = assertFailsWith<OrknuxException> { Review.gather(Review.parse(null), repo) }
        assertTrue(nothing.message!!.startsWith("Nothing to review"), nothing.message)
        val unknown = assertFailsWith<OrknuxException> { Review.gather(Review.parse("no-such-thing"), repo) }
        assertTrue(unknown.message!!.contains("neither a branch or commit nor a path"), unknown.message)
    }

    @Test
    fun `a diff past the cap is cut between files, and says what was left out`() {
        val parts = listOf("a.kt" to "x".repeat(60), "b.kt" to "y".repeat(60), "c.kt" to "z".repeat(10))
        val capped = Review.cap(Path.of("/r"), "the changes", parts, 100)
        assertEquals("x".repeat(60), capped.diff)
        assertEquals("The diff was cut at 100 of 130 characters; left out: b.kt, c.kt.", capped.cut)
        val one = Review.cap(Path.of("/r"), "the changes", listOf("big.kt" to "q".repeat(500)), 100)
        assertEquals(100, one.diff.length)
        assertEquals("The diff was cut at 100 of 500 characters; left out: the rest of big.kt.", one.cut)
    }

    @Test
    fun `the review message carries the instruction or the skill, and asks for the findings block`() {
        val diff = ReviewDiff(Path.of("/r"), "the uncommitted changes in /r", "diff --git a/x b/x\n", listOf("x"), null)
        val builtIn = Review.message(diff, null)
        assertTrue(builtIn.contains(Review.INSTRUCTION))
        assertTrue(builtIn.contains("terminal_run"))
        assertTrue(builtIn.contains("```diff\ndiff --git a/x b/x\n```"))
        assertTrue(builtIn.contains("```findings"))
        val withSkill = Review.message(diff, "!code-review")
        assertTrue(withSkill.startsWith("!code-review Review the uncommitted changes in /r."))
        assertTrue(!withSkill.contains(Review.INSTRUCTION))
        val skills = listOf(Skill("deploy", "Deploy", null, "c", null), Skill("code-review", "Code review", null, "c", null))
        assertEquals("code-review", Review.skillFor(skills)?.key)
        assertNull(Review.skillFor(listOf(Skill("deploy", "Deploy", null, "c", null))))
    }

    @Test
    fun `findings are read off the answer and listed with severity and a file link`() {
        val answer = "Two things.\n\n```findings\nhigh | src/a.kt:12 | The stream is never closed\nlow | README.md | A typo\n```\n"
        val findings = Review.findings(answer)
        assertEquals(listOf(Finding("high", "src/a.kt", 12, "The stream is never closed"), Finding("low", "README.md", null, "A typo")), findings)
        assertEquals(emptyList(), Review.findings("All good.\n```findings\nnone\n```"))
        val lines = Review.render(findings, Path.of("/repo"), plain, Links(true, null))
        assertEquals("Findings", lines[0])
        assertEquals("  high     \u001b]8;;${Links.fileUrl(Path.of("/repo/src/a.kt"))}\u001b\\src/a.kt:12\u001b]8;;\u001b\\ The stream is never closed", lines[1])
        assertEquals(listOf("No findings."), Review.render(emptyList(), Path.of("/repo"), plain, Links(false, null)))
        assertNotNull(Review.findings(answer).firstOrNull())
    }
}
