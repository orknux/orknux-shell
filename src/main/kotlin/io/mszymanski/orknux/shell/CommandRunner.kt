package io.mszymanski.orknux.shell

import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What the agent asked to run: the `run` action's arguments. */
data class RunRequest(
    val command: String,
    val cwd: String?,
    val timeoutSeconds: Int?,
    val dangerous: Boolean,
    val reason: String?,
)

data class RunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val seconds: Double,
    val timedOut: Boolean,
)

/**
 * Which program a command line is handed to.
 *
 * Linux and macOS: `bash -lc`, or `sh -c` where there is no bash. Windows:
 * `powershell -NoProfile -Command`. A configured `shell` names the program;
 * the flags for the familiar ones are known, and anything else is taken as the
 * words to put before the command.
 */
object Shells {
    fun argv(command: String, configured: String?, os: String = System.getProperty("os.name"), bashFound: () -> Boolean = ::bashOnPath): List<String> {
        val chosen = configured?.trim()?.takeIf { it.isNotEmpty() }
        if (chosen != null) return prefixFor(chosen) + command
        return if (Platform.isWindows(os)) {
            listOf("powershell", "-NoProfile", "-Command", command)
        } else if (bashFound()) {
            listOf("bash", "-lc", command)
        } else {
            listOf("sh", "-c", command)
        }
    }

    fun prefixFor(shell: String): List<String> {
        val words = shell.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size > 1) return words
        // Either separator: a Windows path is read the same on any machine, which is what lets this be tested anywhere.
        val name = words[0].substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe")
        return when (name) {
            "powershell", "pwsh" -> listOf(words[0], "-NoProfile", "-Command")
            "cmd" -> listOf(words[0], "/d", "/s", "/c")
            "bash", "zsh", "ksh" -> listOf(words[0], "-lc")
            "fish" -> listOf(words[0], "-c")
            else -> listOf(words[0], "-c")
        }
    }

    fun bashOnPath(): Boolean = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar).any { dir ->
        dir.isNotEmpty() && Files.isExecutable(Path.of(dir, "bash"))
    }
}

/**
 * Keeps the last [limit] characters of what a stream wrote, and whether more came.
 *
 * The tail rather than the head: a build or a test run says what went wrong at
 * the end, and that is the part an agent needs.
 */
class TailBuffer(private val limit: Int) {
    private val kept = StringBuilder()
    var dropped = false
        private set

    @Synchronized
    fun append(chars: CharArray, count: Int) {
        kept.appendRange(chars, 0, count)
        if (kept.length > limit * 2) trim()
    }

    @Synchronized
    fun append(text: String) {
        kept.append(text)
        if (kept.length > limit) trim()
    }

    private fun trim() {
        if (kept.length <= limit) return
        kept.delete(0, kept.length - limit)
        dropped = true
    }

    @Synchronized
    fun text(): String {
        trim()
        return kept.toString()
    }
}

/** Runs one command line in a shell, with a deadline, and what it printed. */
class CommandRunner(
    private val shell: String? = null,
    private val startDirectory: Path = Path.of(System.getProperty("user.dir")),
    private val outputLimit: Int = OUTPUT_LIMIT,
) {
    fun run(request: RunRequest): RunResult {
        val directory = resolveDirectory(request.cwd)
        require(Files.isDirectory(directory)) { "There is no directory $directory on this computer." }
        val timeout = (request.timeoutSeconds ?: DEFAULT_TIMEOUT_SECONDS).coerceIn(1, MAX_TIMEOUT_SECONDS)
        val builder = ProcessBuilder(Shells.argv(request.command, shell))
            .directory(directory.toFile())
            .redirectInput(ProcessBuilder.Redirect.from(nullDevice()))
        builder.environment()["ORKNUX_SHELL"] = "1"
        builder.environment().putIfAbsent("TERM", "dumb")
        val began = System.nanoTime()
        val process = builder.start()
        val out = TailBuffer(outputLimit)
        val err = TailBuffer(outputLimit)
        val readers = listOf(drain(process.inputStream, out), drain(process.errorStream, err))
        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) killTree(process)
        readers.forEach { it.join(2_000) }
        val seconds = (System.nanoTime() - began) / 1_000_000_000.0
        if (!finished) err.append("\n[Orknux Shell stopped it after $timeout s: the command and everything it started were killed.]")
        val exit = runCatching { process.exitValue() }.getOrDefault(-1)
        return RunResult(
            exitCode = exit,
            stdout = out.text(),
            stderr = err.text(),
            truncated = out.dropped || err.dropped,
            seconds = Math.round(seconds * 100) / 100.0,
            timedOut = !finished,
        )
    }

    fun resolveDirectory(cwd: String?): Path {
        val given = cwd?.trim()?.takeIf { it.isNotEmpty() } ?: return startDirectory
        val home = System.getProperty("user.home")
        val expanded = when {
            given == "~" -> home
            given.startsWith("~/") || given.startsWith("~\\") -> home + given.substring(1)
            else -> given
        }
        return startDirectory.resolve(expanded).normalize()
    }

    private fun drain(stream: InputStream, into: TailBuffer): Thread = Thread.ofPlatform().daemon().start {
        InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val n = try { reader.read(buffer) } catch (_: Exception) { -1 }
                if (n < 0) break
                into.append(buffer, n)
            }
        }
    }

    companion object {
        const val OUTPUT_LIMIT = 30_000
        const val DEFAULT_TIMEOUT_SECONDS = 120
        const val MAX_TIMEOUT_SECONDS = 3_600

        /** The command and everything it started, children first, so nothing is left orphaned and running. */
        fun killTree(process: Process) {
            val handle = process.toHandle()
            val descendants = runCatching { handle.descendants().toList() }.getOrDefault(emptyList())
            descendants.reversed().forEach { runCatching { it.destroyForcibly() } }
            process.destroyForcibly()
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            descendants.forEach { runCatching { it.destroyForcibly() } }
        }

        private fun nullDevice(): File = File(if (Platform.isWindows()) "NUL" else "/dev/null")
    }
}
