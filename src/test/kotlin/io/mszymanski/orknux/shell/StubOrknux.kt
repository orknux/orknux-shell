package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A small Orknux that plays its part of one conversation: sign-in, the GraphQL
 * calls a chat makes, a turn's stream, and the terminal door - where the turn
 * waits for the commands it handed the device to be answered, as the server's
 * `DeviceLinks` does.
 */
class StubOrknux(
    private val commands: List<JsonNode>,
    host: String = "127.0.0.1",
    port: Int = 0,
    /** A pause between a turn's frames, so a person watching sees the spinner; none in the suite. */
    private val pace: Long = 0,
    /** A workspace with transcription and speech models, which hears "what time is it" and answers with a tone. */
    private val voice: Boolean = false,
    /** Each answer draws a picture, served at `/api/attachments/9`, for `/copy-image`. */
    private val pictures: Boolean = false,
    /** Says each request on standard output, for whoever drives it by hand. */
    private val log: Boolean = false,
) : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress(host, port), 0)
    val url: String get() = "http://127.0.0.1:${server.address.port}"

    val requests = CopyOnWriteArrayList<String>()
    val missingOrigin = CopyOnWriteArrayList<String>()
    val startChatInputs = CopyOnWriteArrayList<JsonNode>()
    /** Every turn's request body, as the stream received it. */
    val streamRequests = CopyOnWriteArrayList<JsonNode>()
    val nextQueries = CopyOnWriteArrayList<String>()
    val answers = ConcurrentHashMap<String, JsonNode>()

    data class Upload(val path: String, val contentType: String, val origin: String?, val body: String, val fileBytes: Int)

    val uploads = CopyOnWriteArrayList<Upload>()

    @Volatile
    var refuseUploads = false
    private val queue = LinkedBlockingQueue<JsonNode>()
    private val answered = LinkedBlockingQueue<String>()

    init {
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (failure: Throwable) {
                failure.printStackTrace()
                runCatching { send(exchange, 500, "{}") }
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    private fun handle(x: HttpExchange) {
        val path = x.requestURI.path
        val method = x.requestMethod
        requests += "$method $path"
        if (log) println("$method $path")
        val raw = x.requestBody.readAllBytes()
        val body = String(raw, StandardCharsets.UTF_8)
        if (method != "GET" && x.requestHeaders.getFirst("Origin") != url) missingOrigin += "$method $path"
        val signedIn = x.requestHeaders.getFirst("Cookie")?.contains("SESSION=s3cr3t") == true
        when {
            path == "/api/auth/method" -> send(x, 200, """{"method":"INTERNAL","displayName":"Orknux","authorizeUrl":null,"notice":null}""")
            path == "/api/client/compatibility" -> send(x, 200, """{"product":"orknux","server":"1.3.6","serverMajor":1,"compatible":true,"reason":null}""")
            path == "/api/session" && method == "POST" -> {
                val json = Json.parse(body)
                if (json.str("username") == "alice" && json.str("password") == "wonderland") {
                    x.responseHeaders.add("Set-Cookie", "SESSION=s3cr3t; Path=/; HttpOnly; SameSite=Lax")
                    send(x, 200, """{"username":"alice","roles":[],"admin":false}""")
                } else {
                    send(x, 401, "{}")
                }
            }
            !signedIn -> send(x, 401, "{}")
            path == "/api/session" -> send(x, 200, """{"username":"alice","roles":[],"admin":false}""")
            path == "/graphql" -> graphql(x, Json.parse(body))
            path == "/api/chats/7/stream" -> {
                if (log) println("stream $body")
                stream(x, Json.parse(body))
            }
            path == "/api/chats/7/interrupt" -> send(x, 200, "{}")
            path == "/api/attachments/9" -> {
                val red = StubOrknux::class.java.getResourceAsStream("red.png")!!.readAllBytes()
                x.responseHeaders.add("Content-Type", "image/png")
                x.sendResponseHeaders(200, red.size.toLong())
                x.responseBody.write(red)
            }
            path == "/api/workspaces/1/transcription" ->
                if (voice) send(x, 200, """{"text":"What time is it?","millis":40}""") else send(x, 409, """{"error":"This workspace has no transcription model."}""")
            path == "/api/workspaces/1/speech" -> {
                val tone = StubOrknux::class.java.getResourceAsStream("tone.mp3")!!.readAllBytes()
                x.responseHeaders.add("Content-Type", "audio/mp3")
                x.sendResponseHeaders(200, tone.size.toLong())
                x.responseBody.write(tone)
            }
            path == "/api/workspaces/1/attachments" -> {
                val type = x.requestHeaders.getFirst("Content-Type") ?: ""
                val body = String(raw, StandardCharsets.ISO_8859_1)
                val boundary = type.substringAfter("boundary=")
                val start = body.indexOf("\r\n\r\n") + 4
                val end = body.lastIndexOf("\r\n--$boundary--")
                uploads.add(Upload(path, type, x.requestHeaders.getFirst("Origin"), body, end - start))
                if (log) println("upload ${Regex("filename=\"([^\"]*)\"").find(body)?.groupValues?.get(1)} ${end - start} bytes")
                if (refuseUploads) {
                    send(x, 400, """{"type":"about:blank","title":"Bad Request","status":400,"detail":"huge.bin is 30 MB, and files here may be at most 20 MB."}""")
                } else {
                    val name = Regex("filename=\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "file"
                    send(x, 200, """{"attachments":[{"id":41,"filename":"$name","contentType":"application/pdf","sizeBytes":${end - start}}]}""")
                }
            }
            path == "/api/terminal/next" -> {
                nextQueries += x.requestURI.query ?: ""
                val command = queue.poll(2, TimeUnit.SECONDS)
                if (command == null) send(x, 204, null) else send(x, 200, Json.write(command))
            }
            path.startsWith("/api/terminal/answers/") -> {
                val id = path.substringAfterLast('/')
                answers[id] = Json.parse(body)
                answered.put(id)
                send(x, 204, null)
            }
            else -> send(x, 404, "{}")
        }
    }

    private fun graphql(x: HttpExchange, request: JsonNode) {
        val query = request.str("query") ?: ""
        val vars = request.get("variables")
        val data = when {
            query.contains("workspaces(") -> """{"workspaces":{"content":[{"id":"1","name":"Home","commandMarker":null,"commandMarkerDefault":"!"}]}}"""
            query.contains("WorkspaceVoice") -> """{"workspace":{"id":"1","transcriptionModelId":${if (voice) "\"21\"" else "null"},"speechModelId":${if (voice) "\"22\"" else "null"}}}"""
            query.contains("workspace(") -> """{"workspace":{"id":"1","name":"Home","commandMarker":null,"commandMarkerDefault":"!"}}"""
            query.contains("workspaceAgents(") -> """{"workspaceAgents":{"content":[{"id":"5","name":"Builder","description":"Builds","enabled":true,"skillCatalogs":[],"hiddenSkills":[]}]}}"""
            query.contains("startChat(") -> {
                startChatInputs.add(vars.get("input"))
                """{"startChat":${chat(0)}}"""
            }
            query.contains("chatSession(") -> """{"chatSession":${chat(1200)}}"""
            query.contains("setChatMode(") -> """{"setChatMode":${chat(0)}}"""
            else -> null
        }
        if (data == null) send(x, 200, """{"errors":[{"message":"The stub does not know this query"}]}""") else send(x, 200, """{"data":$data}""")
    }

    private fun chat(spent: Int) =
        """{"id":"7","workspaceId":"1","title":"New chat","agentId":"5","agentName":"Builder","lastMessageAt":null,"createdAt":"2026-11-08T00:00:00Z",""" +
            """"spentInputTokens":$spent,"spentOutputTokens":${spent / 10},"spentCachedInputTokens":${spent / 2},"mode":"DEFAULT"}"""

    /** A turn: thinking, a tool call being written, the commands handed to the device and waited for, then the answer. */
    private fun stream(x: HttpExchange, request: JsonNode) {
        streamRequests.add(request)
        x.responseHeaders.add("Content-Type", "text/event-stream")
        x.sendResponseHeaders(200, 0)
        val out = x.responseBody
        fun frame(event: String, data: String) {
            if (pace > 0) Thread.sleep(pace)
            out.write("event: $event\ndata: $data\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        frame("session", """{"id":"31"}""")
        frame("thinking", """{"text":"The person said: ${request.str("text")}"}""")
        frame("writing", """{"tool":"terminal_run","chars":17}""")
        commands.forEachIndexed { at, command ->
            frame("call", Json.write(Json.obj().put("at", at).put("tool", "terminal_run").put("arguments", Json.write(command.get("arguments")))))
            queue.put(command)
            val id = answered.poll(30, TimeUnit.SECONDS)
            val answer = id?.let { answers[it] }
            val failed = answer == null || answer.has("error")
            frame("called", Json.write(Json.obj().put("at", at).put("result", answer?.let { Json.write(it) } ?: "no answer").put("failed", failed)))
        }
        if (pictures) frame("drew", """{"markdown":"![a red square](/api/attachments/9)"}""")
        frame("chunk", """{"text":"All **done**: the command said hi."}""")
        frame("answered", "{}")
        frame("titled", """{"title":"Saying hi"}""")
        frame("done", """{"millis":1500,"inputTokens":1200,"outputTokens":120,"cachedInputTokens":600,"cost":0.0042}""")
        out.close()
    }

    private fun send(x: HttpExchange, status: Int, body: String?) {
        if (body == null) {
            x.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        x.responseHeaders.add("Content-Type", "application/json")
        x.sendResponseHeaders(status, bytes.size.toLong())
        x.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
