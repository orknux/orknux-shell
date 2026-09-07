package io.mszymanski.orknux.shell

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.streams.asSequence

/**
 * Search, the two ways DesktopCommander offers it: by name and by content.
 *
 * Both walk the workspace and nothing else — the starting path goes through
 * the jail like every other path. Results are capped and the cap is said out
 * loud, because a truncated answer that does not admit it reads as "that was
 * everything".
 */
@Component
class SearchTools {

    val tools: List<Tool> = listOf(
        Tool(
            name = "search_files",
            description = "Find files and directories whose name contains the pattern, case-insensitively.",
            parameters = listOf(
                ToolParameter("path", "string", "Where to search from, inside the workspace."),
                ToolParameter("pattern", "string", "What a matching name contains."),
                ToolParameter("maxResults", "number", "The most paths to return; $NAME_RESULTS when absent.", required = false),
            ),
            run = ::searchFiles,
        ),
        Tool(
            name = "search_code",
            description = "Search file contents with a regular expression; answers as path:line: text.",
            parameters = listOf(
                ToolParameter("path", "string", "Where to search from, inside the workspace."),
                ToolParameter("pattern", "string", "The regular expression to look for."),
                ToolParameter("filePattern", "string", "A glob a file's name must match, e.g. `*.kt`.", required = false),
                ToolParameter("ignoreCase", "boolean", "Match case-insensitively; false when absent.", required = false),
                ToolParameter("maxResults", "number", "The most matching lines to return; $CODE_RESULTS when absent.", required = false),
            ),
            run = ::searchCode,
        ),
    )

    private fun searchFiles(session: ShellSession, arguments: JsonNode): String {
        val start = session.resolve(arguments.requiredString("path"))
        if (!Files.isDirectory(start)) throw ToolRefusedException("$start is not a directory in this workspace")
        val wanted = arguments.requiredString("pattern").lowercase()
        val most = (arguments.optionalLong("maxResults") ?: NAME_RESULTS).coerceAtLeast(1).toInt()

        val found = Files.walk(start).use { held ->
            held.asSequence()
                .filter { it != start && it.fileName.toString().lowercase().contains(wanted) }
                .map(Path::toString)
                .take(most + 1)
                .toList()
        }
        return when {
            found.isEmpty() -> "Nothing under $start has a name containing `$wanted`"
            found.size > most -> found.take(most).joinToString("\n") + "\n[more than $most matches; narrow the pattern]"
            else -> found.joinToString("\n")
        }
    }

    private fun searchCode(session: ShellSession, arguments: JsonNode): String {
        val start = session.resolve(arguments.requiredString("path"))
        if (!Files.exists(start)) throw ToolRefusedException("There is no ${arguments.requiredString("path")} in this workspace")
        val most = (arguments.optionalLong("maxResults") ?: CODE_RESULTS).coerceAtLeast(1).toInt()

        val flags = if (arguments.optionalBoolean("ignoreCase", absent = false)) Pattern.CASE_INSENSITIVE else 0
        val pattern = try {
            Pattern.compile(arguments.requiredString("pattern"), flags)
        } catch (bad: PatternSyntaxException) {
            throw ToolRefusedException("`pattern` is not a valid regular expression: ${bad.message}")
        }
        val names = arguments.optionalString("filePattern")?.let { glob ->
            try {
                FileSystems.getDefault().getPathMatcher("glob:$glob")
            } catch (bad: PatternSyntaxException) {
                throw ToolRefusedException("`filePattern` is not a valid glob: ${bad.message}")
            }
        }

        val matches = mutableListOf<String>()
        var clipped = false
        val files = Files.walk(start).use { held ->
            held.asSequence().filter(Files::isRegularFile).filter { file ->
                names == null || names.matches(file.fileName)
            }.toList()
        }
        outer@ for (file in files) {
            for ((index, line) in linesOf(file) ?: continue) {
                if (!pattern.matcher(line).find()) continue
                if (matches.size >= most) {
                    clipped = true
                    break@outer
                }
                matches += "$file:${index + 1}: ${line.take(LINE_LENGTH)}"
            }
        }
        return when {
            matches.isEmpty() -> "Nothing under $start matches"
            clipped -> matches.joinToString("\n") + "\n[stopped at $most matching lines; narrow the search]"
            else -> matches.joinToString("\n")
        }
    }

    /**
     * A file's lines with their indices, or null for one not worth grepping:
     * too big to be a source file, or binary by the oldest test there is — a
     * NUL byte near the front.
     */
    private fun linesOf(file: Path): List<IndexedValue<String>>? {
        if (Files.size(file) > FILE_LIMIT) return null
        val bytes = Files.readAllBytes(file)
        if (bytes.take(4096).contains(0)) return null
        return String(bytes, StandardCharsets.UTF_8).split("\n").withIndex().toList()
    }

    private companion object {
        const val NAME_RESULTS = 300L
        const val CODE_RESULTS = 300L
        const val LINE_LENGTH = 500
        const val FILE_LIMIT = 4 * 1024 * 1024L
    }
}
