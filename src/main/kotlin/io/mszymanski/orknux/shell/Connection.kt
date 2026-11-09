package io.mszymanski.orknux.shell

/**
 * The client for the configured server, signed in, and kept signed in: a
 * cookie the server renews is written back to the configuration at once.
 */
object Connection {
    fun clientFor(store: ConfigStore, config: Config): OrknuxClient {
        val server = requireNotNull(config.server) { "No server is configured." }
        return OrknuxClient(server, token = config.token, cookies = config.cookies) { renewed ->
            runCatching { store.update { it.copy(cookies = renewed) } }
        }
    }

    /** The workspace to work in: the one named, the one last used, the only one, or the person's pick. */
    fun workspace(api: Api, config: Config, named: String?, choose: (List<Workspace>) -> Workspace?): Workspace? {
        val all by lazy { api.workspaces() }
        if (named != null) {
            return SlashCommands.pick(all, named) { it.name }
                ?: throw OrknuxException("There is no workspace \"$named\". Yours: ${all.joinToString(", ") { it.name }}")
        }
        config.workspaceId?.let { id -> runCatching { api.workspace(id) }.getOrNull()?.let { return it } }
        return when (all.size) {
            0 -> throw OrknuxException("You have no workspace on this server.")
            1 -> all[0]
            else -> choose(all)
        }
    }

    /**
     * The agent named, or the one last used in this Shell, or the one the newest chat here was answered by.
     *
     * The last of those is what the server would choose anyway for a chat opened on no agent, worked out here so the
     * Shell can say *which* agent from the first screen - it used to say "agent" until the first answer arrived,
     * which read as an agent of that name. Null only where the newest chat had no agent (My Claude Code, which the
     * server carries on with when it was the last) or there is no chat at all: then the server still chooses.
     */
    fun agent(agents: List<Agent>, config: Config, named: String?, recent: () -> List<Chat> = { emptyList() }): Agent? {
        if (named != null) {
            return SlashCommands.pick(agents, named) { it.name }
                ?: throw OrknuxException("There is no agent \"$named\" here. Agents: ${agents.joinToString(", ") { it.name }}")
        }
        config.agentId?.let { id -> agents.firstOrNull { it.id == id } }?.let { return it }
        return lastTalkedTo(agents, runCatching(recent).getOrDefault(emptyList()))
    }

    /** The agent of the newest chat, passing over chats whose agent is gone or switched off, as the server does. */
    fun lastTalkedTo(agents: List<Agent>, chats: List<Chat>): Agent? {
        for (chat in chats.sortedByDescending { it.lastMessageAt.orEmpty() }) {
            val id = chat.agentId ?: return null
            agents.firstOrNull { it.id == id }?.let { return it }
        }
        return null
    }
}
