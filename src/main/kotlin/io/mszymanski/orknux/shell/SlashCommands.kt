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
        SlashCommand("copy-image", "[n|list]", "Copy a picture an answer showed to the clipboard, the last one unless told"),
        SlashCommand("open", "<n>", "Open a link of the last answer in the browser"),
        SlashCommand("thinking", "[collapsed|expanded|hidden]", "The last turn in full - thinking and tool calls (also Ctrl+O) - or set how thinking is shown"),
        SlashCommand("effort", "[level|default]", "The model's reasoning effort for this chat"),
        SlashCommand("subagents", "[n]", "The agents this conversation asked, or read one"),
        SlashCommand("sessions", null, "Every session of this chat - its own and its agents' - and read one here (Ctrl+T)"),
        SlashCommand("review", "[--fix] [branch|path]", "Review the uncommitted changes, a branch, or a path"),
        SlashCommand("goal", "<what> until <condition> [max <n>] [reviewer <model>] | stop", "Work at a goal, judged each iteration, until it is met"),
        SlashCommand("answer", null, "Answer what the agent asked, where a question was left open"),
        SlashCommand("voice", "[headphones]", "Talk instead of typing: it listens, answers, and reads the answer aloud"),
        SlashCommand("usage", null, "Tokens in, out and cached, and the cost, of this chat"),
        SlashCommand("resume", "[number]", "Pick up an earlier chat in this workspace"),
        SlashCommand("clear", null, "Start a new conversation"),
        SlashCommand("workspace", "[name]", "List workspaces, or switch to one"),
        SlashCommand("mode", "[auto|ask|plan]", "Cycle or set the mode (also Shift+Tab)"),
        SlashCommand("theme", "[<name> #rrggbb|reset]", "Choose a colour and change it, or set one directly"),
        SlashCommand("config", "[<shortcut> ctrl+<letter>|default]", "The shortcuts: choose one and press its new key, or set one directly"),
        SlashCommand("rules", null, "Show the rules for local commands"),
        SlashCommand("orkx-web-chat", null, "Open this chat in Orknux in the browser"),
        SlashCommand("orkx-web-session", null, "Open this conversation's session log in the browser"),
        SlashCommand("orkx-web-agent-settings", null, "Open the agent's settings in the browser"),
        SlashCommand("update", null, "Install the newest Orknux Shell, checked against Orknux's release key"),
        SlashCommand("help", null, "What there is to type"),
        SlashCommand("quit", null, "Leave (also Ctrl+D)"),
    )

    /** Other spellings people type for a command; not offered in completion. */
    val ALIASES: Map<String, String> = mapOf("exit" to "quit", "agents" to "agent", "workspaces" to "workspace", "skills" to "skill", "chats" to "resume", "talk" to "voice", "copy-picture" to "copy-image", "orkxweb-chat" to "orkx-web-chat", "orkxweb-session" to "orkx-web-session", "orkxweb-agent-settings" to "orkx-web-agent-settings")

    val NAMES: List<String> = ALL.map { it.name } + ALIASES.keys

    /**
     * A word after a slash that is no command: a typo, most likely, said as such
     * rather than sent to the agent as a message. A path - another slash in it,
     * or a dot - is not one.
     */
    fun unknown(line: String): String? {
        val word = Regex("""^/([A-Za-z][\w-]*)(\s|$)""").find(line.trim())?.groupValues?.get(1) ?: return null
        return word.takeIf { it.lowercase() !in NAMES }
    }

    /** The command most like [word], for "did you mean". */
    fun nearest(word: String): String? = ALL.map { it.name }.minByOrNull { distance(it, word.lowercase()) }
        ?.takeIf { distance(it, word.lowercase()) <= 2 || it.startsWith(word.lowercase()) }

    private fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        return d[a.length][b.length]
    }

    /** Null where the line is not a command: no slash, or a path like `/etc/hosts is broken` - a known name is needed. */
    fun parse(line: String): Slash? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/") || trimmed.length < 2) return null
        val body = trimmed.substring(1)
        val name = body.substringBefore(' ').substringBefore('\t').lowercase()
        if (name !in NAMES) return null
        val argument = body.substring(name.length).trim().takeIf { it.isNotEmpty() }
        return Slash(ALIASES[name] ?: name, argument)
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
