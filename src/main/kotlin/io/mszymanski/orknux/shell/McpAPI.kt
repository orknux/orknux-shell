package io.mszymanski.orknux.shell

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The one endpoint: MCP over Streamable HTTP, spoken as JSON-RPC 2.0 in plain
 * request-and-answer.
 *
 * No SSE and no resumption, because this server never sends an unsolicited
 * message — a request and its answer is the whole of the transport, the same
 * shape orknux-server's own MCP door takes.
 *
 * What *is* stateful is the session, because the contract makes it the
 * sandbox: `initialize` allocates a jailed workspace and hands its id back in
 * `Mcp-Session-Id`; every later request must carry that header, is confined
 * to that workspace, and DELETE — or the idle sweep — destroys it. A request
 * naming a session this server does not hold is answered 404, which is the
 * spec's way of telling a client to initialize again.
 *
 * Answers block the request thread, deliberately: `start_process` without a
 * timeout has one job, to return when the command has finished, and holding
 * the thread is the honest implementation of that. The ceiling on how long is
 * `orknux-shell.command-timeout`.
 */
@RestController
class McpAPI(
    private val sessions: ShellSessionRegistry,
    private val tools: ShellTools,
    private val properties: ShellProperties,
    private val mapper: ObjectMapper,
    private val meters: MeterRegistry,
    private val toolchain: Toolchain,
) {

    @PostMapping("/mcp", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun rpc(
        @RequestHeader(name = SESSION_HEADER, required = false) sessionId: String?,
        @RequestBody body: JsonNode,
    ): ResponseEntity<Any> {
        val method = body.path("method").stringValue().orEmpty()
        val id = body.path("id").takeUnless { it.isMissingNode || it.isNull }

        if (method == "initialize" && id != null) {
            val opened = sessions.open()
            return ResponseEntity.ok().header(SESSION_HEADER, opened.id).body(ok(id, initialize(opened)))
        }

        val session = sessions.find(sessionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(error(id, INVALID_PARAMS, "This request names no session this server holds; initialize again"))

        // A notification has no id and takes no answer — `initialized` is the
        // one every client sends. Answering it with a result would be a
        // protocol error on our side, so it gets an empty 202 instead.
        if (id == null) {
            log.debug("Notification {} on session {}", method, session.id)
            return ResponseEntity.accepted().build()
        }

        return when (method) {
            "tools/list" -> ResponseEntity.ok(ok(id, mapOf("tools" to tools.all.map(::described))))
            "tools/call" -> ResponseEntity.ok(call(session, id, body.path("params")))
            "ping" -> ResponseEntity.ok(ok(id, emptyMap<String, Any>()))
            else -> ResponseEntity.ok(error(id, METHOD_NOT_FOUND, "This server does not do $method"))
        }
    }

    /** The transport's goodbye: the session and its workspace end here. */
    @DeleteMapping("/mcp")
    fun close(@RequestHeader(name = SESSION_HEADER, required = false) sessionId: String?): ResponseEntity<Void> =
        if (sessions.close(sessionId)) {
            ResponseEntity.noContent().build()
        } else {
            ResponseEntity.notFound().build()
        }

    private fun initialize(session: ShellSession): Map<String, Any> = mapOf(
        // Answered with the version this was written against. A client asking
        // for a newer one is told what it is talking to and decides for itself.
        "protocolVersion" to PROTOCOL,
        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
        "serverInfo" to mapOf("name" to "orknux-shell", "version" to properties.version),
        "instructions" to
            "A POSIX shell with a private workspace. Everything in this session lives under " +
            "${session.root} — relative paths resolve against it, paths outside it are refused, " +
            "and the whole workspace is destroyed when the session ends." + toolchain.sentence,
    )

    /**
     * One call, run and wrapped as MCP expects.
     *
     * Three kinds of bad news, told apart on purpose: a tool that ran and
     * says no answers as a result with `isError`, so the agent reads the
     * sentence and tries something else; a shell that could not run at all is
     * a JSON-RPC error, which is what Orknux reads as "unreachable"; and
     * anything surprising is logged here, where the stack trace is, and
     * answered as an error that names none of it.
     */
    private fun call(session: ShellSession, id: JsonNode, params: JsonNode): Map<String, Any?> {
        val name = params.path("name").stringValue().orEmpty()
        val tool = tools.named(name)
            ?: return error(id, INVALID_PARAMS, "There is no tool called $name")
        val arguments = params.path("arguments").takeUnless { it.isMissingNode || it.isNull }
            ?: mapper.createObjectNode()

        return try {
            ok(id, content(tool.run(session, arguments), failed = false)).also { counted(name, "ok") }
        } catch (refused: ToolRefusedException) {
            counted(name, "refused")
            ok(id, content(refused.message.orEmpty(), failed = true))
        } catch (unavailable: ShellUnavailableException) {
            counted(name, "unavailable")
            error(id, INTERNAL_ERROR, unavailable.message.orEmpty())
        } catch (surprising: Exception) {
            counted(name, "failed")
            log.error("Tool {} failed on session {}", name, session.id, surprising)
            error(id, INTERNAL_ERROR, "$name failed: ${surprising.message}")
        }
    }

    /**
     * One tick per call, split by tool and how it went. The tool names are a
     * fixed set — this is only reached for a tool that exists — so the tag
     * cannot grow without bound.
     */
    private fun counted(tool: String, outcome: String) {
        meters.counter("orknux.shell.tool.calls", "tool", tool, "outcome", outcome).increment()
    }

    private fun content(text: String, failed: Boolean): Map<String, Any> = mapOf(
        "content" to listOf(mapOf("type" to "text", "text" to text)),
        "isError" to failed,
    )

    /** A tool as MCP wants it: a name, a description, and a JSON Schema. */
    private fun described(tool: Tool): Map<String, Any> = mapOf(
        "name" to tool.name,
        "description" to tool.description,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to tool.parameters.associate { parameter ->
                parameter.name to buildMap {
                    put("type", parameter.type)
                    put("description", parameter.description)
                    parameter.items?.let { put("items", mapOf("type" to it)) }
                }
            },
            "required" to tool.parameters.filter { it.required }.map { it.name },
        ),
    )

    private fun ok(id: JsonNode, result: Any): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)

    private fun error(id: JsonNode?, code: Int, says: String): Map<String, Any?> =
        mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to says))

    private companion object {
        val log = LoggerFactory.getLogger(McpAPI::class.java)

        const val SESSION_HEADER = "Mcp-Session-Id"

        /** The revision of the protocol these shapes were written against. */
        const val PROTOCOL = "2025-06-18"

        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603
    }
}
