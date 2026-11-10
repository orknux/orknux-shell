package io.mszymanski.orknux.shell

import kotlin.math.sqrt

/**
 * What a workspace has decided about voice, as the web interface's Voice mode
 * reads it (`orknux-ui/src/components/VoiceMode.tsx`): which models hear and
 * speak, and how a turn ends. A null is a workspace that decided nothing, and
 * the panel's own default stands - the same numbers here as there, so a
 * workspace tuned in the browser behaves the same in the terminal.
 */
data class VoiceSettings(
    val transcriptionModelId: String?,
    val speechModelId: String?,
    val pauseEndsTurnMs: Int? = null,
    val speechOverRoomPercent: Int? = null,
    val unattendedMicrophoneMs: Int? = null,
    val bargeInMs: Int? = null,
    val bargeInWhileThinking: Boolean? = null,
    /** `SENTENCE`, `PARAGRAPH` or `NONE`: where an answer may be cut for the speech model. */
    val chunking: String = "SENTENCE",
) {
    val pauseMs: Long get() = (pauseEndsTurnMs ?: SILENCE_MS).toLong()
    val overRoom: Double get() = (speechOverRoomPercent ?: (SPEECH_OVER_ROOM * 100).toInt()) / 100.0
    val unattendedMs: Long get() = (unattendedMicrophoneMs ?: LONGEST_TURN_MS).toLong()
    /** Zero is the workspace saying to leave the answer alone, which survives the default. */
    val bargeMs: Long get() = (bargeInMs ?: BARGE_IN_MS).toLong()
    val bargeThinking: Boolean get() = bargeInWhileThinking ?: false

    companion object {
        /** Loud enough to be somebody talking rather than a quiet room; the panel's SPEECH_LEVEL. */
        const val SPEECH_LEVEL = 0.02
        const val SPEECH_OVER_ROOM = 2.5
        const val SILENCE_MS = 2_500
        const val LONGEST_TURN_MS = 600_000
        const val BARGE_IN_MS = 1_000
        /** Below this a room's own noise is not a voice. */
        const val VISIBLE_LEVEL = 0.01
    }
}

/**
 * Hears the end of a turn in a run of loudness readings, one per frame of
 * audio - the level watching of the web panel, without a browser.
 *
 * A turn ends on quiet after speech, quiet for the workspace's pause; a turn
 * that never falls quiet ends at the unattended limit, which is a fuse and not
 * a limit on how much somebody may say. The floor follows the quiet quickly and
 * the loud barely at all, so it is the room rather than the voice, and speech is
 * what stands well clear of it - or is loud in its own right.
 *
 * Talking over an answer for the barge-in hold stops it: a run of talking, with
 * gaps shorter than half the hold bridged, so the spaces between syllables do
 * not reset it while a cough followed by quiet does.
 *
 * No clock of its own: every reading carries its time, which is what lets the
 * suite drive it without a microphone.
 */
class TurnDetector(private val settings: () -> VoiceSettings) {
    sealed interface Heard {
        /** Nothing worth acting on. */
        data object Nothing : Heard
        /** The turn is over: [spoke] says whether anything in it was a voice. */
        data class Ended(val spoke: Boolean) : Heard
        /** Somebody has talked over the answer for long enough to mean it. */
        data object BargeIn : Heard
    }

    private var startedAt = -1L
    private var spokeAt: Long? = null
    private var floor = VoiceSettings.SPEECH_LEVEL
    private var overSince: Long? = null
    private var overLast = 0L

    /** The loudness of the frame just read, 0 to 1, for a meter. */
    @Volatile var level = 0.0
        private set

    /** Whether anything said so far in this turn was a voice. */
    val spoke: Boolean get() = spokeAt != null

    /** A new turn: what was heard before it is forgotten, the room is not. */
    fun restart(now: Long) {
        startedAt = now
        spokeAt = null
        overSince = null
    }

    /**
     * One frame: its loudness, when it was read, whether it is muted, and
     * whether talking now would interrupt something - an answer being read, or
     * a model thinking where the workspace allows that to be stopped.
     */
    fun frame(loudness: Double, now: Long, muted: Boolean = false, interruptible: Boolean = false): Heard {
        if (startedAt < 0) startedAt = now
        val s = settings()
        level = if (loudness < VoiceSettings.VISIBLE_LEVEL) 0.0 else (loudness * 6).coerceAtMost(1.0)
        floor = if (loudness < floor) loudness else floor * 0.999 + loudness * 0.001
        val aVoice = !muted && (loudness > maxOf(floor * s.overRoom, VoiceSettings.VISIBLE_LEVEL) || loudness > VoiceSettings.SPEECH_LEVEL)
        if (aVoice) spokeAt = now

        if (!interruptible || s.bargeMs <= 0) {
            overSince = null
        } else if (aVoice) {
            val since = overSince
            if (since == null || now - overLast > s.bargeMs / 2) overSince = now
            overLast = now
            if (now - (overSince ?: now) >= s.bargeMs) {
                overSince = null
                return Heard.BargeIn
            }
        }

        val quietLongEnough = spokeAt?.let { now - it > s.pauseMs } ?: false
        val goneOnTooLong = now - startedAt > s.unattendedMs
        if (quietLongEnough || goneOnTooLong) {
            val said = spokeAt != null
            restart(now)
            return Heard.Ended(said)
        }
        return Heard.Nothing
    }

    companion object {
        /** The root mean square of signed 16-bit little-endian samples, 0 to 1. */
        fun loudness(pcm: ByteArray, length: Int = pcm.size): Double {
            val samples = length / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            var i = 0
            while (i + 1 < length) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort() / 32768.0
                sum += sample * sample
                i += 2
            }
            return sqrt(sum / samples)
        }
    }
}

/**
 * Cuts an answer into pieces for the speech model, where the workspace has said
 * it may be cut - `pieces()` of the web interface's `readAloud.ts`, with the
 * same boundaries and the same sizes, so the first words come as soon in the
 * terminal as in the browser.
 */
object SpeechChunker {
    private val SENTENCE_END = Regex("""[.!?…]["')\]]*(?=\s)|\n""")
    private val PARAGRAPH_END = Regex("""\n[ \t]*\n+""")
    /** Short fragments wait for the sentence after them, unless the answer is over. */
    const val SHORTEST_TO_SPEAK = 24
    /** About as much as anybody says in one breath. */
    const val LONGEST_TO_SPEAK = 220

    data class Cut(val parts: List<String>, val upTo: Int)

    fun pieces(text: String, whole: Boolean, chunking: String): Cut {
        val boundary = when (chunking) {
            "SENTENCE" -> SENTENCE_END
            "PARAGRAPH" -> PARAGRAPH_END
            else -> null
        }
        val cuts = boundary?.findAll(text)?.map { it.range.last + 1 }?.toMutableList() ?: mutableListOf()
        if (whole && cuts.lastOrNull() != text.length) cuts += text.length
        val parts = mutableListOf<String>()
        var start = 0
        var at = 0
        while (at < cuts.size) {
            var end = cuts[at]
            if (chunking == "SENTENCE") {
                while (at + 1 < cuts.size && cuts[at + 1] - start <= LONGEST_TO_SPEAK) {
                    at += 1
                    end = cuts[at]
                }
            }
            parts += text.substring(start, end)
            start = end
            at += 1
        }
        return Cut(parts.map { it.trim() }.filter { it.isNotEmpty() }, start)
    }
}

/**
 * An answer as it is read aloud rather than looked at: code is not read - a
 * listener cannot use a regular expression spelled out letter by letter - and
 * the marks markdown draws with are dropped, a link said by its words.
 */
object Speakable {
    private val FENCE = Regex("""```[^\n]*\n[\s\S]*?(```|$)""")
    private val LINK = Regex("""!?\[([^\]]*)]\([^)]*\)""")
    private val INLINE_CODE = Regex("""`([^`]*)`""")
    private val EMPHASIS = Regex("""(\*\*|__|\*|_|~~)(?=\S)(.+?)(?<=\S)\1""")
    private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s+""")
    private val BULLET = Regex("""(?m)^\s*([-*+]|\d+[.)])\s+""")
    private val QUOTE = Regex("""(?m)^\s*>\s?""")
    private val TABLE_RULE = Regex("""(?m)^\s*\|?\s*:?-{3,}.*$""")
    private val BARE_URL = Regex("""https?://\S+""")

    fun of(markdown: String): String {
        var t = FENCE.replace(markdown, "\n")
        t = LINK.replace(t) { it.groupValues[1] }
        t = INLINE_CODE.replace(t) { it.groupValues[1] }
        t = EMPHASIS.replace(t) { it.groupValues[2] }
        t = HEADING.replace(t, "")
        t = BULLET.replace(t, "")
        t = QUOTE.replace(t, "")
        t = TABLE_RULE.replace(t, "")
        t = BARE_URL.replace(t, "a link")
        t = t.replace("|", " ")
        return t
    }
}
