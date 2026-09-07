package io.mszymanski.orknux.shell

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * The file tools, shaped like DesktopCommander's so an agent that learned
 * that surface keeps working here.
 *
 * Every path goes through the session's [ShellSession.resolve] before it is
 * touched, which is the whole of the isolation story on this side: nothing in
 * this file can name a file outside the workspace, however the argument is
 * spelled. Reads are windowed in lines rather than unbounded, because these
 * answers land in a model's context and a log file is longer than one.
 */
@Component
class FileTools {

    val tools: List<Tool> = listOf(
        Tool(
            name = "read_file",
            description = "Read a file as text, a window of lines at a time.",
            parameters = listOf(
                ToolParameter("path", "string", "The file, inside the workspace."),
                ToolParameter("offset", "number", "First line to read, counted from 0.", required = false),
                ToolParameter("length", "number", "How many lines to read; $READ_LINES when absent.", required = false),
            ),
            run = ::read,
        ),
        Tool(
            name = "read_multiple_files",
            description = "Read several files in one call, each headed by its path.",
            parameters = listOf(
                ToolParameter("paths", "array", "The files to read.", items = "string"),
            ),
            run = ::readMany,
        ),
        Tool(
            name = "write_file",
            description = "Write text to a file, replacing or appending. Missing parent directories are created.",
            parameters = listOf(
                ToolParameter("path", "string", "The file, inside the workspace."),
                ToolParameter("content", "string", "What to write."),
                ToolParameter("mode", "string", "`rewrite` (the default) or `append`.", required = false),
            ),
            run = ::write,
        ),
        Tool(
            name = "edit_block",
            description = "Replace an exact block of text in a file. The old text must match exactly, including whitespace.",
            parameters = listOf(
                ToolParameter("file_path", "string", "The file, inside the workspace."),
                ToolParameter("old_string", "string", "The exact text to replace."),
                ToolParameter("new_string", "string", "What to put in its place."),
                ToolParameter("expected_replacements", "number", "How many occurrences to expect and replace; 1 when absent.", required = false),
            ),
            run = ::edit,
        ),
        Tool(
            name = "create_directory",
            description = "Create a directory, with any missing parents.",
            parameters = listOf(ToolParameter("path", "string", "The directory, inside the workspace.")),
            run = ::createDirectory,
        ),
        Tool(
            name = "list_directory",
            description = "List a directory's entries, marked [FILE] or [DIR].",
            parameters = listOf(ToolParameter("path", "string", "The directory, inside the workspace.")),
            run = ::list,
        ),
        Tool(
            name = "move_file",
            description = "Move or rename a file or directory.",
            parameters = listOf(
                ToolParameter("source", "string", "What to move."),
                ToolParameter("destination", "string", "Where to move it, inside the workspace."),
            ),
            run = ::move,
        ),
        Tool(
            name = "get_file_info",
            description = "Size, kind and timestamps of a file or directory.",
            parameters = listOf(ToolParameter("path", "string", "The file or directory, inside the workspace.")),
            run = ::info,
        ),
    )

    private fun read(session: ShellSession, arguments: JsonNode): String {
        val path = existing(session, arguments.requiredString("path"))
        if (Files.isDirectory(path)) throw ToolRefusedException("$path is a directory; list_directory reads those")

        val offset = (arguments.optionalLong("offset") ?: 0).coerceAtLeast(0).toInt()
        val length = (arguments.optionalLong("length") ?: READ_LINES).coerceAtLeast(1).toInt()

        val lines = readLines(path)
        if (offset >= lines.size && offset > 0) {
            return "[${lines.size} lines in total; there is no line $offset]"
        }
        val window = lines.drop(offset).take(length)
        val text = window.joinToString("\n")
        val after = lines.size - offset - window.size
        return if (after > 0) {
            "$text\n[$after more lines; continue with offset=${offset + window.size}]"
        } else {
            text
        }
    }

    private fun readMany(session: ShellSession, arguments: JsonNode): String {
        val paths = arguments.path("paths").takeIf { it.isArray && it.size() > 0 }
            ?: throw ToolRefusedException("`paths` is required and must name at least one file")
        // Indexed rather than iterated: a JsonNode-typed array does not hand
        // its elements to Kotlin's `map` under Jackson 3.
        return (0 until paths.size()).mapNotNull { paths.path(it).asString() }.joinToString("\n\n") { raw ->
            val header = "=== $raw ==="
            val body = runCatching {
                val path = existing(session, raw)
                readLines(path).take(READ_LINES.toInt()).joinToString("\n")
            }.getOrElse { failed -> "[could not read: ${failed.message}]" }
            "$header\n$body"
        }
    }

    private fun write(session: ShellSession, arguments: JsonNode): String {
        val path = session.resolve(arguments.requiredString("path"))
        val content = arguments.requiredString("content")
        val mode = arguments.optionalString("mode") ?: "rewrite"
        if (mode !in setOf("rewrite", "append")) {
            throw ToolRefusedException("`mode` is `rewrite` or `append`, not `$mode`")
        }
        if (Files.isDirectory(path)) throw ToolRefusedException("$path is a directory")

        path.parent?.let(Files::createDirectories)
        val options = if (mode == "append") {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } else {
            arrayOf(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
        Files.writeString(path, content, StandardCharsets.UTF_8, *options)
        return "Wrote ${content.length} characters to $path" + if (mode == "append") " (appended)" else ""
    }

    private fun edit(session: ShellSession, arguments: JsonNode): String {
        val path = existing(session, arguments.requiredString("file_path"))
        val old = arguments.requiredString("old_string")
        val new = arguments.requiredString("new_string")
        val expected = (arguments.optionalLong("expected_replacements") ?: 1).toInt()
        if (old.isEmpty()) throw ToolRefusedException("`old_string` must not be empty")

        val text = Files.readString(path, StandardCharsets.UTF_8)
        val found = occurrences(text, old)
        if (found != expected) {
            throw ToolRefusedException(
                "Expected $expected occurrence(s) of the old text in $path but found $found. " +
                    "Make the old text more specific, or pass expected_replacements=$found to mean all of them.",
            )
        }
        Files.writeString(path, text.replace(old, new), StandardCharsets.UTF_8)
        return "Replaced $found occurrence(s) in $path"
    }

    private fun createDirectory(session: ShellSession, arguments: JsonNode): String {
        val path = session.resolve(arguments.requiredString("path"))
        Files.createDirectories(path)
        return "Created $path"
    }

    private fun list(session: ShellSession, arguments: JsonNode): String {
        val path = existing(session, arguments.requiredString("path"))
        if (!Files.isDirectory(path)) throw ToolRefusedException("$path is not a directory")
        val entries = Files.list(path).use { held ->
            held.sorted(compareBy { it.fileName.toString() }).map { entry ->
                (if (Files.isDirectory(entry)) "[DIR] " else "[FILE] ") + entry.fileName
            }.toList()
        }
        return if (entries.isEmpty()) "$path is empty" else entries.joinToString("\n")
    }

    private fun move(session: ShellSession, arguments: JsonNode): String {
        val source = existing(session, arguments.requiredString("source"))
        val destination = session.resolve(arguments.requiredString("destination"))
        destination.parent?.let(Files::createDirectories)
        Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return "Moved $source to $destination"
    }

    private fun info(session: ShellSession, arguments: JsonNode): String {
        val path = existing(session, arguments.requiredString("path"))
        val lines = mutableListOf(
            "path: $path",
            "kind: " + if (Files.isDirectory(path)) "directory" else "file",
            "size: ${Files.size(path)}",
            "modified: ${Files.getLastModifiedTime(path)}",
        )
        runCatching {
            lines += "permissions: " + PosixFilePermissions.toString(Files.getPosixFilePermissions(path))
        }
        return lines.joinToString("\n")
    }

    /** The path, resolved into the jail and required to be there. */
    private fun existing(session: ShellSession, raw: String): Path =
        session.resolve(raw).takeIf(Files::exists)
            ?: throw ToolRefusedException("There is no $raw in this workspace")

    /** Lines however they are terminated, decoded as UTF-8 with bad bytes replaced rather than fatal. */
    private fun readLines(path: Path): List<String> =
        String(Files.readAllBytes(path), StandardCharsets.UTF_8).split("\n").map { it.removeSuffix("\r") }

    private fun occurrences(text: String, wanted: String): Int {
        var count = 0
        var at = text.indexOf(wanted)
        while (at >= 0) {
            count++
            at = text.indexOf(wanted, at + wanted.length)
        }
        return count
    }

    private companion object {
        /** The default read window, in lines. */
        const val READ_LINES = 1000L
    }
}
