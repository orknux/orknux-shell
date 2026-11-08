package io.mszymanski.orknux.shell

/**
 * Markdown for a terminal, a line at a time.
 *
 * Fed the answer as it arrives and gives back only whole lines, so a code fence
 * or a list item is never drawn half-understood; [finish] flushes the rest.
 * Deliberately small: headings, emphasis, inline code, fenced code, lists,
 * quotes, rules and links - what an agent's answer is made of.
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

    /** Takes a piece of text; returns the rendered lines it completed, each ending in a newline. */
    fun feed(piece: String): String {
        pending.append(piece)
        val out = StringBuilder()
        while (true) {
            val newline = pending.indexOf("\n")
            if (newline < 0) break
            val line = pending.substring(0, newline).removeSuffix("\r")
            pending.delete(0, newline + 1)
            out.append(line(line)).append('\n')
        }
        return out.toString()
    }

    /** What is left, rendered; and the state cleared for the next answer. */
    fun finish(): String {
        val rest = if (pending.isNotEmpty()) line(pending.toString()) + "\n" else ""
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
        if (trimmed.startsWith("|")) return inline(raw)
        return inline(raw)
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
                    out.append(theme.code(text.substring(i + 1, end)))
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
