package io.mszymanski.orknux.shell

/**
 * Markdown for a terminal, a line at a time.
 *
 * Fed the answer as it arrives and gives back only whole lines, so a code fence
 * or a list item is never drawn half-understood; [finish] flushes the rest.
 * Deliberately small: headings, emphasis, inline code, fenced code, lists,
 * quotes, rules, links and tables - what an agent's answer is made of. A table
 * is held until its last row has come, since every column's width depends on
 * every row, and then drawn whole.
 */
class Markdown(private val theme: Theme, private val width: () -> Int = { 100 }, private val links: Links = Links(false, null)) {
    private val pending = StringBuilder()

    /** Every link drawn since this was made, in order, once each: what `/open <n>` opens. */
    val found = mutableListOf<Pair<String, String>>()

    private fun remember(label: String, url: String) {
        val target = links.absolute(url)
        if (found.none { it.second == target }) found += label to target
    }
    private var inFence = false
    private val table = mutableListOf<String>()

    /** Takes a piece of text; returns the rendered lines it completed, each ending in a newline. */
    fun feed(piece: String): String {
        pending.append(piece)
        val out = StringBuilder()
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline).removeSuffix("\r")
            pending.delete(0, newline + 1)
            if (!inFence && line.trimStart().startsWith("|")) {
                table += line
                continue
            }
            out.append(flushTable())
            out.append(line(line)).append('\n')
        }
        return out.toString()
    }

    /** What is left, rendered; and the state cleared for the next answer. */
    fun finish(): String {
        val last = pending.toString().removeSuffix("\r")
        if (!inFence && last.trimStart().startsWith("|")) {
            table += last
            pending.setLength(0)
        }
        val rest = flushTable() + if (pending.isNotEmpty()) line(pending.toString()) + "\n" else ""
        pending.setLength(0)
        inFence = false
        return rest
    }

    fun render(text: String): String = feed(text) + finish()

    private fun line(raw: String): String {
        val trimmed = raw.trimStart()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            val language = trimmed.drop(3).trim()
            return if (inFence && language.isNotEmpty()) theme.muted("  $language") else ""
        }
        if (inFence) return "  " + theme.code(raw)
        if (trimmed.isEmpty()) return ""

        Regex("^(#{1,6})\\s+(.*)$").find(trimmed)?.let { m ->
            val level = m.groupValues[1].length
            val title = inline(m.groupValues[2])
            return if (level <= 2) theme.bold(theme.accent(title)) else theme.bold(title)
        }
        if (Regex("^([-*_])(\\s*\\1){2,}\\s*$").matches(trimmed)) {
            return theme.muted("─".repeat(width().coerceIn(10, 80)))
        }
        if (trimmed.startsWith(">")) {
            return theme.muted("│ ") + theme.muted(inline(trimmed.removePrefix(">").trimStart()))
        }
        val indent = raw.length - trimmed.length
        Regex("^[-*+]\\s+(\\[([ xX])]\\s+)?(.*)$").find(trimmed)?.let { m ->
            val box = when (m.groupValues[2]) {
                "" -> if (m.groupValues[1].isEmpty()) theme.accent("•") else theme.accent("☐")
                " " -> theme.accent("☐")
                else -> theme.success("☑")
            }
            return " ".repeat(indent + 2) + box + " " + inline(m.groupValues[3])
        }
        Regex("^(\\d+)[.)]\\s+(.*)$").find(trimmed)?.let { m ->
            return " ".repeat(indent + 2) + theme.accent(m.groupValues[1] + ".") + " " + inline(m.groupValues[2])
        }
        return inline(raw)
    }

    // ---- tables ----------------------------------------------------------------------------

    /** The table held so far, drawn, and forgotten; nothing when there is none. */
    private fun flushTable(): String {
        if (table.isEmpty()) return ""
        val rows = table.toList()
        table.clear()
        val separator = rows.getOrNull(1)?.let { cells(it) }?.takeIf { row -> row.isNotEmpty() && row.all { Regex("^:?-{1,}:?$").matches(it.trim()) } }
        // Pipes that are not a table - no |---| line under the first - are text, as they were written.
        if (separator == null) return rows.joinToString("") { inline(it) + "\n" }
        val body = listOf(rows[0]) + rows.drop(2)
        return drawTable(body.map { row -> cells(row).map { inline(it.trim()) } }, separator.map { alignmentOf(it.trim()) })
    }

    private enum class Align { LEFT, CENTRE, RIGHT }

    private fun alignmentOf(marker: String) = when {
        marker.startsWith(":") && marker.endsWith(":") -> Align.CENTRE
        marker.endsWith(":") -> Align.RIGHT
        else -> Align.LEFT
    }

    /** A row's cells: split on the pipes that are not escaped and not inside `code`, without the outer ones. */
    private fun cells(row: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var code = false
        var i = 0
        val text = row.trim()
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] == '|' -> { cell.append('|'); i++ }
                c == '`' -> { code = !code; cell.append(c) }
                c == '|' && !code -> { out += cell.toString(); cell.setLength(0) }
                else -> cell.append(c)
            }
            i++
        }
        out += cell.toString()
        if (text.startsWith("|")) out.removeAt(0)
        if (text.endsWith("|") && out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)
        return out
    }

    /** Words onto lines of at most [width] columns; a word longer than that is cut where it has to be. */
    private fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            var rest = word
            while (rest.length > width) {
                if (line.isNotEmpty()) { lines += line.toString(); line = StringBuilder() }
                lines += rest.take(width)
                rest = rest.drop(width)
            }
            if (rest.isEmpty()) continue
            if (line.isNotEmpty() && line.length + 1 + rest.length > width) { lines += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(rest)
        }
        if (line.isNotEmpty() || lines.isEmpty()) lines += line.toString()
        return lines
    }

    private fun drawTable(rows: List<List<String>>, align: List<Align>): String {
        val columns = maxOf(rows.maxOf { it.size }, align.size)
        val grid = rows.map { row -> List(columns) { row.getOrElse(it) { "" } } }
        val widths = IntArray(columns) { c -> grid.maxOf { visibleLength(it[c]) }.coerceAtLeast(1) }
        // Narrowed to the screen, widest column first: a cell too wide for its column wraps onto more lines.
        val room = (width() - 1 - (3 * columns + 1)).coerceAtLeast(columns * 3)
        while (widths.sum() > room) {
            val widest = widths.indices.maxBy { widths[it] }
            if (widths[widest] <= 3) break
            widths[widest]--
        }
        fun rule(left: String, middle: String, right: String) =
            theme.muted(left + widths.joinToString(middle) { "─".repeat(it + 2) } + right) + "\n"
        fun cellLines(text: String, width: Int): List<String> =
            if (visibleLength(text) <= width) listOf(text) else wrap(stripAnsi(text), width)
        fun pad(text: String, width: Int, how: Align): String {
            val gap = (width - visibleLength(text)).coerceAtLeast(0)
            return when (how) {
                Align.LEFT -> text + " ".repeat(gap)
                Align.RIGHT -> " ".repeat(gap) + text
                Align.CENTRE -> " ".repeat(gap / 2) + text + " ".repeat(gap - gap / 2)
            }
        }
        val out = StringBuilder(rule("┌", "┬", "┐"))
        grid.forEachIndexed { r, row ->
            val wrapped = row.mapIndexed { c, text -> cellLines(text, widths[c]) }
            for (line in 0 until wrapped.maxOf { it.size }) {
                out.append(theme.muted("│"))
                wrapped.forEachIndexed { c, parts ->
                    val text = parts.getOrElse(line) { "" }
                    val drawn = pad(text, widths[c], align.getOrElse(c) { Align.LEFT })
                    out.append(' ').append(if (r == 0) theme.bold(drawn) else drawn).append(' ').append(theme.muted("│"))
                }
                out.append('\n')
            }
            if (r == 0 && grid.size > 1) out.append(rule("├", "┼", "┤"))
        }
        out.append(rule("└", "┴", "┘"))
        return out.toString()
    }

    /** A bare address from [start], without the sentence's punctuation after it; a `)` stays where a `(` inside opened it. */
    private fun bareUrl(text: String, start: Int): String {
        var end = start
        while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"'`") end++
        var url = text.substring(start, end)
        while (url.isNotEmpty() && (url.last() in ".,;:!?]" || (url.last() == ')' && url.count { it == '(' } < url.count { it == ')' }))) url = url.dropLast(1)
        return url
    }

    /** Emphasis, code and links inside one line. Code is taken out first, so a `*` inside it stays a `*`. */
    fun inline(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    val code = text.substring(i + 1, end)
                    // A path an answer names is a link to the file, which the terminal opens on a click.
                    val file = Links.fileTarget(code)
                    if (file != null) {
                        remember(code, file)
                        out.append(links.link(code, file) { theme.code(it) })
                    } else {
                        out.append(theme.code(code))
                    }
                    i = end + 1
                    continue
                }
            }
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                val marker = text.substring(i, i + 2)
                val end = text.indexOf(marker, i + 2)
                if (end > i + 2) {
                    out.append(theme.bold(inline(text.substring(i + 2, end))))
                    i = end + 2
                    continue
                }
            }
            if (c == '*' && i + 1 < text.length && text[i + 1] != ' ') {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1 && text[end - 1] != ' ') {
                    out.append(theme.italic(text.substring(i + 1, end)))
                    i = end + 1
                    continue
                }
            }
            if (c == '[') {
                val m = Regex("^\\[([^\\]]+)]\\(([^)\\s]+)\\)").find(text.substring(i))
                if (m != null) {
                    val label = m.groupValues[1]
                    val url = m.groupValues[2]
                    remember(label, url)
                    out.append(links.link(label, url) { theme.underline(theme.accent(it)) })
                    i += m.value.length
                    continue
                }
            }
            if ((c == 'h') && (i == 0 || !text[i - 1].isLetterOrDigit()) && (text.startsWith("http://", i) || text.startsWith("https://", i))) {
                val url = bareUrl(text, i)
                if (url.length > 8) {
                    remember(url, url)
                    out.append(links.link(url, url) { theme.underline(theme.accent(it)) })
                    i += url.length
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}
