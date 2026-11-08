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

    /** The agent last used here, or the one named; null leaves the choice to the server, which takes the last one talked to. */
    fun agent(agents: List<Agent>, config: Config, named: String?): Agent? {
        if (named != null) {
            return SlashCommands.pick(agents, named) { it.name }
                ?: throw OrknuxException("There is no agent \"$named\" here. Agents: ${agents.joinToString(", ") { it.name }}")
        }
        return config.agentId?.let { id -> agents.firstOrNull { it.id == id } }
    }
}
