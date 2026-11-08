package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** A refusal or failure from the server, in words a person can read. */
class OrknuxException(message: String, val status: Int = 0, val code: String? = null) : RuntimeException(message)

data class Reply(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
    fun json(): JsonNode = if (body.isBlank()) Json.obj() else Json.parse(body)
}

/**
 * Talks to one Orknux server, as one person.
 *
 * Signed in either with the session cookie `POST /api/session` set, or with an
 * `orkx_` access token. Every request that is not a GET carries `Origin`, which
 * is how the server's cross-site guard tells this from a page on another site
 * borrowing the cookie; a bearer token needs no Origin, and gets one anyway.
 */
/** This running Shell, for as long as the process lives. */
val INSTANCE: String = java.util.UUID.randomUUID().toString()

class OrknuxClient(
    server: String,
    private val token: String? = null,
    cookies: Map<String, String> = emptyMap(),
    /** Told whenever the server sets or renews a cookie, so it can be kept. */
    private val onCookies: (Map<String, String>) -> Unit = {},
) {
    val server: String = normaliseServer(server)
    val origin: String = originOf(this.server)
    private val jar = ConcurrentHashMap(cookies)
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    val cookies: Map<String, String> get() = jar.toMap()

    fun uri(path: String): URI = URI.create(server + path)

    private fun builder(path: String, timeout: Duration?): HttpRequest.Builder {
        val b = HttpRequest.newBuilder(uri(path)).header("Accept", "application/json, text/event-stream")
            .header("User-Agent", "OrknuxShell/${Version.VERSION}")
            // Which running Shell this is: a chat it writes in is claimed for it, so its agent's commands run here
            // and on no other computer the person has a Shell open on.
            .header("X-Orknux-Shell", INSTANCE)
        timeout?.let { b.timeout(it) }
        if (token != null) b.header("Authorization", "Bearer $token")
        if (jar.isNotEmpty()) b.header("Cookie", jar.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return b
    }

    fun get(path: String, timeout: Duration = Duration.ofSeconds(30)): Reply =
        send(builder(path, timeout).GET().build())

    fun post(path: String, body: JsonNode?, timeout: Duration = Duration.ofSeconds(60)): Reply =
        send(
            builder(path, timeout)
                .header("Origin", origin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body?.let { Json.write(it) } ?: ""))
                .build(),
        )

    /** One file as `multipart/form-data` under [field], read from disk as it is sent. */
    fun upload(path: String, field: String, file: java.nio.file.Path, timeout: Duration = Duration.ofMinutes(10)): Reply {
        val boundary = "orknux-shell-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val name = file.fileName.toString().replace("\"", "%22").replace("\r", "").replace("\n", "")
        val head = "--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$name\"\r\n" +
            "Content-Type: ${contentTypeOf(file.fileName.toString())}\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        val body = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString(head),
            HttpRequest.BodyPublishers.ofFile(file),
            HttpRequest.BodyPublishers.ofString(tail),
        )
        return send(
            builder(path, timeout)
                .header("Origin", origin)
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(body)
                .build(),
        )
    }

    fun delete(path: String): Reply =
        send(builder(path, Duration.ofSeconds(15)).header("Origin", origin).DELETE().build())

    private fun send(request: HttpRequest): Reply {
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OrknuxException("Stopped.")
        } catch (failure: java.io.IOException) {
            throw OrknuxException("Could not reach $server: ${failure.message ?: failure.javaClass.simpleName}")
        }
        keepCookies(response)
        return Reply(response.statusCode(), response.body() ?: "")
    }

    /**
     * A POST answered as server-sent events, read to the end; a GET where
     * [body] is null, as following a chat is.
     *
     * [opened] is handed the body while it is read, so the reader can be
     * closed from elsewhere - which is how a second Ctrl+C stops waiting.
     */
    fun stream(path: String, body: JsonNode?, opened: (InputStream) -> Unit = {}, onFrame: (SseFrame) -> Unit) {
        val builder = builder(path, null).header("Accept", "text/event-stream")
        val request = if (body == null) {
            builder.GET().build()
        } else {
            builder.header("Origin", origin)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build()
        }
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (failure: java.io.IOException) {
            throw OrknuxException("Could not reach $server: ${failure.message ?: failure.javaClass.simpleName}")
        }
        keepCookies(response)
        val input = response.body()
        if (response.statusCode() !in 200..299) {
            val text = input.use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
            throw OrknuxException(refusalOf(response.statusCode(), text), response.statusCode())
        }
        opened(input)
        val parser = SseParser()
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = try { reader.readLine() } catch (_: java.io.IOException) { null } ?: break
                parser.line(line)?.let(onFrame)
            }
            parser.line("")?.let(onFrame)
        }
    }

    /** A GraphQL call: its `data`, or the first error's message as an [OrknuxException]. */
    fun graphql(query: String, variables: JsonNode = Json.obj()): JsonNode {
        val body = Json.obj()
        body.put("query", query)
        body.set<JsonNode>("variables", variables)
        val reply = post("/graphql", body)
        if (reply.status == 401 || reply.status == 403) throw OrknuxException("Not signed in to $server, or the sign-in has expired. Run: orknux login", reply.status)
        if (!reply.ok) throw OrknuxException(refusalOf(reply.status, reply.body), reply.status)
        val json = runCatching { reply.json() }.getOrElse { throw OrknuxException("The server answered something that is not JSON.") }
        json.list("errors").firstOrNull()?.let { error ->
            throw OrknuxException(error.str("message") ?: "The server refused.", reply.status, error.get("extensions").str("code"))
        }
        return json.get("data") ?: throw OrknuxException("The server's answer had no data.")
    }

    private fun keepCookies(response: HttpResponse<*>) {
        val set = response.headers().allValues("set-cookie")
        if (set.isEmpty()) return
        var changed = false
        set.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty()) return@forEach
            val expired = Regex("(?i);\\s*max-age=0\\b").containsMatchIn(header) || value.isEmpty()
            if (expired) {
                if (jar.remove(name) != null) changed = true
            } else if (jar[name] != value) {
                jar[name] = value
                changed = true
            }
        }
        if (changed) onCookies(jar.toMap())
    }

    companion object {
        fun normaliseServer(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) {
                s = if (s.startsWith("localhost") || s.startsWith("127.") || s.startsWith("[::1]")) "http://$s" else "https://$s"
            }
            return s
        }

        fun originOf(server: String): String {
            val uri = URI.create(server)
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "${uri.scheme}://${uri.host}$port"
        }

        /** A content type from the name, for the upload's part; the server decides for itself in the end. */
        fun contentTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "txt", "log" -> "text/plain"
            "md" -> "text/markdown"
            "csv" -> "text/csv"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "zip" -> "application/zip"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            else -> "application/octet-stream"
        }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

        /** Spring's ProblemDetail, a GraphQL-ish error, or the bare status. */
        fun refusalOf(status: Int, body: String): String {
            val json = runCatching { Json.parse(body) }.getOrNull()
            val said = json?.let { it.str("detail") ?: it.str("message") ?: it.str("reason") ?: it.str("error") }
            return when {
                said != null -> said
                status == 401 -> "Not signed in, or the sign-in has expired. Run: orknux login"
                status == 403 -> "The server refused (403)."
                status == 404 -> "The server has no such address (404)."
                else -> "The server answered $status."
            }
        }
    }
}
