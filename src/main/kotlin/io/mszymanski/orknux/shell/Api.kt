package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode

data class Workspace(val id: String, val name: String, val commandMarker: String?)

data class Agent(val id: String, val name: String, val description: String?, val enabled: Boolean, val skillCatalogs: List<String>, val hiddenSkills: List<String>)

data class ClaudeCodeStatus(val available: Boolean, val reason: String?, val connected: Boolean)

data class Chat(
    val id: String,
    val workspaceId: String,
    val title: String,
    val agentId: String?,
    val agentName: String?,
    val lastMessageAt: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val mode: String,
    val llmSessionId: String? = null,
    val reasoningEffort: String? = null,
)

data class Message(val role: String, val content: String, val thinking: String?, val thinkingMillis: Long?, val at: String?)

/** A file uploaded for the next message, as the server keeps it. */
data class Attachment(val id: Long, val filename: String, val contentType: String?, val sizeBytes: Long)

/** An agent the conversation's agent asked: a session under the chat's own. */
/** One of a question's answers, as the server offers it. */
data class RequestOption(val id: String, val label: String, val description: String?)

/**
 * A question put to the person in a conversation - an agent's ask_person, or
 * anything else that waits on a decision there. It carries its own options, so
 * any kind is drawn the same way.
 */
data class PersonQuestion(
    val id: String,
    val kind: String,
    val askedBy: String?,
    val question: String,
    val options: List<RequestOption>,
    val items: List<String>,
    val multiple: Boolean,
    val allowsText: Boolean,
)

data class Subagent(val sessionId: String, val agent: String, val about: String, val active: Boolean, val depth: Int, val inputTokens: Long, val outputTokens: Long, val cost: String?)

/** One line of a session's transcript. */
data class SessionLine(val kind: String, val actor: String?, val content: String, val result: String?)

data class Skill(val key: String, val name: String, val description: String?, val catalog: String, val plugin: String?)

/**
 * The GraphQL calls this client makes, spelt as the web interface spells them
 * (`orknux-ui/src/api/chat.ts` and its neighbours), so both read the same shapes.
 */
class Api(val client: OrknuxClient) {

    fun me(): JsonNode? {
        val reply = client.get("/api/session")
        return if (reply.ok) reply.json() else null
    }

    fun workspaces(): List<Workspace> {
        val data = client.graphql(
            "query Workspaces(\$page: Int, \$size: Int) { workspaces(page: \$page, size: \$size) { content { id name commandMarker commandMarkerDefault } } }",
            vars("page" to 0, "size" to 200),
        )
        return data.get("workspaces").list("content").map(::workspaceOf)
    }

    fun workspace(id: String): Workspace? {
        val data = client.graphql(
            "query Workspace(\$id: ID!) { workspace(id: \$id) { id name commandMarker commandMarkerDefault } }",
            vars("id" to id),
        )
        return data.get("workspace")?.takeIf { !it.isNull }?.let(::workspaceOf)
    }

    private fun workspaceOf(node: JsonNode) = Workspace(
        node.str("id")!!,
        node.str("name") ?: "workspace",
        node.str("commandMarker")?.takeIf { it.isNotEmpty() } ?: node.str("commandMarkerDefault")?.takeIf { it.isNotEmpty() },
    )

    fun agents(workspaceId: String): List<Agent> {
        val data = client.graphql(
            "query WorkspaceAgents(\$workspaceId: ID!, \$page: Int!, \$size: Int!) { workspaceAgents(workspaceId: \$workspaceId, page: \$page, size: \$size) " +
                "{ content { id name description enabled skillCatalogs hiddenSkills } } }",
            vars("workspaceId" to workspaceId, "page" to 0, "size" to 200),
        )
        return data.get("workspaceAgents").list("content").map {
            Agent(it.str("id")!!, it.str("name") ?: "agent", it.str("description"), it.bool("enabled") ?: true, it.strings("skillCatalogs"), it.strings("hiddenSkills"))
        }
    }

    /**
     * Opens a chat that says it was opened in a terminal: only such a chat offers
     * its agent the tool that runs a command here. A server from before
     * `openedIn` refuses the field, and is asked again without it - the chat
     * works, without local commands, and [startedWithoutOrigin] says so.
     */
    fun startChat(workspaceId: String, agentId: String?): Chat {
        val input = Json.obj().put("workspaceId", workspaceId).put("openedIn", ORIGIN)
        agentId?.let { input.put("agentId", it) }
        val query = "mutation StartChat(\$input: StartChatInput!) { startChat(input: \$input) { $CHAT_FIELDS } }"
        return try {
            chatOf(client.graphql(query, Json.obj().set("input", input)).get("startChat"))
        } catch (refused: OrknuxException) {
            if (!refused.message.orEmpty().contains("openedIn")) throw refused
            input.remove("openedIn")
            startedWithoutOrigin = true
            chatOf(client.graphql(query, Json.obj().set("input", input)).get("startChat"))
        }
    }

    @Volatile
    var startedWithoutOrigin = false
        private set

    fun chats(workspaceId: String): List<Chat> =
        client.graphql("query ChatSessions(\$workspaceId: ID!) { chatSessions(workspaceId: \$workspaceId) { $CHAT_FIELDS } }", vars("workspaceId" to workspaceId))
            .list("chatSessions").map(::chatOf)

    fun chat(id: String): Chat? =
        client.graphql("query ChatSession(\$id: ID!) { chatSession(id: \$id) { $CHAT_FIELDS } }", vars("id" to id))
            .get("chatSession")?.takeIf { !it.isNull }?.let(::chatOf)

    fun messages(id: String): List<Message> =
        client.graphql("query ChatMessages(\$id: ID!) { chatMessages(id: \$id) { role content thinking thinkingMillis at } }", vars("id" to id))
            .list("chatMessages").map { Message(it.str("role") ?: "", it.str("content") ?: "", it.str("thinking"), it.long("thinkingMillis"), it.str("at")) }

    /** Leaves [text] for the turn running in the chat: its agent reads it at its next step, as the person speaking. */
    fun postDuringTurn(chatId: String, text: String) {
        client.graphql(
            "mutation PostDuringTurn(\$id: ID!, \$text: String!) { postChatMessageDuringTurn(id: \$id, text: \$text) }",
            vars("id" to chatId, "text" to text),
        )
    }

    /** Whether My Claude Code is offered here, why not where it is not, and whether the caller's Claude Code is connected. */
    fun claudeCode(): ClaudeCodeStatus? = runCatching {
        client.graphql("query { claudeCodeChat { available reason connected } }").get("claudeCodeChat")?.let {
            ClaudeCodeStatus(it.bool("available") ?: false, it.str("reason"), it.bool("connected") ?: false)
        }
    }.getOrNull()

    /** Hands the chat to the caller's own Claude Code, through the Orknux channel plugin. */
    fun chooseClaudeCode(chatId: String): Chat =
        chatOf(
            client.graphql(
                "mutation ChooseChatClaudeCode(\$id: ID!) { chooseChatClaudeCode(id: \$id) { $CHAT_FIELDS } }",
                vars("id" to chatId),
            ).get("chooseChatClaudeCode"),
        )

    fun chooseAgent(chatId: String, agentId: String): Chat =
        chatOf(
            client.graphql(
                "mutation ChooseChatAgent(\$id: ID!, \$agentId: ID!) { chooseChatAgent(id: \$id, agentId: \$agentId) { $CHAT_FIELDS } }",
                vars("id" to chatId, "agentId" to agentId),
            ).get("chooseChatAgent"),
        )

    fun setMode(chatId: String, mode: String): Chat =
        chatOf(
            client.graphql(
                "mutation SetChatMode(\$id: ID!, \$mode: ChatMode!) { setChatMode(id: \$id, mode: \$mode) { $CHAT_FIELDS } }",
                vars("id" to chatId, "mode" to mode),
            ).get("setChatMode"),
        )

    fun setEffort(chatId: String, effort: String?): Chat =
        chatOf(
            client.graphql(
                "mutation SetChatReasoningEffort(\$id: ID!, \$effort: String) { setChatReasoningEffort(id: \$id, effort: \$effort) { $CHAT_FIELDS } }",
                vars("id" to chatId, "effort" to effort),
            ).get("setChatReasoningEffort"),
        )

    /** The words this chat's model takes for its reasoning effort; empty where it takes none. */
    fun efforts(chatId: String): List<String> =
        client.graphql("query ChatEffort(\$id: ID!) { chatSession(id: \$id) { reasoningEffort reasoningEfforts } }", vars("id" to chatId))
            .get("chatSession").strings("reasoningEfforts")

    /**
     * Uploads one file for a message, as the web chat does: one multipart
     * request per file to the workspace's attachments. A refusal - too large, a
     * type the installation does not take, attachments switched off - comes
     * back in the server's words.
     */
    fun upload(workspaceId: String, file: java.nio.file.Path): Attachment {
        val reply = client.upload("/api/workspaces/$workspaceId/attachments", "files", file)
        if (!reply.ok) throw OrknuxException(OrknuxClient.refusalOf(reply.status, reply.body), reply.status)
        val kept = reply.json().list("attachments").firstOrNull() ?: throw OrknuxException("The server kept nothing of ${file.fileName}.")
        return Attachment(kept.long("id") ?: kept.str("id")!!.toLong(), kept.str("filename") ?: file.fileName.toString(), kept.str("contentType"), kept.long("sizeBytes") ?: 0)
    }

    /**
     * The agents this chat's agent asked: the sessions under its own, in the
     * order the family lists them, each with its agent, its task and what it spent.
     */
    fun subagents(llmSessionId: String): List<Subagent> {
        val family = client.graphql(
            "query Family(\$id: ID!) { llmSessionFamily(id: \$id) { id title main depth active } }",
            vars("id" to llmSessionId),
        ).list("llmSessionFamily").filter { it.bool("main") != true && it.str("id") != llmSessionId }
        return family.map { member ->
            val id = member.str("id")!!
            val one = runCatching {
                client.graphql(
                    "query Session(\$id: ID!) { llmSession(id: \$id) { id title active agentDetails { agent } spent { inputTokens outputTokens cost } } }",
                    vars("id" to id),
                ).get("llmSession")
            }.getOrNull()
            Subagent(
                sessionId = id,
                agent = one?.get("agentDetails").str("agent") ?: "agent",
                about = member.str("title") ?: one.str("title") ?: "",
                active = member.bool("active") ?: one.bool("active") ?: false,
                depth = (member.long("depth") ?: 1).toInt(),
                inputTokens = one?.get("spent").double("inputTokens")?.toLong() ?: 0,
                outputTokens = one?.get("spent").double("outputTokens")?.toLong() ?: 0,
                cost = one?.get("spent").str("cost"),
            )
        }
    }

    /** The questions in a conversation nobody has answered yet, oldest first; none from a server that has no such questions. */
    fun openQuestions(llmSessionId: String): List<PersonQuestion> = runCatching {
        client.graphql(
            "query Questions(\$id: ID!) { sessionRequests(sessionId: \$id) { id kind askedBy question chosen multiple allowsText " +
                "options { id label description } items { name description } } }",
            vars("id" to llmSessionId),
        ).list("sessionRequests").filter { it.str("chosen") == null }.map { q ->
            PersonQuestion(
                id = q.str("id")!!,
                kind = q.str("kind") ?: "",
                askedBy = q.str("askedBy"),
                question = q.str("question") ?: "",
                options = q.list("options").map { RequestOption(it.str("id")!!, it.str("label") ?: "", it.str("description")) },
                items = q.list("items").map { listOfNotNull(it.str("name"), it.str("description")).joinToString(" - ") },
                multiple = q.bool("multiple") ?: false,
                allowsText = q.bool("allowsText") ?: false,
            )
        }
    }.getOrDefault(emptyList())

    /** Answers a question: one option, several where it is multiple, or the person's own words. */
    fun answerQuestion(id: String, options: List<String>, text: String?, multiple: Boolean) {
        val variables = Json.obj().put("id", id)
        if (multiple) variables.putArray("options").apply { options.forEach { add(it) } } else options.firstOrNull()?.let { variables.put("option", it) }
        text?.let { variables.put("text", it) }
        client.graphql(
            "mutation Answer(\$id: ID!, \$option: String, \$options: [String!], \$text: String) { answerRequest(id: \$id, option: \$option, options: \$options, text: \$text) { id } }",
            variables,
        )
    }

    /** Whether a session is running now, and what it spent: for /sessions, the chat's own session among its family. */
    fun session(id: String): Subagent? = runCatching {
        val one = client.graphql(
            "query Session(\$id: ID!) { llmSession(id: \$id) { id title active agentDetails { agent } spent { inputTokens outputTokens cost } } }",
            vars("id" to id),
        ).get("llmSession") ?: return null
        Subagent(
            sessionId = id,
            agent = one.get("agentDetails").str("agent") ?: "agent",
            about = one.str("title") ?: "",
            active = one.bool("active") ?: false,
            depth = 0,
            inputTokens = one.get("spent").double("inputTokens")?.toLong() ?: 0,
            outputTokens = one.get("spent").double("outputTokens")?.toLong() ?: 0,
            cost = one.get("spent").str("cost"),
        )
    }.getOrNull()

    /** A session's transcript, oldest first. */
    fun sessionLines(sessionId: String, size: Int = 200): List<SessionLine> =
        client.graphql(
            "query Events(\$sessionId: ID!, \$size: Int, \$ascending: Boolean) { llmSessionEvents(sessionId: \$sessionId, page: 0, size: \$size, ascending: \$ascending) " +
                "{ content { kind actor content result } } }",
            Json.obj().put("sessionId", sessionId).put("size", size).put("ascending", true),
        ).get("llmSessionEvents").list("content").map { SessionLine(it.str("kind") ?: "", it.str("actor"), it.str("content") ?: "", it.str("result")) }

    /** The skills a chat with [agent] can name, once each by key - `reachableSkills` in the interface. */
    fun skills(workspaceId: String, agent: Agent?): List<Skill> {
        val catalogs = runCatching {
            client.graphql("query SkillCatalogs(\$workspaceId: ID!) { skillCatalogs(workspaceId: \$workspaceId) { id name } }", vars("workspaceId" to workspaceId))
                .list("skillCatalogs").associate { it.str("id")!! to (it.str("name") ?: "") }
        }.getOrDefault(emptyMap())
        val offers = mutableListOf<Skill>()
        client.graphql(
            "query WorkspaceSkills(\$workspaceId: ID!, \$page: Int!, \$size: Int!) { workspaceSkills(workspaceId: \$workspaceId, page: \$page, size: \$size) " +
                "{ content { key name description enabled catalogId } } }",
            vars("workspaceId" to workspaceId, "page" to 0, "size" to 200),
        ).get("workspaceSkills").list("content").filter { it.bool("enabled") != false }.forEach {
            offers += Skill(it.str("key") ?: return@forEach, it.str("name") ?: "", it.str("description"), catalogs[it.str("catalogId")] ?: "", null)
        }
        runCatching {
            client.graphql("query PluginSkillCatalogs { pluginSkillCatalogs { name plugin skills { name key description } } }")
                .list("pluginSkillCatalogs").forEach { catalog ->
                    catalog.list("skills").forEach {
                        offers += Skill(it.str("key") ?: return@forEach, it.str("name") ?: "", it.str("description"), catalog.str("name") ?: "", catalog.str("plugin"))
                    }
                }
        }
        val seen = mutableSetOf<String>()
        return offers.filter { offer ->
            val reached = if (agent == null) {
                offer.plugin == null || offer.catalog == BUILT_IN_SKILLS
            } else {
                (offer.catalog == BUILT_IN_SKILLS || offer.catalog in agent.skillCatalogs) && offer.key !in agent.hiddenSkills
            }
            reached && seen.add(offer.key.lowercase())
        }
    }

    fun interrupt(chatId: String) {
        client.post("/api/chats/$chatId/interrupt", null)
    }

    /** Whether this server takes a client of this version; null where it does not say. */
    fun compatibility(): Pair<Boolean, String?>? {
        val reply = runCatching { client.get("/api/client/compatibility?client=${OrknuxClient.encode(Version.VERSION)}") }.getOrNull() ?: return null
        if (!reply.ok) return null
        val json = runCatching { reply.json() }.getOrNull() ?: return null
        return (json.bool("compatible") ?: true) to json.str("reason")
    }

    private fun chatOf(node: JsonNode) = Chat(
        id = node.str("id")!!,
        workspaceId = node.str("workspaceId") ?: "",
        title = node.str("title") ?: "New chat",
        agentId = node.str("agentId"),
        agentName = node.str("agentName"),
        lastMessageAt = node.str("lastMessageAt"),
        inputTokens = node.long("spentInputTokens") ?: 0,
        outputTokens = node.long("spentOutputTokens") ?: 0,
        cachedInputTokens = node.long("spentCachedInputTokens") ?: 0,
        mode = node.str("mode") ?: "DEFAULT",
        llmSessionId = node.str("llmSessionId"),
        reasoningEffort = node.str("reasoningEffort"),
    )

    companion object {
        const val CHAT_FIELDS = "id workspaceId title agentId agentName lastMessageAt createdAt spentInputTokens spentOutputTokens spentCachedInputTokens mode llmSessionId reasoningEffort"

        /** `ChatOrigin`: a chat opened here, which is what lends its agent this computer. */
        const val ORIGIN = "SHELL"
        const val BUILT_IN_SKILLS = "orknux_skills"

        fun vars(vararg pairs: Pair<String, Any?>): JsonNode {
            val node = Json.obj()
            pairs.forEach { (k, v) ->
                when (v) {
                    null -> node.putNull(k)
                    is Int -> node.put(k, v)
                    is Long -> node.put(k, v)
                    is Boolean -> node.put(k, v)
                    else -> node.put(k, v.toString())
                }
            }
            return node
        }
    }
}
