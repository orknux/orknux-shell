package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode

/** One server-sent event as it came: a name and the JSON after `data:`. */
data class SseFrame(val event: String, val data: String)

/**
 * The `text/event-stream` wire format, a line at a time.
 *
 * `event:` names the frame, every `data:` line is a piece of it, a blank line
 * ends it, and a line starting with a colon is a keep-alive that says nothing.
 */
class SseParser {
    private var event: String? = null
    private val data = StringBuilder()
    private var hasData = false

    /** One line without its newline; a frame where this line ended one. */
    fun line(line: String): SseFrame? {
        val l = line.removeSuffix("\r")
        if (l.isEmpty()) {
            if (!hasData && event == null) return null
            val frame = SseFrame(event ?: "message", data.toString())
            event = null
            data.setLength(0)
            hasData = false
            return frame
        }
        if (l.startsWith(":")) return null
        val colon = l.indexOf(':')
        val field = if (colon < 0) l else l.substring(0, colon)
        var value = if (colon < 0) "" else l.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }
}

/** What a chat's stream says, in the vocabulary of `ChatStreamAPI`. */
sealed interface ChatEvent {
    data class Chunk(val text: String) : ChatEvent
    data class Thinking(val text: String) : ChatEvent
    data class Thought(val text: String, val millis: Long) : ChatEvent
    /** The model is writing a tool call's arguments: which tool, where it knows, and how far it has got. */
    data class Writing(val tool: String?, val chars: Int) : ChatEvent
    /** A local server reading the prompt. */
    data class Reading(val total: Long, val cached: Long, val processed: Long) : ChatEvent
    data class Call(val at: Int, val tool: String, val arguments: String) : ChatEvent
    data class Called(val at: Int, val result: String, val failed: Boolean) : ChatEvent
    data class Done(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val cachedInputTokens: Long?,
        val cost: Double?,
        val millis: Long?,
        val generatedPerSecond: Double?,
    ) : ChatEvent
    data object Answered : ChatEvent
    data class Error(val reason: String) : ChatEvent
    data object Compacting : ChatEvent
    data class Compacted(val replaced: Long, val kept: Long, val tokens: Long) : ChatEvent
    data object Titling : ChatEvent
    data class Titled(val title: String) : ChatEvent
    data class TitleFailed(val reason: String) : ChatEvent
    data class Session(val id: String) : ChatEvent
    data class Drew(val markdown: String) : ChatEvent
    /** A file the agent's side put on the chat; [id] is the attachment's, at `/api/attachments/{id}`. */
    data class Filed(val filename: String, val contentType: String?, val id: String? = null) : ChatEvent
    data class Said(val text: String) : ChatEvent

    /** A message sent into the turn was read by the agent at its next step: the server's `personRead`. */
    data class PersonRead(val text: String) : ChatEvent

    /** A sound the agent plays to the person in a voice turn - `audio_play` - by its address. */
    data class Play(val url: String, val contentType: String?) : ChatEvent
    data class Other(val name: String, val data: String) : ChatEvent

    companion object {
        fun of(frame: SseFrame): ChatEvent {
            val json: JsonNode = runCatching { Json.parse(frame.data.ifBlank { "{}" }) }.getOrElse { return Other(frame.event, frame.data) }
            return when (frame.event) {
                "chunk" -> Chunk(json.str("text") ?: "")
                "thinking" -> Thinking(json.str("text") ?: "")
                "thought" -> Thought(json.str("text") ?: "", json.long("millis") ?: 0)
                "writing" -> Writing(json.str("tool"), (json.long("chars") ?: 0).toInt())
                "reading" -> Reading(json.long("total") ?: 0, json.long("cached") ?: 0, json.long("processed") ?: 0)
                "call" -> Call((json.long("at") ?: 0).toInt(), json.str("tool") ?: "tool", json.str("arguments") ?: "")
                "called" -> Called((json.long("at") ?: 0).toInt(), json.str("result") ?: "", json.bool("failed") ?: false)
                "done" -> Done(
                    json.long("inputTokens"), json.long("outputTokens"), json.long("cachedInputTokens"),
                    json.double("cost"), json.long("millis"), json.double("generatedPerSecond"),
                )
                "answered" -> Answered
                "error" -> Error(json.str("reason") ?: json.str("message") ?: "The server could not answer.")
                "compacting" -> Compacting
                "compacted" -> Compacted(json.long("replaced") ?: 0, json.long("kept") ?: 0, json.long("tokens") ?: 0)
                "titling" -> Titling
                "titled" -> Titled(json.str("title") ?: "")
                "titleFailed" -> TitleFailed(json.str("reason") ?: "")
                "session" -> Session(json.str("id") ?: "")
                "drew" -> Drew(json.str("markdown") ?: "")
                "filed" -> Filed(json.str("filename") ?: "file", json.str("contentType"), json.str("id"))
                "said" -> Said(json.str("text") ?: "")
                "personRead" -> PersonRead(json.str("text") ?: "")
                "play" -> json.str("url")?.let { Play(it, json.str("contentType")) } ?: Other(frame.event, frame.data)
                else -> Other(frame.event, frame.data)
            }
        }
    }
}
