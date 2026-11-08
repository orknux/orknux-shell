package io.mszymanski.orknux.shell

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * Links in the terminal: clickable through OSC 8 where the terminal draws them,
 * and written out as `text (url)` where it would not.
 */
class Links(val osc8: Boolean, private val server: String?) {

    /** An address the server gave, made absolute: a link to `/chats/4` means nothing once it has left the server. */
    fun absolute(url: String): String = when {
        server != null && url.startsWith("/") && !url.startsWith("//") -> server.trimEnd('/') + url
        else -> url
    }

    /** [text] linked to [url]; in plain mode the address follows the text unless they are the same. */
    fun link(text: String, url: String, styled: (String) -> String = { it }): String {
        val target = absolute(url)
        return if (osc8) {
            hyperlink(target, styled(text))
        } else if (text == url || text == target) {
            styled(target)
        } else {
            styled(text) + " ($target)"
        }
    }

    companion object {
        fun hyperlink(url: String, text: String) = "\u001b]8;;$url\u001b\\$text\u001b]8;;\u001b\\"

        /** `auto` makes links clickable unless the terminal is one known not to draw OSC 8, or there is no colour at all. */
        fun osc8For(setting: String, depth: ColourDepth, env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Boolean = when (setting) {
            "osc8" -> true
            "plain" -> false
            else -> when {
                depth == ColourDepth.NONE -> false
                env["TERM"] == "dumb" || env["TERM"] == "linux" -> false
                env["TERM_PROGRAM"] == "Apple_Terminal" -> false
                Platform.isWindows(os) -> env.containsKey("WT_SESSION")
                else -> true
            }
        }

        /**
         * Opens an address - a page in the browser, a file in the program that
         * opens it. Under WSL that is Windows' to do, since the person's desktop
         * is Windows': explorer.exe, handed a file as the Windows path it is.
         */
        fun open(url: String, os: String = System.getProperty("os.name"), env: Map<String, String> = System.getenv()) {
            val command = when {
                env.containsKey("WSL_DISTRO_NAME") -> listOf("explorer.exe", windowsTarget(url))
                Platform.isWindows(os) -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                os.lowercase().contains("mac") -> listOf("open", url)
                else -> listOf("xdg-open", url)
            }
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }

        /**
         * The `file:` address of a path an answer names - `C:\\Users\\me\\a.csv`,
         * `/home/me/a.csv`, `~/a.csv` - for the terminal to open on a click; null
         * for anything that is not one. A single word after a slash (`/agent`)
         * is a command, not a path. Under WSL, where the terminal is Windows',
         * `/mnt/c/...` is the drive it is and any other path is reached through
         * `\\\\wsl.localhost\\<distro>`, so a click opens it on Windows.
         */
        fun fileTarget(text: String, env: Map<String, String> = System.getenv(), home: String = System.getProperty("user.home")): String? {
            val path = text.trim()
            if (path.isEmpty() || path.contains('\n') || path.contains("://")) return null
            fun uri(p: String, host: String = ""): String? =
                runCatching { URI("file", host, if (p.startsWith("/")) p else "/$p", null).toASCIIString() }.getOrNull()
            Regex("""^([A-Za-z]):[\\/](.*)$""").find(path)?.let { m ->
                return uri(m.groupValues[1].uppercase() + ":/" + m.groupValues[2].replace('\\', '/'))
            }
            val posix = when {
                path.startsWith("~/") -> home.trimEnd('/') + path.substring(1)
                path.startsWith("/") -> path
                else -> return null
            }
            val rest = posix.trimEnd('/').removePrefix("/")
            if (!rest.contains('/') && !rest.contains('.')) return null
            val distro = env["WSL_DISTRO_NAME"]
            if (distro != null) {
                Regex("""^/mnt/([a-z])(/.*)?$""").find(posix)?.let { m -> return uri(m.groupValues[1].uppercase() + ":" + (m.groupValues[2].ifEmpty { "/" })) }
                return uri("/$distro$posix", "wsl.localhost")
            }
            return uri(posix)
        }

        /** What explorer.exe takes for an address: a file: one as its Windows path, anything else as it is. */
        fun windowsTarget(url: String): String {
            if (!url.startsWith("file:")) return url
            val uri = runCatching { URI(url) }.getOrNull() ?: return url
            val path = uri.path ?: return url
            return when {
                !uri.host.isNullOrEmpty() -> "\\\\" + uri.host + path.replace('/', '\\')
                Regex("^/[A-Za-z]:").containsMatchIn(path) -> path.removePrefix("/").replace('/', '\\')
                else -> url
            }
        }

        /** A `file://` address for a path, for a finding's file:line to open in an editor that takes one. */
        fun fileUrl(path: Path): String = path.toAbsolutePath().normalize().toUri().toString()
    }
}

/**
 * A file path pasted or dropped into the input on its own, as terminals paste
 * one: bare, quoted, with spaces escaped by a backslash, as a `file://`
 * address, or as a Windows path. Several are taken where all of them are files.
 */
object PastedPaths {
    private val PATH_START = Regex("""^(/|~[/\\]|~$|\./|\.\./|[A-Za-z]:[\\/]|\\\\|file://)""")

    fun detect(line: String, home: String = System.getProperty("user.home"), exists: (String) -> Boolean = { Files.isRegularFile(Path.of(it)) }): List<String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return null
        // The whole line as one path first: a Windows path with spaces arrives unquoted and unescaped.
        clean(unquote(trimmed), home)?.let { if (exists(it)) return listOf(it) }
        val tokens = tokens(trimmed) ?: return null
        val paths = tokens.map { clean(it, home) ?: return null }
        return paths.takeIf { it.isNotEmpty() && it.all(exists) }
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && (s.first() == '"' && s.last() == '"' || s.first() == '\'' && s.last() == '\'')) s.substring(1, s.length - 1) else s

    /** A path-like token with `~` and `file://` resolved; null where it does not look like a path at all. */
    fun clean(token: String, home: String): String? {
        if (!PATH_START.containsMatchIn(token)) return null
        return when {
            token.startsWith("file://") -> runCatching { Path.of(URI(token)).toString() }.getOrNull()
            token == "~" -> home
            token.startsWith("~/") || token.startsWith("~\\") -> home + token.substring(1)
            else -> token
        }
    }

    /** Shell-like words: quotes group, and outside a Windows path a backslash escapes the next character. */
    fun tokens(line: String): List<String>? {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        var quote: Char? = null
        var i = 0
        var inWord = false
        fun windowsWord() = Regex("""^[A-Za-z]:\\|^\\\\""").containsMatchIn(word)
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> if (c == quote) quote = null else word.append(c)
                c == '"' || c == '\'' -> { quote = c; inWord = true }
                c == '\\' && i + 1 < line.length && !windowsWord() && !(word.isEmpty() && line[i + 1] == '\\') &&
                    !(word.length == 2 && word[1] == ':') -> { word.append(line[i + 1]); i++; inWord = true }
                c.isWhitespace() -> {
                    if (inWord) out += word.toString()
                    word.setLength(0)
                    inWord = false
                }
                else -> { word.append(c); inWord = true }
            }
            i++
        }
        if (quote != null) return null
        if (inWord) out += word.toString()
        return out
    }
}
