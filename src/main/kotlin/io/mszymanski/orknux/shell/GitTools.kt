package io.mszymanski.orknux.shell

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Setting up a GitHub token, scoped to the one session that set it.
 *
 * This is the one credential-shaped thing the shell holds, and it is held the
 * way everything else here is: inside the jailed root, and only there. The
 * token is written to this session's own git credential file and named by this
 * session's own git config, both of which every process on the session already
 * reads through `GIT_CONFIG_GLOBAL` (see [ShellSession.gitEnvironment]). So a
 * `git clone`, `fetch` or `push` in this session authenticates, a command in
 * another session — with its own config pointing at its own file — does not
 * see this token at all, and both tokens vanish when their workspaces do.
 *
 * There is deliberately no tool to read a token back. It goes in, git uses it,
 * and it is gone with the session; nothing here hands it to a model.
 */
@Component
class GitTools {

    val tools: List<Tool> = listOf(
        Tool(
            name = "github_login",
            description = "Store a GitHub token for this session only, so git operations here authenticate. The " +
                "token is confined to this session's workspace and destroyed with it; other sessions keep their own " +
                "tokens independently. Does not echo the token back.",
            parameters = listOf(
                ToolParameter("token", "string", "A GitHub token (PAT, or an app/installation token)."),
                ToolParameter("host", "string", "The host it is for; `github.com` when absent.", required = false),
                ToolParameter("username", "string", "The username to pair with it; `x-access-token` when absent, which suits app and fine-grained tokens.", required = false),
            ),
            run = ::login,
        ),
        Tool(
            name = "github_logout",
            description = "Forget the GitHub token stored for this session.",
            parameters = listOf(
                ToolParameter("host", "string", "The host to forget; all hosts when absent.", required = false),
            ),
            run = ::logout,
        ),
    )

    private fun login(session: ShellSession, arguments: JsonNode): String {
        val token = arguments.requiredString("token").trim()
        if (token.isEmpty()) throw ToolRefusedException("`token` must not be empty")
        val host = arguments.optionalString("host")?.trim()?.ifEmpty { null } ?: "github.com"
        val username = arguments.optionalString("username")?.trim()?.ifEmpty { null } ?: "x-access-token"

        // The config names the store helper and the file it keeps credentials
        // in — both absolute, inside this session's root. Written whole rather
        // than through `git config`, so it is deterministic and needs no shell.
        Files.writeString(
            session.gitConfig,
            """
            [credential]
            	helper = store --file=${session.gitCredentials}
            """.trimIndent() + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        // The store helper matches a line by host, so a login for a second host
        // is added rather than replacing the first. A line for the same host is
        // dropped first, so re-logging-in replaces the token instead of
        // stacking a stale one above it.
        val line = "https://${enc(username)}:${enc(token)}@$host"
        val kept = existingCredentials(session).filterNot { it.substringAfter('@', "") == host }
        writeCredentials(session, kept + line)

        return "Stored a GitHub token for $host in this session as `$username`. " +
            "git clone/fetch/push over https to $host will use it. It is gone when the session ends."
    }

    private fun logout(session: ShellSession, arguments: JsonNode): String {
        val host = arguments.optionalString("host")?.trim()?.ifEmpty { null }
        if (host == null) {
            Files.deleteIfExists(session.gitCredentials)
            Files.deleteIfExists(session.gitConfig)
            return "Forgot every GitHub token stored for this session."
        }
        val kept = existingCredentials(session).filterNot { it.substringAfter('@', "") == host }
        writeCredentials(session, kept)
        return "Forgot the GitHub token for $host in this session."
    }

    private fun existingCredentials(session: ShellSession): List<String> =
        if (Files.exists(session.gitCredentials)) {
            Files.readAllLines(session.gitCredentials, StandardCharsets.UTF_8).filter { it.isNotBlank() }
        } else {
            emptyList()
        }

    private fun writeCredentials(session: ShellSession, lines: List<String>) {
        if (lines.isEmpty()) {
            Files.deleteIfExists(session.gitCredentials)
            return
        }
        Files.writeString(
            session.gitCredentials,
            lines.joinToString("\n") + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        // A token in a file is a token nobody else's account should read.
        runCatching {
            Files.setPosixFilePermissions(session.gitCredentials, PosixFilePermissions.fromString("rw-------"))
        }
    }

    /** URL-encode the parts of a credential that may carry a `:`, `@` or `/`. */
    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
