package io.mszymanski.orknux.shell

import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * An answer read aloud as it is written: what has arrived is cut where the
 * workspace allows, each piece handed to the speech model, and the clips played
 * in order - the next one already being made while the one before it plays, and
 * no further ahead, so a long answer cut short has not been paid for in full.
 * `readAloud.ts` in the web interface, for a terminal.
 *
 * [speak] and [fetch] are the server's speech endpoint and a sound's address;
 * [play] plays one clip to the end and says whether it got there. Each is a
 * function so the suite can hand it a stand-in and hear nothing.
 */
class Reading(
    private val chunking: String,
    private val speak: (String) -> Pair<ByteArray, String?>,
    private val fetch: (String) -> Pair<ByteArray, String?>,
    private val play: (Pcm) -> Boolean,
    private val stopPlaying: () -> Unit = {},
    private val decode: (ByteArray, String?) -> Pcm? = Sound::decode,
    private val onFailure: (String) -> Unit = {},
) {
    private sealed interface Item {
        data class Text(val text: String) : Item
        data class Sound(val url: String) : Item
        data object End : Item
    }

    private class Clip(val pcm: Pcm?, val end: Boolean = false)

    private val pending = LinkedBlockingQueue<Item>()
    /** One clip made ahead of the one playing. */
    private val ready = ArrayBlockingQueue<Clip>(1)
    private var offset = 0
    private var ended = false

    @Volatile var stopped = false
        private set

    /** Sound is coming out of the speakers now. */
    @Volatile var audible = false
        private set

    /** Done when the last of the answer has played, or when it was stopped. */
    val finished = CompletableFuture<Boolean>()

    /** Every piece handed to the speech model, in order: what the suite reads back. */
    val asked = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val maker = Thread.ofVirtual().name("orknux-voice-speech").start(::make)
    private val player = Thread.ofVirtual().name("orknux-voice-play").start(::playAll)

    /**
     * The answer so far, always from its beginning; [done] says no more is
     * coming, and takes the remainder however it ends.
     */
    @Synchronized
    fun push(whole: String, done: Boolean) {
        if (stopped || ended) return
        val speakable = Speakable.of(whole)
        if (offset > speakable.length) offset = speakable.length
        val rest = speakable.substring(offset)
        val cut = SpeechChunker.pieces(rest, done, chunking)
        // Short fragments wait for what follows them: "Yes." alone, then the rest, sounds like two answers.
        if (done || cut.parts.sumOf { it.length } >= SpeechChunker.SHORTEST_TO_SPEAK) {
            cut.parts.forEach { pending.put(Item.Text(it)) }
            offset += cut.upTo
        }
        if (done) {
            ended = true
            pending.put(Item.End)
        }
    }

    /** A whole utterance the agent wrote beside its calls, read at once rather than held for more. */
    @Synchronized
    fun say(text: String) {
        if (stopped || ended) return
        Speakable.of(text).trim().takeIf { it.isNotEmpty() }?.let { pending.put(Item.Text(it)) }
    }

    /** A sound the agent played, in its place: after what was handed over already, before what comes next. */
    @Synchronized
    fun sound(url: String) {
        if (stopped || ended) return
        pending.put(Item.Sound(url))
    }

    /** Abandons it: nothing asked for is played, nothing more is asked for, and what plays stops now. */
    fun stop() {
        // Under the same lock the maker asks under, so once this returns nothing more is asked for.
        synchronized(asking) {
            if (stopped) return
            stopped = true
        }
        audible = false
        stopPlaying()
        maker.interrupt()
        player.interrupt()
        finished.complete(false)
    }

    /** Held while a sentence is asked for and while the reading stops, so the two cannot cross. */
    private val asking = Object()

    private fun make() {
        try {
            while (!stopped) {
                val clip = when (val item = pending.take()) {
                    is Item.End -> Clip(null, end = true)
                    is Item.Text -> {
                        // A sentence taken just as the reading was stopped is not asked for.
                        synchronized(asking) {
                            if (stopped) return
                            asked += item.text
                        }
                        Clip(attempt { speak(item.text) }?.let { (bytes, type) -> decodeOrSay(bytes, type) })
                    }
                    is Item.Sound -> Clip(attempt { fetch(item.url) }?.let { (bytes, type) -> decodeOrSay(bytes, type) })
                }
                ready.put(clip)
                if (clip.end) return
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun decodeOrSay(bytes: ByteArray, type: String?): Pcm? =
        decode(bytes, type) ?: null.also { onFailure("The speech came back as ${type ?: "something"} the Shell cannot play.") }

    private var failedOnce = false

    /** A failure said once a reading, not once a sentence: the rest is likely to fail the same way. */
    private fun <T> attempt(work: () -> T): T? = try {
        work()
    } catch (failure: OrknuxException) {
        if (!stopped && !failedOnce) {
            failedOnce = true
            onFailure(failure.message ?: "The answer could not be read aloud.")
        }
        null
    }

    private fun playAll() {
        try {
            while (!stopped) {
                val clip = ready.take()
                if (clip.end) {
                    audible = false
                    finished.complete(true)
                    return
                }
                val pcm = clip.pcm ?: continue
                audible = true
                play(pcm)
                audible = false
            }
        } catch (_: InterruptedException) {
        } catch (failure: Exception) {
            if (!stopped) onFailure(failure.message ?: "The answer could not be played.")
            finished.complete(false)
        }
    }
}

/**
 * The microphone listened to for as long as voice mode is open, cut into
 * utterances by [TurnDetector] - the web panel's listening, which never closes
 * the microphone between turns.
 *
 * There is no echo cancellation in a terminal. With speakers, everything heard
 * while the answer is read aloud is the answer itself, so it is thrown away and
 * talking over it does not stop it - a key does. With headphones (`/voice
 * headphones`) the microphone hears only the person, and talking over the
 * answer stops it after the workspace's barge-in hold, as in the browser.
 */
class Listener(
    private val read: () -> ByteArray?,
    private val settings: () -> VoiceSettings,
    private val headphones: Boolean,
    /** Whether the answer is audible now. */
    private val speaking: () -> Boolean,
    /** Whether a turn is running with nothing audible: the model thinking. */
    private val thinking: () -> Boolean,
    private val onUtterance: (ByteArray) -> Unit,
    private val onBargeIn: () -> Unit,
    private val flush: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val format: javax.sound.sampled.AudioFormat = Sound.RECORDING,
) {
    val detector = TurnDetector(settings)

    @Volatile var muted = false

    @Volatile private var finishAsked = false

    /** Space while listening: what has been said so far is the whole turn. */
    fun finishNow() { finishAsked = true }

    private val held = ArrayDeque<ByteArray>()
    private var heldBytes = 0
    /** Kept from before the first word, so its start is not clipped: half a second. */
    private val preRoll = (format.frameRate * format.frameSize / 2).toInt()

    /** Reads and judges frames until the microphone gives no more; run on a thread of its own. */
    fun run() {
        var wasSpeaking = false
        while (true) {
            val frame = read() ?: return
            val now = clock()
            val audible = speaking()
            if (audible && !headphones) {
                // What it hears is the answer: none of it is a turn, and the room is not measured by it.
                wasSpeaking = true
                drop(now)
                continue
            }
            if (wasSpeaking && !audible) {
                // The tail of the answer still in the device's buffer is not the person either.
                flush()
                drop(now)
                wasSpeaking = false
                continue
            }
            // Kept before it is judged: the frame that ends a turn belongs to it, and judging forgets what was said.
            keep(frame)
            val heard = detector.frame(TurnDetector.loudness(frame), now, muted, interruptible = (audible && headphones) || (thinking() && settings().bargeThinking))
            when {
                heard is TurnDetector.Heard.BargeIn -> onBargeIn()
                heard is TurnDetector.Heard.Ended -> utterance(heard.spoke)
                finishAsked -> {
                    finishAsked = false
                    val spoke = detector.spoke
                    detector.restart(now)
                    utterance(spoke)
                }
            }
        }
    }

    private fun keep(frame: ByteArray) {
        held.addLast(frame)
        heldBytes += frame.size
        // Nothing said yet: only the moment before the first word is worth sending.
        while (!detector.spoke && heldBytes > preRoll && held.size > 1) heldBytes -= held.removeFirst().size
    }

    private fun drop(now: Long) {
        held.clear()
        heldBytes = 0
        detector.restart(now)
    }

    private fun utterance(spoke: Boolean) {
        if (spoke && heldBytes > 0) {
            val pcm = ByteArrayOutputStream(heldBytes)
            held.forEach { pcm.write(it) }
            onUtterance(Sound.wav(pcm.toByteArray(), format))
        }
        held.clear()
        heldBytes = 0
    }
}

/** Waits up to [millis] for [future], true where it completed. */
internal fun CompletableFuture<*>.within(millis: Long): Boolean =
    runCatching { get(millis, TimeUnit.MILLISECONDS); true }.getOrDefault(isDone)
