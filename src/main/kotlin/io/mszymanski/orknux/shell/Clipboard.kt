package io.mszymanski.orknux.shell

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A picture, as bytes and what they are. */
class Picture(val bytes: ByteArray, val contentType: String) {
    val extension: String get() = Pictures.extensionOf(contentType)
    val size: Pair<Int, Int>? by lazy { Pictures.dimensions(bytes) }
}

/** What the clipboard holds, as far as a chat cares. */
sealed interface Clipped {
    data class Image(val picture: Picture) : Clipped
    /** Files copied in a file manager: their paths. */
    data class Files(val paths: List<String>) : Clipped
    data class Text(val text: String) : Clipped
    data object Empty : Clipped
}

/** The system clipboard, through whatever this computer has to reach it. */
interface Clipboard {
    /** What it is reached through, for the person: "wl-clipboard", "xclip", "PowerShell". */
    val name: String
    fun read(): Clipped
    /** Puts [picture] on the clipboard as an image; refused in words where it cannot be. */
    fun write(picture: Picture)
}

/**
 * Finds the clipboard: on Linux the Wayland tools (wl-paste, wl-copy) where
 * there is a Wayland session, xclip where there is an X display; on Windows the
 * system clipboard through PowerShell's Windows Forms, which every Windows has;
 * and Java's own AWT clipboard last, on a JVM with a display. The tools are the
 * platform's own clipboard clients rather than code of the Shell's, and they
 * work the same in the native binary, which carries no AWT.
 *
 * xsel is not used: it moves text only, and a picture is the point here.
 */
object Clipboards {
    /** Runs a program: its exit code and standard output; [input] is written to its standard input. */
    fun interface Runner {
        fun run(command: List<String>, input: ByteArray?, waitForOutput: Boolean): Pair<Int, ByteArray>
    }

    val PROCESSES = Runner { command, input, waitForOutput ->
        val builder = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        // A tool that stays behind to serve what it was given (xclip, wl-copy) must not be waited on through a pipe.
        if (!waitForOutput) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        val process = builder.start()
        if (input != null) process.outputStream.use { it.write(input) } else process.outputStream.close()
        val out = if (waitForOutput) process.inputStream.use { it.readAllBytes() } else ByteArray(0)
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw OrknuxException("${command.first()} did not answer.")
        }
        process.exitValue() to out
    }

    /** Whether this is Linux under WSL, which says so in its environment. */
    fun isWsl(env: Map<String, String> = System.getenv()): Boolean =
        !env["WSL_DISTRO_NAME"].isNullOrBlank() || !env["WSL_INTEROP"].isNullOrBlank()

    /** Whether [program] is on the PATH. */
    fun onPath(program: String, env: Map<String, String> = System.getenv()): Boolean =
        (env["PATH"] ?: "").split(java.io.File.pathSeparatorChar).any { dir ->
            dir.isNotBlank() && listOf("", ".exe", ".cmd").any { Files.isExecutable(Path.of(dir, program + it)) }
        }

    /** The clipboard this computer offers, or why there is none, in words saying what to install. */
    fun find(
        env: Map<String, String> = System.getenv(),
        os: String = System.getProperty("os.name"),
        found: (String) -> Boolean = { onPath(it, env) },
        runner: Runner = PROCESSES,
        awt: () -> Clipboard? = ::awtClipboard,
    ): Result<Clipboard> {
        if (Platform.isWindows(os)) {
            val shell = listOf("powershell", "pwsh").firstOrNull(found)
            if (shell != null) return Result.success(WindowsClipboard(runner, shell))
            awt()?.let { return Result.success(it) }
            return Result.failure(OrknuxException("No clipboard: PowerShell was not found on the PATH."))
        }
        /*
         * Inside WSL the clipboard that matters is Windows': a picture copied in a
         * Windows browser is there, and WSLg's Linux clipboard does not reliably
         * carry pictures across. Windows' own PowerShell is reachable from WSL by
         * name, so it is read the way the Windows build reads it.
         */
        if (isWsl(env) && found("powershell.exe")) return Result.success(WindowsClipboard(runner, "powershell.exe", wsl = true))
        // wl-copy and xclip stay behind to hold what they were given; in a session of their own they outlive the Shell.
        val detach = if (found("setsid")) listOf("setsid") else emptyList()
        if (!env["WAYLAND_DISPLAY"].isNullOrBlank() && found("wl-paste") && found("wl-copy")) return Result.success(WaylandClipboard(runner, detach))
        if (!env["DISPLAY"].isNullOrBlank() && found("xclip")) return Result.success(XClipboard(runner, detach))
        awt()?.let { return Result.success(it) }
        val why = when {
            !env["WAYLAND_DISPLAY"].isNullOrBlank() -> "this is a Wayland session and wl-paste/wl-copy are not installed (the wl-clipboard package)"
            !env["DISPLAY"].isNullOrBlank() -> "xclip is not installed (xsel moves only text)"
            os.lowercase().contains("mac") -> "the Shell does not reach the macOS clipboard yet"
            else -> "there is no display here (no WAYLAND_DISPLAY or DISPLAY) - over ssh the clipboard is the other computer's"
        }
        return Result.failure(OrknuxException("No clipboard: $why."))
    }

    /**
     * Java's own clipboard, on a JVM with a display; never in the native
     * binary, which is built without AWT. Looked up by a name made at run time,
     * so that native-image does not pull AWT in for a path it cannot use.
     */
    private fun awtClipboard(): Clipboard? {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) return null
        return runCatching {
            val name = listOf("io", "mszymanski", "orknux", "shell", "AwtClipboard").joinToString(".")
            val made = Class.forName(name).getDeclaredConstructor().newInstance() as Clipboard
            made.takeIf { (it as AvailableOrNot).available() }
        }.getOrNull()
    }

    /** The image types a clipboard is read for, best first. */
    val IMAGE_TYPES = listOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

    /** `text/uri-list` as paths: the `file://` lines, decoded. */
    fun paths(uriList: String): List<String> = uriList.lines().map { it.trim() }
        .filter { it.startsWith("file://") }
        .mapNotNull { runCatching { Path.of(java.net.URI(it)).toString() }.getOrNull() }
}

/** Implemented by a clipboard that may turn out not to work here. */
interface AvailableOrNot {
    fun available(): Boolean
}

private fun Clipboards.Runner.output(command: List<String>): ByteArray? =
    run(command, null, true).let { (code, out) -> if (code == 0) out else null }

/** Wayland, through wl-clipboard. */
class WaylandClipboard(private val runner: Clipboards.Runner, private val detach: List<String> = emptyList()) : Clipboard {
    override val name = "wl-clipboard"

    override fun read(): Clipped {
        val types = runner.output(listOf("wl-paste", "--list-types"))?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() } ?: return Clipped.Empty
        Clipboards.IMAGE_TYPES.firstOrNull { it in types }?.let { type ->
            runner.output(listOf("wl-paste", "--no-newline", "--type", type))?.takeIf { it.isNotEmpty() }?.let { return Clipped.Image(Picture(it, type)) }
        }
        if ("text/uri-list" in types) {
            runner.output(listOf("wl-paste", "--no-newline", "--type", "text/uri-list"))?.let { Clipboards.paths(it.toString(Charsets.UTF_8)) }
                ?.takeIf { it.isNotEmpty() }?.let { return Clipped.Files(it) }
        }
        if (types.any { it.startsWith("text/") || it == "UTF8_STRING" || it == "STRING" }) {
            runner.output(listOf("wl-paste", "--no-newline"))?.let { return Clipped.Text(it.toString(Charsets.UTF_8)) }
        }
        return Clipped.Empty
    }

    override fun write(picture: Picture) {
        val (code, _) = runner.run(detach + listOf("wl-copy", "--type", picture.contentType), picture.bytes, false)
        if (code != 0) throw OrknuxException("wl-copy could not take the picture.")
    }
}

/** X11, through xclip. */
class XClipboard(private val runner: Clipboards.Runner, private val detach: List<String> = emptyList()) : Clipboard {
    override val name = "xclip"

    private fun get(type: String) = runner.output(listOf("xclip", "-selection", "clipboard", "-t", type, "-o"))

    override fun read(): Clipped {
        val types = get("TARGETS")?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() } ?: return Clipped.Empty
        Clipboards.IMAGE_TYPES.firstOrNull { it in types }?.let { type ->
            get(type)?.takeIf { it.isNotEmpty() }?.let { return Clipped.Image(Picture(it, type)) }
        }
        if ("text/uri-list" in types) {
            get("text/uri-list")?.let { Clipboards.paths(it.toString(Charsets.UTF_8)) }?.takeIf { it.isNotEmpty() }?.let { return Clipped.Files(it) }
        }
        val text = listOf("UTF8_STRING", "text/plain;charset=utf-8", "STRING", "text/plain").firstOrNull { it in types }
        if (text != null) get(text)?.let { return Clipped.Text(it.toString(Charsets.UTF_8)) }
        return Clipped.Empty
    }

    override fun write(picture: Picture) {
        val (code, _) = runner.run(detach + listOf("xclip", "-selection", "clipboard", "-t", picture.contentType, "-i"), picture.bytes, false)
        if (code != 0) throw OrknuxException("xclip could not take the picture.")
    }
}

/**
 * Windows, through PowerShell and Windows Forms' Clipboard: a picture as PNG,
 * files copied in Explorer as their paths, or text. Its answers come back as
 * one tagged line of base64, so no console code page touches them.
 */
class WindowsClipboard(
    private val runner: Clipboards.Runner,
    private val shell: String = "powershell",
    /** Reached from WSL: a copied file's Windows path is turned into the Linux one, and the other way round to copy. */
    private val wsl: Boolean = false,
) : Clipboard {
    override val name = if (wsl) "Windows' clipboard, through powershell.exe" else "PowerShell"

    /** A path as the other side spells it, through WSL's own wslpath; as it was where that does not answer. */
    private fun converted(path: String, flag: String): String =
        runner.output(listOf("wslpath", flag, path))?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() } ?: path

    private fun ps(script: String) = listOf(shell, "-NoProfile", "-NonInteractive", "-STA", "-Command", script)

    override fun read(): Clipped {
        val out = runner.output(ps(READ))?.toString(Charsets.US_ASCII)?.trim() ?: return Clipped.Empty
        val tag = out.substringBefore(':')
        val body = runCatching { Base64.getDecoder().decode(out.substringAfter(':', "")) }.getOrNull() ?: return Clipped.Empty
        return when (tag) {
            "IMAGE" -> Clipped.Image(Picture(body, "image/png"))
            "FILES" -> Clipped.Files(body.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { if (wsl) converted(it.trim(), "-u") else it })
            "TEXT" -> Clipped.Text(body.toString(Charsets.UTF_8))
            else -> Clipped.Empty
        }
    }

    override fun write(picture: Picture) {
        val file = Files.createTempFile("orknux-copy-", "." + picture.extension)
        try {
            Files.write(file, picture.bytes)
            val path = (if (wsl) converted(file.toString(), "-w") else file.toString()).replace("'", "''")
            val (code, _) = runner.run(ps(WRITE.replace("{path}", path)), null, true)
            if (code != 0) throw OrknuxException("Windows did not take the picture onto the clipboard.")
        } finally {
            Files.deleteIfExists(file)
        }
    }

    companion object {
        val READ = """
            Add-Type -AssemblyName System.Windows.Forms, System.Drawing
            ${'$'}c = [System.Windows.Forms.Clipboard]
            if (${'$'}c::ContainsImage()) { ${'$'}m = New-Object IO.MemoryStream; ${'$'}c::GetImage().Save(${'$'}m, [System.Drawing.Imaging.ImageFormat]::Png); 'IMAGE:' + [Convert]::ToBase64String(${'$'}m.ToArray()) }
            elseif (${'$'}c::ContainsFileDropList()) { 'FILES:' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes((${'$'}c::GetFileDropList() -join "`n"))) }
            elseif (${'$'}c::ContainsText()) { 'TEXT:' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes(${'$'}c::GetText())) }
            else { 'EMPTY:' }
        """.trimIndent()

        /** Copied rather than referenced (`SetDataObject(..., $true)`), so the picture stays once PowerShell has gone. */
        val WRITE = """
            Add-Type -AssemblyName System.Windows.Forms, System.Drawing
            ${'$'}i = [System.Drawing.Image]::FromFile('{path}')
            [System.Windows.Forms.Clipboard]::SetDataObject((New-Object System.Drawing.Bitmap ${'$'}i), ${'$'}true)
            ${'$'}i.Dispose()
        """.trimIndent()
    }
}

/** What is known of a picture from its first bytes, without decoding it. */
object Pictures {
    fun extensionOf(contentType: String): String = when (contentType.substringBefore(';').lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "image/svg+xml" -> "svg"
        else -> "img"
    }

    fun typeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> null
    }

    /** The type the bytes themselves say, where they say one. */
    fun sniff(bytes: ByteArray): String? = when {
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() -> "image/png"
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF" -> "image/gif"
        bytes.size > 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        bytes.size > 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
        else -> null
    }

    /** Width and height from a PNG, GIF or JPEG header; null for anything else. */
    fun dimensions(b: ByteArray): Pair<Int, Int>? {
        fun u16be(i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
        fun u16le(i: Int) = ((b[i + 1].toInt() and 0xff) shl 8) or (b[i].toInt() and 0xff)
        fun u32be(i: Int) = (u16be(i) shl 16) or u16be(i + 2)
        return when (sniff(b)) {
            "image/png" -> if (b.size >= 24) u32be(16) to u32be(20) else null
            "image/gif" -> if (b.size >= 10) u16le(6) to u16le(8) else null
            "image/jpeg" -> {
                var i = 2
                while (i + 9 < b.size) {
                    if (b[i] != 0xFF.toByte()) return null
                    val marker = b[i + 1].toInt() and 0xff
                    val length = u16be(i + 2)
                    // Start of frame, any kind but the DHT, JPG and DAC markers that share the range.
                    if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) return u16be(i + 7) to u16be(i + 5)
                    i += 2 + length
                }
                null
            }
            else -> null
        }
    }
}

/**
 * Pictures pasted into the line being written, each shown in it as a chip -
 * `[image 1: 1280×720 png]` - that stands for an attachment already uploaded.
 * Backspace at a chip's end takes the whole chip, and the attachment with it;
 * a chip gone from the line by any other edit is detached when it is sent, so
 * what goes is exactly what the line shows.
 */
class ImageChips {
    private val held = LinkedHashMap<String, Attachment>()
    private var next = 1

    val attachments: Collection<Attachment> get() = held.values

    fun isEmpty() = held.isEmpty()

    /** The chip for [attachment], a picture of [picture]'s size and type, numbered in the line. */
    fun add(attachment: Attachment, picture: Picture?): String {
        val type = picture?.extension ?: Pictures.typeOf(attachment.filename)?.let(Pictures::extensionOf) ?: "image"
        val size = picture?.size?.let { (w, h) -> "$w×$h " } ?: ""
        val chip = "[image ${next++}: $size$type]"
        held[chip] = attachment
        return chip
    }

    /**
     * What Backspace at [cursor] takes, where it is a chip: the chip that ends
     * there, or the one before the space a paste leaves after it - its text,
     * and how many characters to delete.
     */
    fun endingAt(line: CharSequence, cursor: Int): Pair<String, Int>? {
        fun at(end: Int) = held.keys.firstOrNull { chip -> end >= chip.length && end <= line.length && line.subSequence(end - chip.length, end) == chip }
        at(cursor)?.let { return it to it.length }
        if (cursor > 0 && line[cursor - 1] == ' ') at(cursor - 1)?.let { return it to it.length + 1 }
        return null
    }

    /** Forgets [chip], handing back the attachment it stood for. */
    fun remove(chip: String): Attachment? = held.remove(chip)

    /**
     * A line as it is sent: its chips taken out of the text, the attachments of
     * those still in it, and those of chips edited away. Starts the numbering again.
     */
    fun take(line: String): Taken {
        val kept = held.filterKeys { line.contains(it) }
        val dropped = held.filterKeys { !line.contains(it) }.values.toList()
        var text = line
        kept.keys.forEach { text = text.replace(it, "") }
        held.clear()
        next = 1
        return Taken(text.replace(Regex("[ \\t]{2,}"), " ").trim(), kept.values.toList(), dropped)
    }

    data class Taken(val text: String, val pictures: List<Attachment>, val dropped: List<Attachment>)
}
