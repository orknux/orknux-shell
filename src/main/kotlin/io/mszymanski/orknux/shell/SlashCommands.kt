package io.mszymanski.orknux.shell

/** A line that starts with `/`, taken apart: `/agent Research bot` is `agent` and `Research bot`. */
data class Slash(val name: String, val argument: String?)

data class SlashCommand(val name: String, val argument: String?, val summary: String)

object SlashCommands {
    val ALL = listOf(
        SlashCommand("agent", "[name]", "List the workspace's agents, or switch to one"),
        SlashCommand("skill", "[key] [message]", "List the skills the agent can use, or use one"),
        SlashCommand("attach", "[paths]", "Attach files to the next message, or list what is attached"),
        SlashCommand("detach", "<n|all>", "Take an attachment off"),
        SlashCommand("open", "<n>", "Open a link of the last answer in the browser"),
        SlashCommand("thinking", "[collapsed|expanded|hidden]", "Read the last turn's thinking (also Ctrl+O), or set how it is shown"),
        SlashCommand("effort", "[level|default]", "The model's reasoning effort for this chat"),
        SlashCommand("subagents", "[n]", "The agents this conversation asked, or read one"),
        SlashCommand("review", "[--fix] [branch|path]", "Review the uncommitted changes, a branch, or a path"),
        SlashCommand("goal", "<what> until <condition> [max <n>] [reviewer <model>] | stop", "Work at a goal, judged each iteration, until it is met"),
        SlashCommand("usage", null, "Tokens in, out and cached, and the cost, of this chat"),
        SlashCommand("resume", "[number]", "Pick up an earlier chat in this workspace"),
        SlashCommand("clear", null, "Start a new conversation"),
        SlashCommand("workspace", "[name]", "List workspaces, or switch to one"),
        SlashCommand("mode", "[auto|ask|plan]", "Cycle or set the mode (also Shift+Tab)"),
        SlashCommand("theme", null, "Show the colours and where to change them"),
        SlashCommand("rules", null, "Show the rules for local commands"),
        SlashCommand("help", null, "What there is to type"),
        SlashCommand("quit", null, "Leave (also Ctrl+D)"),
    )

    val NAMES: List<String> = ALL.map { it.name } + "exit"

    /** Null where the line is not a command: no slash, or a path like `/etc/hosts is broken` - a known name is needed. */
    fun parse(line: String): Slash? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 2) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').substringBefore('\t').lowercase()
        if (name !in NAMES) return null
        val argument = body.substring(name.length).trim().takeIf { it.isNotEmpty() }
        return Slash(if (name == "exit") "quit" else name, argument)
    }

    /** The commands a typed prefix could still become, for completion. */
    fun completing(prefix: String): List<SlashCommand> =
        ALL.filter { it.name.startsWith(prefix.removePrefix("/").lowercase()) }

    /** Picks one of [items] by number (from 1) or by name, case-insensitive: exact first, then a unique prefix, then a unique substring. */
    fun <T> pick(items: List<T>, said: String, name: (T) -> String): T? {
        val s = said.trim()
        s.toIntOrNull()?.let { n -> return items.getOrNull(n - 1) }
        items.firstOrNull { name(it).equals(s, ignoreCase = true) }?.let { return it }
        items.filter { name(it).startsWith(s, ignoreCase = true) }.singleOrNull()?.let { return it }
        return items.filter { name(it).contains(s, ignoreCase = true) }.singleOrNull()
    }
}
