package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigTest {

    @Test
    fun `a missing file is the defaults`(@TempDir dir: Path) {
        val config = ConfigStore(dir.resolve("none.json")).load()
        assertNull(config.server)
        assertEquals("auto", config.mode)
        assertEquals(CommandRules.DEFAULT_ASK, config.commands.ask)
        assertTrue(config.commands.allow.isEmpty())
        assertTrue(!config.signedIn)
    }

    @Test
    fun `everything saved is read back`(@TempDir dir: Path) {
        val store = ConfigStore(dir.resolve("sub").resolve("orknux-shell.json"))
        val config = Config(
            server = "https://orknux.example.com",
            workspaceId = "3", workspace = "Ops", agentId = "9", agent = "Builder",
            mode = "ask", shell = "pwsh",
            commands = CommandRules(allow = listOf("^ls"), ask = listOf("rm"), deny = listOf("^dd")),
            theme = mapOf("accent" to "#112233"),
            cookies = mapOf("SESSION" to "abc"), user = "alice", thinking = "expanded", links = "plain",
        )
        store.save(config)
        assertEquals(config, store.load())
        assertTrue(store.load().signedIn)
    }

    @Test
    fun `the file is readable by its owner only`(@TempDir dir: Path) {
        val store = ConfigStore(dir.resolve("orknux-shell.json"))
        store.save(Config(server = "http://x", token = "orkx_secret"))
        if (dir.fileSystem.supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.path)))
        }
    }

    @Test
    fun `an empty ask list stays empty rather than going back to the defaults`(@TempDir dir: Path) {
        val path = dir.resolve("c.json")
        Files.writeString(path, """{"server":"http://x","commands":{"ask":[],"deny":["^rm"]},"mode":"nonsense"}""")
        val config = ConfigStore(path).load()
        assertEquals(emptyList(), config.commands.ask)
        assertEquals(listOf("^rm"), config.commands.deny)
        assertEquals("auto", config.mode)
    }

    @Test
    fun `update keeps what another writer saved`(@TempDir dir: Path) {
        val store = ConfigStore(dir.resolve("c.json"))
        store.save(Config(server = "http://x", cookies = mapOf("SESSION" to "one")))
        store.update { it.copy(cookies = mapOf("SESSION" to "two")) }
        store.update { it.copy(commands = it.commands.copy(allow = it.commands.allow + "^ls\\b")) }
        val config = store.load()
        assertEquals(mapOf("SESSION" to "two"), config.cookies)
        assertEquals(listOf("^ls\\b"), config.commands.allow)
    }

    @Test
    fun `the file lives in the platform's place`() {
        assertEquals(
            Path.of("/home/a/.config/orknux/orknux-shell.json"),
            ConfigPaths.configFile("Linux", emptyMap(), "/home/a"),
        )
        assertEquals(
            Path.of("/xdg/orknux/orknux-shell.json"),
            ConfigPaths.configFile("Linux", mapOf("XDG_CONFIG_HOME" to "/xdg"), "/home/a"),
        )
        assertEquals(
            Path.of("C:\\Users\\a\\AppData\\Roaming", "Orknux", "orknux-shell.json"),
            ConfigPaths.configFile("Windows 11", mapOf("APPDATA" to "C:\\Users\\a\\AppData\\Roaming"), "C:\\Users\\a"),
        )
        assertEquals(Path.of("/given.json"), ConfigPaths.configFile("Linux", mapOf("ORKNUX_SHELL_CONFIG" to "/given.json"), "/home/a"))
    }

    @Test
    fun `the server address is made whole`() {
        assertEquals("https://orknux.example.com", OrknuxClient.normaliseServer("orknux.example.com/"))
        assertEquals("http://localhost:8080", OrknuxClient.normaliseServer("localhost:8080"))
        assertEquals("http://localhost:8080", OrknuxClient.originOf("http://localhost:8080"))
        assertEquals("https://o.example.com", OrknuxClient.originOf("https://o.example.com/orknux"))
    }

    @Test
    fun `theme colours are the defaults, overridable, and fall back to 256 colours`() {
        val theme = Theme(mapOf("accent" to "#ff0000", "bogus" to "#000000", "text" to "red"), ColourDepth.TRUE)
        assertEquals("#ff0000", theme.colours["accent"])
        assertEquals("#e4e4e7", theme.colours["text"])
        assertEquals(listOf("bogus=#000000", "text=red"), theme.unknown)
        assertEquals("\u001b[38;2;255;0;0mx\u001b[0m", theme.accent("x"))
        assertEquals("\u001b[38;5;196mx\u001b[0m", Theme(mapOf("accent" to "#ff0000"), ColourDepth.INDEXED).accent("x"))
        assertEquals("x", Theme(depth = ColourDepth.NONE).accent("x"))
        assertEquals(ColourDepth.TRUE, ColourDepth.detect(mapOf("COLORTERM" to "truecolor")))
        assertEquals(ColourDepth.INDEXED, ColourDepth.detect(mapOf("TERM" to "xterm-256color")))
        assertEquals(ColourDepth.TRUE, ColourDepth.detect(mapOf("WT_SESSION" to "1")))
        assertEquals(ColourDepth.NONE, ColourDepth.detect(mapOf("NO_COLOR" to "1", "COLORTERM" to "truecolor")))
    }
}
