package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Which commands run without asking, which are asked about, and which never run. Java regular expressions. */
data class CommandRules(
    val allow: List<String> = emptyList(),
    val ask: List<String> = DEFAULT_ASK,
    val deny: List<String> = emptyList(),
) {
    companion object {
        /** Asked about out of the box: what destroys data or the machine in one line. */
        val DEFAULT_ASK = listOf(
            """\brm\s+-[a-zA-Z]*r[a-zA-Z]*f""",
            """\brm\s+-[a-zA-Z]*f[a-zA-Z]*r""",
            """\bmkfs(\.\w+)?\b""",
            """\bdd\s+.*\bof=/dev/""",
            """\bdd\s+if=""",
            """>\s*/dev/(sd|nvme|hd|vd)""",
            """\bshutdown\b|\breboot\b|\bpoweroff\b|\bhalt\b""",
            """\bgit\s+push\b.*--force|\bgit\s+push\b.*\s-f\b""",
            """\bgit\s+reset\s+--hard\b""",
            """\bchmod\s+-R\b|\bchown\s+-R\b""",
            """(?i)\bRemove-Item\b.*-Recurse""",
            """(?i)\bFormat-Volume\b|\bClear-Disk\b""",
            """(?i)\brd\s+/s\b|\brmdir\s+/s\b|\bdel\s+/s\b""",
        )
    }
}

/**
 * The settings, the sign-in and where the person last was.
 *
 * One file, written with permissions only its owner can read, because the
 * session cookie or access token in it is as good as the password for as long
 * as it lasts.
 */
data class Config(
    val server: String? = null,
    val workspaceId: String? = null,
    val workspace: String? = null,
    val agentId: String? = null,
    val agent: String? = null,
    /** `auto`, `ask` or `plan`. */
    val mode: String = "auto",
    /** The shell local commands run in; null picks bash, sh or PowerShell by platform. */
    val shell: String? = null,
    val commands: CommandRules = CommandRules(),
    val theme: Map<String, String> = emptyMap(),
    /** An `orkx_` access token, used instead of a session where present. */
    val token: String? = null,
    /** The session's cookies, by name. */
    val cookies: Map<String, String> = emptyMap(),
    val user: String? = null,
    /** How a model's thinking is shown once it ends: `collapsed` to one line, `expanded` in full, or `hidden`. Live while it runs, except hidden. */
    val thinking: String = "collapsed",
    /** Links in answers: `auto` decides by the terminal, `osc8` makes them clickable, `plain` writes the address out. */
    val links: String = "auto",
    /** Whether to look for a newer Shell once a day; `ORKNUX_SHELL_UPDATES=off` says no as well. */
    val updates: Boolean = true,
    /** When orknux.ai was last asked for an update, in epoch milliseconds. */
    val updateCheckedAt: Long? = null,
    /** The newest version that check found, so a day without asking still says it is out. */
    val updateLatest: String? = null,
    /** The shortcuts set with /config, by action - `send-now` to `ctrl+s`; what is not here is its default. */
    val keys: Map<String, String> = emptyMap(),
) {
    val signedIn: Boolean get() = server != null && (token != null || cookies.isNotEmpty())

    fun toJson(): ObjectNode {
        val root = Json.obj()
        server?.let { root.put("server", it) }
        workspaceId?.let { root.put("workspaceId", it) }
        workspace?.let { root.put("workspace", it) }
        agentId?.let { root.put("agentId", it) }
        agent?.let { root.put("agent", it) }
        root.put("mode", mode)
        shell?.let { root.put("shell", it) }
        root.put("thinking", thinking)
        root.put("links", links)
        root.put("updates", updates)
        updateCheckedAt?.let { root.put("updateCheckedAt", it) }
        updateLatest?.let { root.put("updateLatest", it) }
        val rules = root.putObject("commands")
        listOf("allow" to commands.allow, "ask" to commands.ask, "deny" to commands.deny).forEach { (name, list) ->
            val array = rules.putArray(name)
            list.forEach { array.add(it) }
        }
        val colours = root.putObject("theme")
        theme.toSortedMap().forEach { (k, v) -> colours.put(k, v) }
        if (keys.isNotEmpty()) {
            val bound = root.putObject("keys")
            keys.toSortedMap().forEach { (k, v) -> bound.put(k, v) }
        }
        user?.let { root.put("user", it) }
        token?.let { root.put("token", it) }
        if (cookies.isNotEmpty()) {
            val jar = root.putObject("cookies")
            cookies.forEach { (k, v) -> jar.put(k, v) }
        }
        return root
    }

    companion object {
        val THINKING = listOf("collapsed", "expanded", "hidden")
        val LINKS = listOf("auto", "osc8", "plain")

        fun fromJson(root: JsonNode): Config {
            val rules = root.get("commands")
            val theme = root.get("theme")?.takeIf { it.isObject }?.properties()
                ?.mapNotNull { (k, v) -> if (v.isTextual) k to v.textValue() else null }?.toMap() ?: emptyMap()
            val keys = root.get("keys")?.takeIf { it.isObject }?.properties()
                ?.mapNotNull { (k, v) -> if (v.isTextual) k to v.textValue() else null }?.toMap() ?: emptyMap()
            val cookies = root.get("cookies")?.takeIf { it.isObject }?.properties()
                ?.mapNotNull { (k, v) -> if (v.isTextual) k to v.textValue() else null }?.toMap() ?: emptyMap()
            return Config(
                server = root.str("server"),
                workspaceId = root.str("workspaceId"),
                workspace = root.str("workspace"),
                agentId = root.str("agentId"),
                agent = root.str("agent"),
                mode = root.str("mode")?.takeIf { it in Modes.ALL } ?: "auto",
                shell = root.str("shell"),
                commands = CommandRules(
                    allow = if (rules?.has("allow") == true) rules.strings("allow") else emptyList(),
                    ask = if (rules?.has("ask") == true) rules.strings("ask") else CommandRules.DEFAULT_ASK,
                    deny = if (rules?.has("deny") == true) rules.strings("deny") else emptyList(),
                ),
                theme = theme,
                token = root.str("token"),
                cookies = cookies,
                user = root.str("user"),
                thinking = root.str("thinking")?.takeIf { it in THINKING } ?: "collapsed",
                links = root.str("links")?.takeIf { it in LINKS } ?: "auto",
                updates = root.get("updates")?.takeIf { it.isBoolean }?.booleanValue() ?: true,
                updateCheckedAt = root.get("updateCheckedAt")?.takeIf { it.isNumber }?.asLong(),
                updateLatest = root.str("updateLatest"),
                keys = keys,
            )
        }
    }
}

/** Where the configuration lives, by platform. Taken apart from the file system so both platforms can be tested on one. */
object ConfigPaths {
    const val FILE = "orknux-shell.json"

    fun configFile(os: String = System.getProperty("os.name"), env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): Path {
        env["ORKNUX_SHELL_CONFIG"]?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
        return if (Platform.isWindows(os)) {
            val appData = env["APPDATA"]?.takeIf { it.isNotBlank() } ?: Path.of(home, "AppData", "Roaming").toString()
            Path.of(appData, "Orknux", FILE)
        } else {
            val base = env["XDG_CONFIG_HOME"]?.takeIf { it.isNotBlank() } ?: Path.of(home, ".config").toString()
            Path.of(base, "orknux", FILE)
        }
    }
}

object Platform {
    fun isWindows(os: String = System.getProperty("os.name")): Boolean = os.lowercase().startsWith("windows")
}

/** Reads and writes [Config] at one path. */
class ConfigStore(val path: Path) {

    fun exists(): Boolean = Files.exists(path)

    fun load(): Config {
        if (!Files.exists(path)) return Config()
        val text = Files.readString(path)
        if (text.isBlank()) return Config()
        return Config.fromJson(Json.parse(text))
    }

    /** Written to a file beside it and moved over, so a crash mid-write leaves the old file, never half of one. */
    @Synchronized
    fun save(config: Config) {
        path.parent?.let { Files.createDirectories(it) }
        val temporary = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.deleteIfExists(temporary)
        if (posix()) {
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } else {
            Files.createFile(temporary)
        }
        Files.writeString(temporary, Json.pretty(config.toJson()) + "\n")
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (posix()) Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    }

    /** Read, changed and written as one step, so the update check in the background and the chat do not undo each other. */
    @Synchronized
    fun update(change: (Config) -> Config): Config {
        val next = change(load())
        save(next)
        return next
    }

    private fun posix(): Boolean = runCatching { path.fileSystem.supportedFileAttributeViews().contains("posix") }.getOrDefault(false)
}
