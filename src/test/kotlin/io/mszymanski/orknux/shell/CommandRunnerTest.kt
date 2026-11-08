package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandRunnerTest {

    @Test
    fun `the shell is chosen by platform, or by the configuration`() {
        assertEquals(listOf("bash", "-lc", "ls"), Shells.argv("ls", null, "Linux") { true })
        assertEquals(listOf("sh", "-c", "ls"), Shells.argv("ls", null, "Linux") { false })
        assertEquals(listOf("powershell", "-NoProfile", "-Command", "dir"), Shells.argv("dir", null, "Windows 11") { true })
        assertEquals(listOf("pwsh", "-NoProfile", "-Command", "dir"), Shells.argv("dir", "pwsh", "Windows 11") { true })
        assertEquals(listOf("C:\\Windows\\System32\\cmd.exe", "/d", "/s", "/c", "dir"), Shells.argv("dir", "C:\\Windows\\System32\\cmd.exe", "Windows 10"))
        assertEquals(listOf("zsh", "-lc", "ls"), Shells.argv("ls", "zsh", "Mac OS X"))
        assertEquals(listOf("nu", "--commands", "ls"), Shells.argv("ls", "nu --commands", "Linux"))
        assertEquals(listOf("/bin/dash", "-c", "ls"), Shells.argv("ls", "/bin/dash", "Linux"))
    }

    @Test
    fun `the tail is kept, and cutting is said`() {
        val buffer = TailBuffer(10)
        buffer.append("0123456789abcdef")
        assertEquals("6789abcdef", buffer.text())
        assertTrue(buffer.dropped)
        val small = TailBuffer(10)
        small.append("abc")
        assertEquals("abc", small.text())
        assertFalse(small.dropped)
    }

    @Test
    fun `a command runs, and says what it printed and how it ended`(@TempDir dir: Path) {
        assumeTrue(!Platform.isWindows())
        val result = CommandRunner(startDirectory = dir).run(RunRequest("echo hi; echo oops >&2; pwd; exit 3", null, null, false, null))
        assertEquals(3, result.exitCode)
        assertEquals("hi\n${dir.toRealPath()}\n", result.stdout)
        assertEquals("oops\n", result.stderr)
        assertFalse(result.truncated)
        assertFalse(result.timedOut)
    }

    @Test
    fun `the working directory is the one asked for, and home is understood`(@TempDir dir: Path) {
        assumeTrue(!Platform.isWindows())
        Files.createDirectories(dir.resolve("sub"))
        val runner = CommandRunner(startDirectory = dir)
        assertEquals("${dir.resolve("sub").toRealPath()}\n", runner.run(RunRequest("pwd", "sub", null, false, null)).stdout)
        assertEquals(Path.of(System.getProperty("user.home")), runner.resolveDirectory("~"))
        val missing = runCatching { runner.run(RunRequest("pwd", "nowhere", null, false, null)) }
        assertTrue(missing.exceptionOrNull()?.message?.contains("There is no directory") == true)
    }

    @Test
    fun `long output is cut to its last thirty thousand characters`() {
        assumeTrue(!Platform.isWindows())
        val result = CommandRunner().run(RunRequest("seq 1 20000; echo END", null, null, false, null))
        assertTrue(result.truncated)
        assertEquals(30_000, result.stdout.length)
        assertTrue(result.stdout.endsWith("20000\nEND\n"))
    }

    @Test
    fun `a command past its time is killed with everything it started`(@TempDir dir: Path) {
        assumeTrue(!Platform.isWindows())
        val pidFile = dir.resolve("child.pid")
        val began = System.nanoTime()
        val result = CommandRunner(startDirectory = dir).run(
            RunRequest("sleep 60 & echo \$! > child.pid; sleep 60; echo never", null, timeoutSeconds = 1, dangerous = false, reason = null),
        )
        val seconds = (System.nanoTime() - began) / 1e9
        assertTrue(result.timedOut)
        assertTrue(seconds < 15, "took $seconds s")
        assertTrue(result.stderr.contains("stopped it after 1 s"), result.stderr)
        assertFalse(result.stdout.contains("never"))
        val child = Files.readString(pidFile).trim().toLong()
        Thread.sleep(300)
        // A killed child left to an init that does not reap (a container's PID 1) is a zombie: dead, but still listed.
        val stat = Path.of("/proc/$child/stat")
        val zombie = Files.exists(stat) && runCatching { Files.readString(stat).substringAfterLast(')').trim().startsWith("Z") }.getOrDefault(false)
        val alive = !zombie && ProcessHandle.of(child).map { it.isAlive }.orElse(false)
        assertFalse(alive, "the background child $child is still running")
    }
}
