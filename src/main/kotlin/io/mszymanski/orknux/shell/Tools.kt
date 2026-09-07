package io.mszymanski.orknux.shell

import tools.jackson.databind.JsonNode

/**
 * One parameter of a tool, in the terms MCP's `inputSchema` wants.
 *
 * The types are JSON Schema's; `items` names the element type where the
 * parameter is an array. The names and shapes here are the contract's
 * DC-compatibility clause made concrete — an agent that learned
 * DesktopCommander's surface binds to these, so they change together with it
 * or not at all.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val items: String? = null,
)

/**
 * One tool: what it is called, how it describes itself, and what it does.
 *
 * The handler takes the session it runs in — the jail is the session's, not
 * the tool's — and the call's arguments, and answers with the text the agent
 * reads. Refusals are thrown; see the two exceptions below for which kind.
 */
class Tool(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val run: (ShellSession, JsonNode) -> String,
)

/**
 * The tool ran and says no: a path outside the jail, a file that is not
 * there, a pid nothing owns. Answered as a tool result with `isError` set,
 * because the agent needs to read the sentence and try something else.
 */
class ToolRefusedException(message: String) : RuntimeException(message)

/**
 * The tool could not run at all — the configured shell would not start.
 * Answered as a JSON-RPC error, which is what the contract reserves errors
 * for: Orknux reads one as "shell unreachable", and this is the one case
 * where that is the truth.
 */
class ShellUnavailableException(message: String) : RuntimeException(message)

/** The named argument as a string, or a refusal that names what is missing. */
fun JsonNode.requiredString(name: String): String =
    optionalString(name) ?: throw ToolRefusedException("`$name` is required and must be a string")

/** The named argument as a string, or null where it was not given. Empty counts as given. */
fun JsonNode.optionalString(name: String): String? =
    path(name).takeIf { it.isTextual }?.asString()

/** The named argument as a number, or a refusal. A number in quotes is read as one. */
fun JsonNode.requiredLong(name: String): Long =
    optionalLong(name) ?: throw ToolRefusedException("`$name` is required and must be a number")

fun JsonNode.optionalLong(name: String): Long? = path(name).let { held ->
    when {
        held.isNumber -> held.asLong()
        held.isTextual -> held.asString().trim().toLongOrNull()
        else -> null
    }
}

fun JsonNode.optionalBoolean(name: String, absent: Boolean): Boolean =
    path(name).takeIf { it.isBoolean }?.asBoolean(absent) ?: absent
