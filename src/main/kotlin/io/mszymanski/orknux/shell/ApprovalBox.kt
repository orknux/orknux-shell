package io.mszymanski.orknux.shell

/** The three answers to a command the agent wants to run, in the order they are offered. */
enum class ApprovalChoice(val label: String, val key: Char) {
    ONCE("Approve", 'a'),
    PATTERN("Approve for pattern", 'p'),
    DECLINE("Decline", 'd'),
}

/**
 * The panel a command is asked about in: what would run, where, why the agent
 * wants it, and a red marker where the agent itself called it dangerous.
 */
object ApprovalBox {
    fun lines(theme: Theme, request: RunRequest, decision: Decision, directory: String, width: Int): List<String> {
        val inner = (width - 4).coerceIn(30, 110)
        val border = { s: String -> theme.accent(s) }
        val out = mutableListOf<String>()
        val title = " Run a command on this computer? "
        out += border("╭─") + theme.bold(title) + border("─".repeat((inner - title.length).coerceAtLeast(0)) + "─╮")
        fun row(content: String) {
            val pad = (inner - visibleLength(content)).coerceAtLeast(0)
            out += border("│ ") + content + " ".repeat(pad) + border(" │")
        }
        row("")
        wrap(request.command, inner - 2).forEachIndexed { i, part -> row((if (i == 0) theme.bold("$ ") else "  ") + theme.text(part)) }
        row("")
        row(theme.muted("in ") + clip(directory, inner - 3))
        request.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            wrap(reason, inner - 5).forEachIndexed { i, part -> row(theme.muted(if (i == 0) "why " else "    ") + part) }
        }
        if (request.dangerous) row(theme.danger("▲ dangerous") + theme.muted(" - the agent marked this command dangerous"))
        row(theme.muted(clip(decision.why, inner)))
        out += border("╰" + "─".repeat(inner + 2) + "╯")
        return out
    }

    /** The choices on one line, the selected one highlighted. */
    fun choices(theme: Theme, selected: ApprovalChoice): String =
        "  " + ApprovalChoice.entries.joinToString("   ") { choice ->
            val label = "${choice.label} (${choice.key})"
            if (choice == selected) theme.bold(theme.accent("› $label")) else theme.muted("  $label")
        } + theme.muted("   ←/→ Enter · Esc declines")

    fun wrap(text: String, width: Int): List<String> {
        val w = width.coerceAtLeast(10)
        return text.lines().flatMap { line -> if (line.isEmpty()) listOf("") else line.chunked(w) }
    }

    private fun clip(text: String, width: Int) = if (text.length <= width) text else "…" + text.takeLast(width - 1)
}
