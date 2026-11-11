package io.mszymanski.orknux.shell

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pictures pasted in and copied out: everything of it but a real clipboard. */
class ClipboardTest {
    private fun resource(name: String) = ClipboardTest::class.java.getResourceAsStream(name)!!.readAllBytes()
    private val red = resource("red.png")

    /** A stand-in for the clipboard programs: answers by command line, and records what it was run with. */
    private class Fake(private val answers: Map<List<String>, ByteArray>) : Clipboards.Runner {
        val ran = CopyOnWriteArrayList<Triple<List<String>, ByteArray?, Boolean>>()
        override fun run(command: List<String>, input: ByteArray?, waitForOutput: Boolean): Pair<Int, ByteArray> {
            ran += Triple(command, input, waitForOutput)
            val answer = answers[command] ?: answers.entries.firstOrNull { (k, _) -> k.size == 1 && command.first() == k.first() }?.value
            return if (answer == null) 1 to ByteArray(0) else 0 to answer
        }
    }

    // ---- finding one ---------------------------------------------------------------------------

    @Test
    fun `the platform's own clipboard client is found, and its absence is said with what to install`() {
        val none = { _: String -> false }
        val all = { _: String -> true }
        assertIs<WaylandClipboard>(Clipboards.find(mapOf("WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0"), "Linux", all, awt = { null }).getOrThrow())
        assertIs<XClipboard>(Clipboards.find(mapOf("DISPLAY" to ":0"), "Linux", all, awt = { null }).getOrThrow())
        // Wayland without wl-clipboard, but XWayland and xclip: xclip it is.
        assertIs<XClipboard>(Clipboards.find(mapOf("WAYLAND_DISPLAY" to "w", "DISPLAY" to ":0"), "Linux", { it == "xclip" }, awt = { null }).getOrThrow())
        assertIs<WindowsClipboard>(Clipboards.find(emptyMap(), "Windows 11", all, awt = { null }).getOrThrow())

        assertTrue(Clipboards.find(mapOf("WAYLAND_DISPLAY" to "w"), "Linux", none, awt = { null }).exceptionOrNull()!!.message!!.contains("wl-clipboard"))
        assertTrue(Clipboards.find(mapOf("DISPLAY" to ":0"), "Linux", none, awt = { null }).exceptionOrNull()!!.message!!.contains("xclip is not installed"))
        assertTrue(Clipboards.find(emptyMap(), "Linux", all, awt = { null }).exceptionOrNull()!!.message!!.contains("there is no display here"))
        // A Windows root with no PowerShell in it, so the suite says the same on a Windows machine, which has one.
        val bare = mapOf("SystemRoot" to "/nowhere-${System.nanoTime()}")
        assertTrue(Clipboards.find(bare, "Windows 11", none, awt = { null }).exceptionOrNull()!!.message!!.contains("PowerShell"))
    }

    /**
     * Inside WSL the picture a person copied in Windows is on Windows' clipboard,
     * which WSLg's Linux one does not reliably carry: the Shell goes to Windows'
     * own PowerShell, even with a Linux clipboard to hand.
     */
    @Test
    fun `inside WSL the Windows clipboard is used, and a copied file's path is turned into WSL's`() {
        val wsl = mapOf("WSL_DISTRO_NAME" to "Ubuntu", "WAYLAND_DISPLAY" to "wayland-0", "DISPLAY" to ":0")
        val found = Clipboards.find(wsl, "Linux", { true }, awt = { null }).getOrThrow()
        assertIs<WindowsClipboard>(found)
        assertTrue(found.name.contains("powershell.exe"))
        // No powershell.exe on the PATH (interop switched off): the Linux clipboard, as anywhere else.
        assertIs<WaylandClipboard>(Clipboards.find(wsl, "Linux", { it != "powershell.exe" }, awt = { null }).getOrThrow())

        val windowsPath = "C:\\Users\\a\\shot.png"
        val fake = Fake(
            mapOf(
                listOf("powershell.exe") to ("FILES:" + Base64.getEncoder().encodeToString(windowsPath.toByteArray()) + "\r\n").toByteArray(),
                listOf("wslpath", "-u", windowsPath) to "/mnt/c/Users/a/shot.png\n".toByteArray(),
            ),
        )
        assertEquals(Clipped.Files(listOf("/mnt/c/Users/a/shot.png")), WindowsClipboard(fake, "powershell.exe", wsl = true).read())
    }

    /** Windows spells it `Path`, and the environment map Java hands over is case-sensitive: it is found all the same. */
    @Test
    fun `the search path is found however it is spelled, and Windows' own PowerShell where it is not on it`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        val program = java.nio.file.Files.createFile(dir.resolve("powershell.exe"))
        program.toFile().setExecutable(true)
        assertTrue(Clipboards.onPath("powershell", mapOf("Path" to dir.toString())))
        assertTrue(!Clipboards.onPath("powershell", mapOf("Path" to "/nowhere")))

        val root = java.nio.file.Files.createDirectories(dir.resolve("Windows/System32/WindowsPowerShell/v1.0"))
        java.nio.file.Files.createFile(root.resolve("powershell.exe"))
        val found = Clipboards.find(mapOf("SystemRoot" to dir.resolve("Windows").toString()), "Windows 11", { false }, awt = { null }).getOrThrow()
        assertIs<WindowsClipboard>(found)
    }

    // ---- reading -------------------------------------------------------------------------------

    @Test
    fun `Wayland - a picture is taken in the best type offered, then copied files, then text`() {
        val picture = Fake(
            mapOf(
                listOf("wl-paste", "--list-types") to "text/plain\nimage/jpeg\nimage/png\n".toByteArray(),
                listOf("wl-paste", "--no-newline", "--type", "image/png") to red,
            ),
        )
        val read = WaylandClipboard(picture).read()
        assertIs<Clipped.Image>(read)
        assertEquals("image/png", read.picture.contentType)
        assertEquals(64 to 48, read.picture.size)

        val files = Fake(
            mapOf(
                listOf("wl-paste", "--list-types") to "text/uri-list\ntext/plain".toByteArray(),
                listOf("wl-paste", "--no-newline", "--type", "text/uri-list") to "file:///home/a/My%20shot.png\r\nfile:///home/a/b.txt\n".toByteArray(),
            ),
        )
        // In the platform's own spelling: the tests run on Windows too, where a file URI's path comes back with backslashes.
        val expected = listOf("/home/a/My shot.png", "/home/a/b.txt").map { java.nio.file.Path.of(it).toString() }
        assertEquals(Clipped.Files(expected), WaylandClipboard(files).read())

        val text = Fake(mapOf(listOf("wl-paste", "--list-types") to "text/plain;charset=utf-8".toByteArray(), listOf("wl-paste", "--no-newline") to "zażółć".toByteArray()))
        assertEquals(Clipped.Text("zażółć"), WaylandClipboard(text).read())
        assertEquals(Clipped.Empty, WaylandClipboard(Fake(emptyMap())).read())
    }

    @Test
    fun `X11 - xclip is asked for its targets, then the picture`() {
        val fake = Fake(
            mapOf(
                listOf("xclip", "-selection", "clipboard", "-t", "TARGETS", "-o") to "TARGETS\nimage/png\nUTF8_STRING".toByteArray(),
                listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-o") to red,
            ),
        )
        val read = XClipboard(fake).read()
        assertIs<Clipped.Image>(read)
        assertContentEquals(red, read.picture.bytes)
    }

    @Test
    fun `Windows - PowerShell answers a tagged line of base64, for a picture, copied files or text`() {
        fun answer(line: String) = Fake(mapOf(listOf("powershell") to line.toByteArray()))
        val picture = WindowsClipboard(answer("IMAGE:" + Base64.getEncoder().encodeToString(red) + "\r\n")).read()
        assertIs<Clipped.Image>(picture)
        assertEquals(64 to 48, picture.picture.size)
        assertEquals(Clipped.Files(listOf("C:\\Users\\a\\shot.png", "C:\\b.txt")), WindowsClipboard(answer("FILES:" + Base64.getEncoder().encodeToString("C:\\Users\\a\\shot.png\nC:\\b.txt".toByteArray()))).read())
        assertEquals(Clipped.Text("hi"), WindowsClipboard(answer("TEXT:aGk=")).read())
        assertEquals(Clipped.Empty, WindowsClipboard(answer("EMPTY:")).read())
        val ran = Fake(mapOf(listOf("powershell") to "EMPTY:".toByteArray())).also { WindowsClipboard(it).read() }.ran.single().first
        // Encoded, so the double quotes in it survive Windows' command line: nothing in the argument needs quoting.
        assertEquals(listOf("powershell", "-NoProfile", "-NonInteractive", "-STA", "-EncodedCommand"), ran.take(5))
        assertTrue(ran[5].none { it == '"' || it == ' ' || it == '\n' })
        val script = String(Base64.getDecoder().decode(ran[5]), Charsets.UTF_16LE)
        assertTrue(script.contains("GetImage()") && script.contains("GetFileDropList()"))
        // And a PowerShell that failed is said, not read as an empty clipboard.
        val failed = runCatching { WindowsClipboard(Fake(emptyMap())).read() }.exceptionOrNull()
        assertTrue(failed?.message?.contains("PowerShell could not read the clipboard") == true, failed?.message)
    }

    /**
     * The real PowerShell, on a Windows machine - the release build's - which is
     * the only place the script's way onto the command line can be checked: a
     * fake runner is handed the arguments as they are, and 1.0.42 passed every
     * test while Windows' PowerShell was given a broken script. A script that
     * reaches it whole answers with one of its tags, whatever the clipboard holds.
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `the real PowerShell runs the script whole`() {
        val (code, out) = Clipboards.PROCESSES.run(
            WindowsClipboard.command("powershell", WindowsClipboard.READ),
            null,
            true,
        )
        val said = out.toString(Charsets.US_ASCII).trim()
        assertEquals(0, code, said)
        assertTrue(listOf("IMAGE:", "FILES:", "TEXT:", "EMPTY:").any { said.startsWith(it) }, said)
    }

    // ---- writing -------------------------------------------------------------------------------

    @Test
    fun `a picture is handed to the tool on standard input, which is not waited on for output`() {
        val wl = Fake(mapOf(listOf("wl-copy") to ByteArray(0)))
        WaylandClipboard(wl).write(Picture(red, "image/png"))
        assertEquals(Triple(listOf("wl-copy", "--type", "image/png"), red.toList(), false), wl.ran.single().let { Triple(it.first, it.second?.toList(), it.third) })

        val x = Fake(mapOf(listOf("xclip") to ByteArray(0)))
        XClipboard(x).write(Picture(red, "image/png"))
        assertEquals(listOf("xclip", "-selection", "clipboard", "-t", "image/png", "-i"), x.ran.single().first)

        val windows = Fake(mapOf(listOf("powershell") to ByteArray(0)))
        WindowsClipboard(windows).write(Picture(red, "image/png"))
        val script = String(Base64.getDecoder().decode(windows.ran.single().first.last()), Charsets.UTF_16LE)
        assertTrue(script.contains("SetDataObject") && script.contains("orknux-copy-") && script.contains(".png"), script)

        // Detached into a session of its own where setsid is there, so the picture outlives the Shell.
        val detached = Fake(mapOf(listOf("setsid") to ByteArray(0)))
        XClipboard(detached, listOf("setsid")).write(Picture(red, "image/png"))
        assertEquals("setsid", detached.ran.single().first.first())

        val refused = runCatching { XClipboard(Fake(emptyMap())).write(Picture(red, "image/png")) }.exceptionOrNull()
        assertEquals("xclip could not take the picture.", refused?.message)
    }

    // ---- pictures and chips ----------------------------------------------------------------------

    @Test
    fun `a picture's type and size are read off its first bytes`() {
        assertEquals("image/png", Pictures.sniff(red))
        assertEquals(64 to 48, Pictures.dimensions(red))
        assertEquals("image/jpeg", Pictures.sniff(resource("blue.jpg")))
        assertEquals(40 to 30, Pictures.dimensions(resource("blue.jpg")))
        assertEquals(20 to 10, Pictures.dimensions(resource("green.gif")))
        assertNull(Pictures.dimensions("not a picture".toByteArray()))
        assertEquals("image/png", Pictures.typeOf("/a/Shot.PNG"))
        assertNull(Pictures.typeOf("/a/notes.txt"))
    }

    private fun attachment(id: Long, name: String) = Attachment(id, name, "image/png", 10)

    @Test
    fun `a chip names its picture, Backspace at its end finds it, and a line sends without its chips`() {
        val chips = ImageChips()
        val one = attachment(1, "a.png")
        val two = attachment(2, "b.png")
        val first = chips.add(one, "a.png", "670 KB")
        val second = chips.add(two, "/home/michal/pics/b.png", "1.2 MB")
        assertEquals("[image 1: a.png · 670 KB]", first, "the name it was given and its size")
        assertEquals("[image 2: /home/michal/pics/b.png · 1.2 MB]", second, "a file's own path, not just its name")

        val line = "what is in $first and $second"
        assertEquals(second to second.length, chips.endingAt(line, line.length))
        // After the space a paste leaves behind it, Backspace still takes the chip, and the space.
        assertEquals(second to second.length + 1, chips.endingAt("$line ", line.length + 1))
        assertNull(chips.endingAt(line, line.length - 1))

        // The second chip deleted by some other edit: its attachment goes, the first stays.
        val taken = chips.take("what is in $first and ")
        assertEquals("what is in and", taken.text)
        assertEquals(listOf(one), taken.pictures)
        assertEquals(listOf(two), taken.dropped)
        assertTrue(chips.isEmpty())
        assertEquals("[image 1: b.png · 1.2 MB]", chips.add(two, "b.png", "1.2 MB"), "numbered from 1 again for the next message")
    }

    @Test
    fun `the pictures an answer shows are kept in order, once each - drawn, filed, and in its markdown`() {
        val renderer = TurnRenderer(Theme(depth = ColourDepth.NONE), object : TurnOutput {
            override fun line(text: String) {}
            override fun phase(key: String, text: String) {}
        })
        renderer.handle(ChatEvent.Drew("![a cat](/api/attachments/9)"))
        renderer.handle(ChatEvent.Filed("chart.png", "image/png", "12"))
        renderer.handle(ChatEvent.Filed("notes.txt", "text/plain", "13"))
        renderer.handle(ChatEvent.Chunk("Here: ![plot](<https://x.example/p.png> \"Plot\") and again ![a cat](/api/attachments/9)."))
        renderer.handle(ChatEvent.Answered)
        assertEquals(
            listOf(ShownPicture("a cat", "/api/attachments/9"), ShownPicture("chart.png", "/api/attachments/12"), ShownPicture("plot", "https://x.example/p.png")),
            renderer.pictures,
        )
        assertEquals(ChatEvent.Filed("f.png", "image/png", "7"), ChatEvent.of(SseFrame("filed", """{"id":"7","filename":"f.png","contentType":"image/png","sizeBytes":3}""")))
    }

    // ---- the server ----------------------------------------------------------------------------

    @Test
    fun `a pasted picture is uploaded as an attachment, and goes with the next message by its id`() {
        StubOrknux(emptyList()).use { stub ->
            val api = Api(OrknuxClient(stub.url, cookies = mapOf("SESSION" to "s3cr3t")))
            val kept = api.uploadPicture("1", "pasted-image.png", "image/png", red)
            val upload = stub.uploads.single()
            assertTrue(upload.body.contains("name=\"files\"; filename=\"pasted-image.png\""))
            assertTrue(upload.body.contains("Content-Type: image/png"))
            assertEquals(red.size, upload.fileBytes)
            assertEquals(stub.url, upload.origin)

            val conversation = Conversation(api, Workspace("1", "Home", null), null, "auto")
            conversation.send("", listOf(kept.id)) {}
            assertEquals(listOf(kept.id), stub.streamRequests.single().get("attachmentIds").map { it.asLong() })
        }
    }

    @Test
    fun `a picture is fetched from this server with its credentials, and from elsewhere without them`() {
        val cookies = CopyOnWriteArrayList<String?>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { x ->
            cookies += x.requestHeaders.getFirst("Cookie")
            val (type, body) = if (x.requestURI.path == "/api/attachments/9") "image/png" to red else "text/html" to "<html>".toByteArray(StandardCharsets.UTF_8)
            x.responseHeaders.add("Content-Type", type)
            x.sendResponseHeaders(200, body.size.toLong())
            x.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val server = "http://127.0.0.1:${http.address.port}"
            val api = Api(OrknuxClient(server, cookies = mapOf("SESSION" to "s3cr3t")))
            assertEquals(64 to 48, api.picture("/api/attachments/9").size)
            assertEquals("SESSION=s3cr3t", cookies.last())
            // The same address written in full is still this server.
            api.picture("$server/api/attachments/9")
            assertEquals("SESSION=s3cr3t", cookies.last())
            val other = Api(OrknuxClient("http://elsewhere.invalid", cookies = mapOf("SESSION" to "s3cr3t")))
            assertEquals(64 to 48, other.picture("$server/api/attachments/9").size)
            assertNull(cookies.last())
            assertTrue(runCatching { api.picture("/page") }.exceptionOrNull()!!.message!!.startsWith("That is not a picture"))
        } finally {
            http.stop(0)
        }
    }
}
