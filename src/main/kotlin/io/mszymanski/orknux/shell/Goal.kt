package io.mszymanski.orknux.shell

/** What `/goal` was asked: work at [goal] until [condition] holds, at most [max] times, judged by [reviewer]. */
data class GoalRequest(val goal: String, val condition: String, val max: Int?, val reviewer: String?)

/** A chat's goal as the server keeps it. [status] is RUNNING, MET, STOPPED or EXHAUSTED. */
data class GoalState(val goal: String, val condition: String, val iteration: Int, val maxIterations: Int?, val status: String, val lastFeedback: String?) {
    val running: Boolean get() = status == "RUNNING"
    val progress: String get() = "$iteration/${maxIterations ?: "∞"}"
}

data class Model(val id: String, val name: String)

object Goals {
    /** `<what> until <condition> [max <n>] [reviewer <model>]`, the options in either order. */
    fun parse(argument: String): GoalRequest {
        val text = argument.trim()
        val until = Regex("\\s+until\\s+", RegexOption.IGNORE_CASE).find(text)
            ?: throw IllegalArgumentException("Say when it is done: /goal <what> until <condition> [max <n>] [reviewer <model>]")
        val goal = text.substring(0, until.range.first).trim()
        var rest = text.substring(until.range.last + 1).trim()
        var max: Int? = null
        var reviewer: String? = null
        repeat(2) {
            Regex("\\s+max\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE).find(rest)?.let {
                max = it.groupValues[1].toInt()
                rest = rest.substring(0, it.range.first)
            }
            Regex("\\s+reviewer\\s+(.+?)\\s*$", RegexOption.IGNORE_CASE).find(rest)?.let {
                val named = it.groupValues[1]
                val trailingMax = Regex("^(.*?)\\s+max\\s+(\\d+)$", RegexOption.IGNORE_CASE).find(named)
                if (trailingMax != null) {
                    reviewer = trailingMax.groupValues[1]
                    max = trailingMax.groupValues[2].toInt()
                } else {
                    reviewer = named
                }
                rest = rest.substring(0, it.range.first)
            }
        }
        require(goal.isNotEmpty()) { "What is the goal? /goal <what> until <condition>" }
        require(rest.isNotBlank()) { "When is it met? /goal <what> until <condition>" }
        require(max == null || max!! in 1..100) { "max is between 1 and 100." }
        return GoalRequest(goal, rest.trim(), max, reviewer?.trim()?.trim('"', '\''))
    }

    /** The line a finished iteration leaves in the transcript. */
    fun verdictLine(state: GoalState, theme: Theme): String {
        val head = theme.accent("◆ Goal") + theme.muted(" · iteration ${state.iteration} of ${state.maxIterations ?: "∞"} · ")
        val status = when (state.status) {
            "MET" -> theme.success("met")
            "RUNNING" -> theme.warning("not met yet")
            "STOPPED" -> theme.muted("stopped")
            "EXHAUSTED" -> theme.danger("out of iterations")
            else -> theme.muted(state.status.lowercase())
        }
        return head + status + (state.lastFeedback?.takeIf { it.isNotBlank() }?.let { theme.muted(" - ") + TurnRenderer.oneLine(it) } ?: "")
    }
}

/** The goal calls; a server without them answers in words rather than in GraphQL's. */
class GoalApi(private val client: OrknuxClient) {

    fun start(chatId: String, request: GoalRequest, reviewerModelId: String?): GoalState? {
        val input = Json.obj().put("goal", request.goal).put("condition", request.condition)
        request.max?.let { input.put("maxIterations", it) }
        reviewerModelId?.let { input.put("reviewerModelId", it) }
        val data = call(
            "mutation StartChatGoal(\$chatId: ID!, \$input: StartChatGoalInput!) { startChatGoal(chatId: \$chatId, input: \$input) { $FIELDS } }",
            Json.obj().put("chatId", chatId).set("input", input),
        )
        return data.get("startChatGoal")?.takeIf { it.isObject }?.let(::stateOf) ?: current(chatId)
    }

    fun stop(chatId: String) {
        call("mutation StopChatGoal(\$chatId: ID!) { stopChatGoal(chatId: \$chatId) { $FIELDS } }", Json.obj().put("chatId", chatId))
    }

    fun current(chatId: String): GoalState? =
        call("query ChatGoal(\$chatId: ID!) { chatGoal(chatId: \$chatId) { $FIELDS } }", Json.obj().put("chatId", chatId))
            .get("chatGoal")?.takeIf { it.isObject }?.let(::stateOf)

    fun models(workspaceId: String): List<Model> = runCatching {
        client.graphql("query Models(\$workspaceId: ID!) { models(workspaceId: \$workspaceId) { id name } }", Json.obj().put("workspaceId", workspaceId))
            .list("models").map { Model(it.str("id")!!, it.str("name") ?: "model") }
    }.getOrDefault(emptyList())

    private fun call(query: String, variables: com.fasterxml.jackson.databind.JsonNode) = try {
        client.graphql(query, variables)
    } catch (refused: OrknuxException) {
        if (notOffered(refused.message)) throw OrknuxException("This server does not offer goals yet.")
        throw refused
    }

    private fun stateOf(node: com.fasterxml.jackson.databind.JsonNode) = GoalState(
        node.str("goal") ?: "", node.str("condition") ?: "", (node.long("iteration") ?: 0).toInt(),
        node.long("maxIterations")?.toInt(), node.str("status") ?: "RUNNING", node.str("lastFeedback"),
    )

    companion object {
        const val FIELDS = "goal condition iteration maxIterations status lastFeedback"

        /** GraphQL's way of saying a field or type does not exist on this server. */
        fun notOffered(message: String?): Boolean {
            val m = message ?: return false
            return Regex("(?i)(FieldUndefined|Field '?\\w*Goal'? in type|Unknown type '?ChatGoal|Validation error.*Goal|undefined.*Goal)").containsMatchIn(m)
        }
    }
}
