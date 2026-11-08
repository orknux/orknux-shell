package io.mszymanski.orknux.shell

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** What `/review` was asked: a target (nothing for the uncommitted changes) and whether to apply the fixes after. */
data class ReviewRequest(val target: String?, val fix: Boolean)

/** The change gathered for review: the diff, what it covers in words, and what was cut. */
data class ReviewDiff(val root: Path, val description: String, val diff: String, val files: List<String>, val cut: String?)

data class Finding(val severity: String, val file: String?, val line: Int?, val summary: String)

/**
 * `/review`: gathers a change with git where the Shell runs - which is where
 * the code is - and asks the agent to review it.
 */
object Review {
    const val LIMIT = 150_000

    fun parse(argument: String?): ReviewRequest {
        val words = argument?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()
        val fix = words.any { it == "--fix" }
        val rest = words.filter { it != "--fix" }
        require(rest.size <= 1) { "Review one thing at a time: /review [--fix] [branch or path]." }
        return ReviewRequest(rest.firstOrNull(), fix)
    }

    /** Gathers the change in [directory]'s repository; refuses in words where there is no repository or nothing to review. */
    fun gather(request: ReviewRequest, directory: Path, limit: Int = LIMIT): ReviewDiff {
        val top = git(directory, "rev-parse", "--show-toplevel")
        if (top.exit != 0) throw OrknuxException("$directory is not inside a git repository. /review reviews the changes of the repository Orknux Shell runs in.")
        val root = Path.of(top.out.trim())
        val hasHead = git(root, "rev-parse", "--verify", "--quiet", "HEAD").exit == 0
        val target = request.target
        val (description, parts) = when {
            target == null -> "the uncommitted changes in $root" to uncommitted(root, hasHead, null)
            git(root, "rev-parse", "--verify", "--quiet", "$target^{commit}").exit == 0 -> {
                val base = git(root, "merge-base", target, "HEAD")
                if (base.exit != 0) throw OrknuxException("$target and HEAD share no history to compare.")
                val diff = git(root, "diff", "--no-color", "--no-ext-diff", "${base.out.trim()}..HEAD")
                "the commits on HEAD since it left $target" to splitFiles(diff.out)
            }
            Files.exists(directory.resolve(target)) -> {
                val relative = root.relativize(directory.resolve(target).toAbsolutePath().normalize()).toString().ifEmpty { "." }
                "the uncommitted changes under $relative" to uncommitted(root, hasHead, relative)
            }
            else -> throw OrknuxException("\"$target\" is neither a branch or commit nor a path here.")
        }
        if (parts.isEmpty()) throw OrknuxException("Nothing to review: there are no changes in ${description.removePrefix("the ")}.")
        return cap(root, description, parts, limit)
    }

    /** Staged, unstaged and untracked text files - optionally under one path. */
    private fun uncommitted(root: Path, hasHead: Boolean, under: String?): List<Pair<String, String>> {
        val scope = under?.let { arrayOf("--", it) } ?: emptyArray()
        val tracked = if (hasHead) {
            git(root, "diff", "--no-color", "--no-ext-diff", "HEAD", *scope).out
        } else {
            git(root, "diff", "--no-color", "--no-ext-diff", "--cached", *scope).out + git(root, "diff", "--no-color", "--no-ext-diff", *scope).out
        }
        val untracked = git(root, "ls-files", "--others", "--exclude-standard", "-z", *scope).out.split('\u0000').filter { it.isNotEmpty() }
        return splitFiles(tracked) + untracked.mapNotNull { name -> newFile(root, name)?.let { name to it } }
    }

    /** An untracked file as a diff that adds it; nothing for a binary or a huge one. */
    private fun newFile(root: Path, name: String): String? {
        val file = root.resolve(name)
        if (!Files.isRegularFile(file) || Files.size(file) > 1_000_000) return null
        val bytes = Files.readAllBytes(file)
        if (bytes.take(8000).any { it == 0.toByte() }) return null
        val lines = String(bytes, Charsets.UTF_8).lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        return "diff --git a/$name b/$name\nnew file mode 100644\n--- /dev/null\n+++ b/$name\n@@ -0,0 +1,${lines.size} @@\n" +
            lines.joinToString("") { "+$it\n" }
    }

    /** A diff into its files, by their `diff --git` headers, so a cut can fall between files. */
    fun splitFiles(diff: String): List<Pair<String, String>> {
        if (diff.isBlank()) return emptyList()
        val parts = mutableListOf<Pair<String, String>>()
        val header = Regex("^diff --git a/(.*?) b/(.*)$", RegexOption.MULTILINE)
        val starts = header.findAll(diff).toList()
        if (starts.isEmpty()) return listOf("(diff)" to diff)
        starts.forEachIndexed { i, m ->
            val end = starts.getOrNull(i + 1)?.range?.first ?: diff.length
            parts += m.groupValues[2] to diff.substring(m.range.first, end).let { if (it.endsWith("\n")) it else it + "\n" }
        }
        return parts
    }

    /** Whole files up to [limit] characters; what did not fit is named, and a first file too large on its own is cut inside. */
    fun cap(root: Path, description: String, parts: List<Pair<String, String>>, limit: Int): ReviewDiff {
        val kept = StringBuilder()
        val included = mutableListOf<String>()
        val left = mutableListOf<String>()
        for ((name, text) in parts) {
            when {
                left.isNotEmpty() -> left += name
                kept.length + text.length <= limit -> { kept.append(text); included += name }
                included.isEmpty() -> { kept.append(text.take(limit)); included += name; left += "the rest of $name" }
                else -> left += name
            }
        }
        val total = parts.sumOf { it.second.length }
        val cut = if (left.isEmpty()) null else
            "The diff was cut at ${"%,d".format(limit)} of ${"%,d".format(total)} characters; left out: ${left.joinToString(", ")}."
        return ReviewDiff(root, description, kept.toString(), parts.map { it.first }, cut)
    }

    /** The skill to review with, where the agent can reach one called review or code-review. */
    fun skillFor(skills: List<Skill>): Skill? =
        skills.firstOrNull { Regex("(?i)^(code[-_ ]?review|review)$").matches(it.key) }
            ?: skills.firstOrNull { Regex("(?i)(code[-_ ]?review|review)").containsMatchIn(it.key) }

    /** What is sent: the skill's command where there is one, else the built-in instruction; the format of the findings either way. */
    fun message(diff: ReviewDiff, skillCommand: String?): String = buildString {
        if (skillCommand != null) {
            append(skillCommand).append(" Review ").append(diff.description).append(".\n\n")
        } else {
            append("Review this code change: ").append(diff.description).append(".\n\n")
            append(INSTRUCTION).append("\n\n")
        }
        append(VERIFY).append("\n\n")
        diff.cut?.let { append(it).append("\n\n") }
        append("Files: ").append(diff.files.joinToString(", ")).append("\n\n")
        append("```diff\n").append(diff.diff).append(if (diff.diff.endsWith("\n")) "" else "\n").append("```\n\n")
        append(FORMAT)
    }

    const val INSTRUCTION =
        "Find real bugs first - correctness, security, data loss, concurrency, error handling - then risky changes, " +
            "then cleanups. For each finding give the file and line, its severity (critical, high, medium or low), what " +
            "goes wrong and when, and a concrete fix. No style nits unless asked. Where there is nothing worth fixing, say " +
            "\"no findings\" plainly rather than inventing some."

    const val VERIFY =
        "The repository is on the person's computer, where you can run commands with terminal_run in its root. Use " +
            "read-only commands to check a finding before you report it - git show, git log, grep, reading files, running " +
            "the tests. Do not change any file now, and mark anything that would change files dangerous."

    const val FORMAT =
        "End your answer with the findings, one per line, in a fenced block marked findings, like:\n" +
            "```findings\nhigh | src/Main.kt:42 | The reader is never closed when parsing fails\n```\n" +
            "and with a block holding only the word none where there are no findings."

    fun fixMessage(): String =
        "Now apply the fixes for the findings you reported, one at a time, with terminal_run. Mark every command that " +
            "changes a file dangerous, so the person approves it. Run the tests afterwards where there are any, and say what " +
            "you changed."

    /** The findings block of an answer; empty where it has none or says none. */
    fun findings(answer: String): List<Finding> {
        val block = Regex("```findings\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL).findAll(answer).lastOrNull()?.groupValues?.get(1) ?: return emptyList()
        return block.lines().map { it.trim().removePrefix("-").trim() }.filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }.map { line ->
            val parts = line.split('|').map { it.trim() }
            if (parts.size >= 3) {
                val place = parts[1]
                val match = Regex("^(.*?):(\\d+)(?:[-:]\\d+)?$").find(place)
                Finding(parts[0].lowercase(), match?.groupValues?.get(1) ?: place.ifEmpty { null }, match?.groupValues?.get(2)?.toIntOrNull(), parts.drop(2).joinToString(" | "))
            } else {
                Finding("note", null, null, line)
            }
        }
    }

    /** The findings as a compact list, severity in the theme's colour and file:line a link that opens the file. */
    fun render(findings: List<Finding>, root: Path, theme: Theme, links: Links): List<String> {
        if (findings.isEmpty()) return listOf(theme.success("No findings."))
        return listOf(theme.bold("Findings")) + findings.map { f ->
            val severity = when (f.severity) {
                "critical", "high" -> theme.danger(f.severity.padEnd(8))
                "medium" -> theme.warning(f.severity.padEnd(8))
                "low" -> theme.muted(f.severity.padEnd(8))
                else -> theme.text(f.severity.padEnd(8))
            }
            val place = f.file?.let { file ->
                val label = file + (f.line?.let { ":$it" } ?: "")
                links.link(label, Links.fileUrl(root.resolve(file))) { theme.accent(it) } + " "
            } ?: ""
            "  $severity $place${f.summary}"
        }
    }

    data class Ran(val exit: Int, val out: String)

    fun git(directory: Path, vararg args: String): Ran {
        val process = try {
            ProcessBuilder(listOf("git", "-c", "core.quotepath=off", "--no-pager") + args)
                .directory(directory.toFile())
                .redirectErrorStream(false)
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File(if (Platform.isWindows()) "NUL" else "/dev/null")))
                .start()
        } catch (missing: java.io.IOException) {
            throw OrknuxException("git is not installed here, and /review needs it.")
        }
        val err = Thread.ofVirtual().start { process.errorStream.readAllBytes() }
        val out = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            CommandRunner.killTree(process)
            throw OrknuxException("git ${args.firstOrNull()} took longer than a minute.")
        }
        err.join()
        return Ran(process.exitValue(), out)
    }
}
