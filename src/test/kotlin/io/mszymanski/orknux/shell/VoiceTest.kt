package io.mszymanski.orknux.shell

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice mode, everything of it that needs no microphone: turn-taking, cutting, reading, the server's endpoints. */
class VoiceTest {
    private val defaults = VoiceSettings(transcriptionModelId = "1", speechModelId = "2")

    // ---- hearing the end of a turn --------------------------------------------------------------

    /** Frames 20 ms apart, loud ([level]) or quiet, from [from]; what the detector said for each. */
    private fun TurnDetector.feed(from: Long, millis: Long, level: Double, interruptible: Boolean = false, muted: Boolean = false): List<Pair<Long, TurnDetector.Heard>> =
        (0 until millis step 20).map { at -> (from + at) to frame(level, from + at, muted, interruptible) }

    @Test
    fun `a turn ends on the workspace's pause after speech, and not before`() {
        val d = TurnDetector { defaults }
        d.feed(0, 1_000, 0.001)
        d.feed(1_000, 1_000, 0.2)
        val quiet = d.feed(2_000, 3_000, 0.001)
        val ended = quiet.first { it.second is TurnDetector.Heard.Ended }
        assertEquals(TurnDetector.Heard.Ended(true), ended.second)
        // Spoke last at 1980; 2.5 s of quiet after it, not a moment sooner.
        assertTrue(ended.first - 1_980 > 2_500 && ended.first - 1_980 <= 2_520, "ended at ${ended.first}")
    }

    @Test
    fun `a pause the workspace chose is the one in force`() {
        val d = TurnDetector { defaults.copy(pauseEndsTurnMs = 800) }
        d.feed(0, 500, 0.2)
        val ended = d.feed(500, 2_000, 0.001).first { it.second is TurnDetector.Heard.Ended }
        assertTrue(ended.first < 1_400, "ended at ${ended.first}")
    }

    @Test
    fun `a quiet room is not a turn, and the unattended fuse ends one that never falls quiet`() {
        val silent = TurnDetector { defaults.copy(unattendedMicrophoneMs = 5_000) }
        val heard = silent.feed(0, 6_000, 0.001).map { it.second }.filterIsInstance<TurnDetector.Heard.Ended>()
        assertEquals(listOf(TurnDetector.Heard.Ended(false)), heard)

        val noisy = TurnDetector { defaults.copy(unattendedMicrophoneMs = 5_000) }
        val ended = noisy.feed(0, 6_000, 0.3).first { it.second is TurnDetector.Heard.Ended }
        assertEquals(TurnDetector.Heard.Ended(true), ended.second)
    }

    @Test
    fun `muted, nothing is speech`() {
        val d = TurnDetector { defaults }
        d.feed(0, 1_000, 0.3, muted = true)
        assertFalse(d.spoke)
    }

    @Test
    fun `talking over the answer stops it after the hold, across the gaps between words, and a cough does not`() {
        val d = TurnDetector { defaults }
        // A cough: a burst, then quiet longer than half the hold.
        val cough = d.feed(0, 200, 0.3, interruptible = true) + d.feed(200, 800, 0.001, interruptible = true)
        assertTrue(cough.none { it.second == TurnDetector.Heard.BargeIn })

        // Words with short gaps between them, for over a second: that is somebody interrupting.
        val talk = (0 until 6).flatMap { i ->
            d.feed(1_000 + i * 300L, 200, 0.3, interruptible = true) + d.feed(1_200 + i * 300L, 100, 0.001, interruptible = true)
        }
        val barge = talk.firstOrNull { it.second == TurnDetector.Heard.BargeIn }
        assertNotNull(barge)
        assertTrue(barge.first >= 2_000, "stopped at ${barge.first}")
    }

    @Test
    fun `a barge-in hold of zero leaves the answer alone`() {
        val d = TurnDetector { defaults.copy(bargeInMs = 0) }
        assertTrue(d.feed(0, 3_000, 0.3, interruptible = true).none { it.second == TurnDetector.Heard.BargeIn })
    }

    @Test
    fun `loudness is the root mean square of 16-bit samples`() {
        assertEquals(0.0, TurnDetector.loudness(ByteArray(320)))
        val full = ByteArray(320) { if (it % 2 == 0) 0x00 else 0x40 } // 0x4000 = half scale
        assertEquals(0.5, TurnDetector.loudness(full), 1e-6)
    }

    // ---- cutting and cleaning an answer ---------------------------------------------------------

    @Test
    fun `sentences are gathered up to a breath, and a half-written one waits`() {
        val cut = SpeechChunker.pieces("First sentence here. Second one! And a third that is still being wri", false, "SENTENCE")
        assertEquals(listOf("First sentence here. Second one!"), cut.parts)
        assertEquals("First sentence here. Second one!".length, cut.upTo)
        val whole = SpeechChunker.pieces("And a third.", true, "SENTENCE")
        assertEquals(listOf("And a third."), whole.parts)
    }

    @Test
    fun `paragraphs are cut at blank lines only, and none waits for the end`() {
        val text = "One. Two.\n\nThree. Four.\n\nFive"
        assertEquals(listOf("One. Two.", "Three. Four."), SpeechChunker.pieces(text, false, "PARAGRAPH").parts)
        assertEquals(emptyList(), SpeechChunker.pieces(text, false, "NONE").parts)
        assertEquals(listOf(text), SpeechChunker.pieces(text, true, "NONE").parts)
    }

    @Test
    fun `code, marks and addresses are not read aloud`() {
        val said = Speakable.of("## Done\n\n**Run** `make` - see [the guide](https://x.example/g).\n\n```sh\nrm -rf /\n```\n- one\n- two\nhttps://a.example/b")
        assertFalse(said.contains("rm -rf"))
        assertFalse(said.contains("**") || said.contains("`") || said.contains("##") || said.contains("]("))
        assertTrue(said.contains("Run make - see the guide."))
        assertTrue(said.contains("one\ntwo"))
        assertTrue(said.contains("a link"))
    }

    // ---- reading aloud --------------------------------------------------------------------------

    private val silence = Pcm(ByteArray(3_200), Sound.RECORDING)

    @Test
    fun `an answer is read in order as it arrives, and finishes when it is over`() {
        val played = CopyOnWriteArrayList<String>()
        val reading = Reading(
            chunking = "SENTENCE",
            speak = { text -> text.toByteArray() to "test/plain" },
            fetch = { url -> url.toByteArray() to "test/plain" },
            play = { pcm -> played += String(pcm.bytes); true },
            decode = { bytes, _ -> Pcm(bytes, Sound.RECORDING) },
        )
        reading.push("Hello there, this is the first sentence. And", false)
        reading.sound("/api/sounds/1")
        reading.push("Hello there, this is the first sentence. And the second one.", true)
        assertTrue(reading.finished.get(5, TimeUnit.SECONDS))
        assertEquals(listOf("Hello there, this is the first sentence.", "/api/sounds/1", "And the second one."), played)
    }

    @Test
    fun `stopping a reading stops what plays and asks for nothing more`() {
        val playing = CountDownLatch(1)
        val release = CountDownLatch(1)
        var stoppedSpeaker = false
        val reading = Reading(
            chunking = "SENTENCE",
            speak = { text -> text.toByteArray() to null },
            fetch = { error("not asked") },
            play = { playing.countDown(); release.await(5, TimeUnit.SECONDS); false },
            stopPlaying = { stoppedSpeaker = true; release.countDown() },
            decode = { _, _ -> silence },
        )
        reading.push("The first sentence is long enough. The second is too, and more. ".repeat(10), false)
        assertTrue(playing.await(5, TimeUnit.SECONDS))
        reading.stop()
        assertTrue(stoppedSpeaker)
        assertFalse(reading.finished.get(1, TimeUnit.SECONDS))
        val asked = reading.asked.size
        reading.push("More words arriving after the stop. ".repeat(5), true)
        Thread.sleep(100)
        assertEquals(asked, reading.asked.size)
        // One playing and one made ahead, no more.
        assertTrue(asked <= 3, "asked for $asked pieces")
    }

    @Test
    fun `a speech model that refuses is said once, not once a sentence`() {
        val said = CopyOnWriteArrayList<String>()
        val reading = Reading(
            chunking = "SENTENCE",
            speak = { throw OrknuxException("This workspace has no speech model.") },
            fetch = { error("not asked") },
            play = { true },
            onFailure = { said += it },
        )
        reading.push("One sentence that is long. Another one that is long. A third one too.\n", true)
        reading.finished.get(5, TimeUnit.SECONDS)
        assertEquals(listOf("This workspace has no speech model."), said)
    }

    // ---- listening ------------------------------------------------------------------------------

    private fun tone(level: Double): ByteArray {
        val samples = 320
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 440 * i / 16_000) * level * 32767 * 1.414).toInt().coerceIn(-32768, 32767)
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    @Test
    fun `an utterance is cut from the stream as a WAV, with a moment before the first word and not the minute of quiet`() {
        val frames = LinkedBlockingQueue<ByteArray>()
        repeat(150) { frames += tone(0.0005) } // three seconds of a quiet room
        repeat(50) { frames += tone(0.2) } // a second of talking
        repeat(140) { frames += tone(0.0005) } // and the pause that ends it
        var now = 0L
        val utterances = CopyOnWriteArrayList<ByteArray>()
        val listener = Listener(
            read = { frames.poll() },
            settings = { defaults },
            headphones = false,
            speaking = { false },
            thinking = { false },
            onUtterance = { utterances += it },
            onBargeIn = {},
            clock = { now.also { now += 20 } },
        )
        listener.run()
        assertEquals(1, utterances.size)
        val wav = AudioSystem.getAudioInputStream(ByteArrayInputStream(utterances[0]))
        assertEquals(16_000f, wav.format.sampleRate)
        assertEquals(1, wav.format.channels)
        val seconds = wav.frameLength / 16_000.0
        // Half a second before, one of speech, two and a half of pause: not the three seconds of room before it.
        assertTrue(seconds in 3.5..4.2, "recorded $seconds s")
    }

    @Test
    fun `with speakers, what is heard while the answer plays is thrown away`() {
        val frames = LinkedBlockingQueue<ByteArray>()
        repeat(200) { frames += tone(0.3) } // the answer, heard through the microphone
        repeat(200) { frames += tone(0.0005) }
        var n = 0
        val utterances = CopyOnWriteArrayList<ByteArray>()
        var barged = false
        var flushed = 0
        val listener = Listener(
            read = { frames.poll().also { n++ } },
            settings = { defaults },
            headphones = false,
            speaking = { n <= 200 },
            thinking = { false },
            onUtterance = { utterances += it },
            onBargeIn = { barged = true },
            flush = { flushed++ },
            clock = { n * 20L },
        )
        listener.run()
        assertTrue(utterances.isEmpty())
        assertFalse(barged)
        assertEquals(1, flushed)
    }

    @Test
    fun `with headphones, talking over the answer stops it`() {
        val frames = LinkedBlockingQueue<ByteArray>()
        repeat(100) { frames += tone(0.3) }
        var n = 0
        var barged = 0
        val listener = Listener(
            read = { frames.poll().also { n++ } },
            settings = { defaults },
            headphones = true,
            speaking = { true },
            thinking = { false },
            onUtterance = {},
            onBargeIn = { barged++ },
            clock = { n * 20L },
        )
        listener.run()
        assertTrue(barged >= 1)
    }

    @Test
    fun `Space ends the turn at once with what has been said`() {
        val frames = LinkedBlockingQueue<ByteArray>()
        repeat(30) { frames += tone(0.2) }
        val utterances = CopyOnWriteArrayList<ByteArray>()
        var n = 0
        lateinit var listener: Listener
        listener = Listener(
            read = { frames.poll().also { if (++n == 30) listener.finishNow() } },
            settings = { defaults },
            headphones = false,
            speaking = { false },
            thinking = { false },
            onUtterance = { utterances += it },
            onBargeIn = {},
            clock = { n * 20L },
        )
        listener.run()
        assertEquals(1, utterances.size)
    }

    // ---- sound ----------------------------------------------------------------------------------

    @Test
    fun `MP3 from the speech endpoint is decoded to PCM`() {
        val mp3 = VoiceTest::class.java.getResourceAsStream("tone.mp3")!!.readAllBytes()
        val pcm = Sound.decode(mp3, "audio/mp3")
        assertNotNull(pcm)
        assertEquals(24_000f, pcm.format.sampleRate)
        assertEquals(16, pcm.format.sampleSizeInBits)
        assertTrue(pcm.millis in 400..700, "${pcm.millis} ms")
        // ffmpeg's sine is an eighth of full scale: a root mean square near 0.088, and not silence.
        assertTrue(TurnDetector.loudness(pcm.bytes) in 0.05..0.12)
    }

    @Test
    fun `WAV goes both ways, and what neither library reads is said to be unplayable`() {
        val wav = Sound.wav(tone(0.2))
        assertEquals("RIFF", String(wav, 0, 4, StandardCharsets.US_ASCII))
        val back = Sound.decode(wav, "audio/wav")
        assertNotNull(back)
        assertEquals(640, back.bytes.size)
        assertNull(Sound.decode("not audio".toByteArray(), "text/plain"))
    }

    @Test
    fun `a recording stands in for the microphone, then a quiet room, and a missing one is said`() {
        val file = kotlin.io.path.createTempFile(suffix = ".wav")
        try {
            java.nio.file.Files.write(file, Sound.wav(tone(0.2) + tone(0.2)))
            val mic = Sound.microphone(mapOf("ORKNUX_SHELL_MICROPHONE" to file.toString())).getOrThrow()
            assertTrue(TurnDetector.loudness(mic.read()!!) > 0.1)
            assertTrue(TurnDetector.loudness(mic.read()!!) > 0.1)
            assertEquals(0.0, TurnDetector.loudness(mic.read()!!))
            mic.close()
            assertNull(mic.read())
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
        val missing = Sound.microphone(mapOf("ORKNUX_SHELL_MICROPHONE" to "/nowhere/said.wav")).exceptionOrNull()
        assertEquals("ORKNUX_SHELL_MICROPHONE names /nowhere/said.wav, which is not there", missing?.message)
    }

    // ---- the server ----------------------------------------------------------------------------

    private class Server : AutoCloseable {
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val seen = CopyOnWriteArrayList<Pair<String, ByteArray>>()
        val types = CopyOnWriteArrayList<String>()
        var workspace = """{"id":"3","transcriptionModelId":"11","speechModelId":null,"voicePauseEndsTurnMs":1800,"voiceSpeechOverRoomPercent":null,""" +
            """"voiceUnattendedMicrophoneMs":null,"voiceBargeInMs":0,"voiceBargeInWhileThinking":true,"voiceSpeechChunking":"PARAGRAPH"}"""
        var refuseVoiceFields = false

        init {
            http.createContext("/") { x ->
                val body = x.requestBody.readAllBytes()
                seen += "${x.requestMethod} ${x.requestURI.path}" to body
                types += x.requestHeaders.getFirst("Content-Type").orEmpty()
                val (status, type, answer) = when (x.requestURI.path) {
                    "/graphql" -> {
                        val q = String(body)
                        if (refuseVoiceFields && q.contains("voicePause")) Triple(200, "application/json", """{"errors":[{"message":"Field 'voicePauseEndsTurnMs' in type 'Workspace' is undefined"}]}""".toByteArray())
                        else Triple(200, "application/json", """{"data":{"workspace":$workspace}}""".toByteArray())
                    }
                    "/api/workspaces/3/transcription" -> Triple(200, "application/json", """{"text":" turn the lights off ","millis":120}""".toByteArray())
                    "/api/workspaces/4/transcription" -> Triple(409, "application/json", """{"error":"This workspace has no transcription model."}""".toByteArray())
                    "/api/workspaces/3/speech" -> Triple(200, "audio/mp3", byteArrayOf(1, 2, 3))
                    "/api/workspaces/4/speech" -> Triple(502, "application/json", """{"error":"The speech model did not answer."}""".toByteArray())
                    else -> Triple(404, "application/json", "{}".toByteArray())
                }
                x.responseHeaders.add("Content-Type", type)
                x.sendResponseHeaders(status, answer.size.toLong())
                x.responseBody.use { it.write(answer) }
            }
            http.start()
        }

        val url get() = "http://127.0.0.1:${http.address.port}"
        override fun close() = http.stop(0)
    }

    @Test
    fun `the workspace's models and turn-taking are read, and an older server's defaults stand`() {
        Server().use { server ->
            val api = Api(OrknuxClient(server.url, token = "orkx_t"))
            val s = api.voiceSettings("3")
            assertEquals("11", s.transcriptionModelId)
            assertNull(s.speechModelId)
            assertEquals(1_800, s.pauseMs)
            assertEquals(2.5, s.overRoom)
            assertEquals(0, s.bargeMs)
            assertTrue(s.bargeThinking)
            assertEquals("PARAGRAPH", s.chunking)

            server.refuseVoiceFields = true
            server.workspace = """{"id":"3","transcriptionModelId":"11","speechModelId":"12"}"""
            val older = api.voiceSettings("3")
            assertEquals("12", older.speechModelId)
            assertEquals(2_500, older.pauseMs)
            assertEquals(1_000, older.bargeMs)
            assertEquals("SENTENCE", older.chunking)
        }
    }

    @Test
    fun `a recording is uploaded as a WAV part named audio, and a refusal comes back in the server's words`() {
        Server().use { server ->
            val api = Api(OrknuxClient(server.url, token = "orkx_t"))
            val wav = Sound.wav(tone(0.2))
            assertEquals("turn the lights off", api.transcribe("3", wav).trim())
            val (_, body) = server.seen.last()
            val text = String(body, StandardCharsets.ISO_8859_1)
            assertTrue(text.contains("name=\"audio\"; filename=\"speech.wav\""))
            assertTrue(text.contains("Content-Type: audio/wav"))
            assertTrue(text.contains("RIFF"))
            assertTrue(server.types.last().startsWith("multipart/form-data; boundary="))

            val refused = runCatching { api.transcribe("4", wav) }.exceptionOrNull()
            assertEquals("This workspace has no transcription model.", refused?.message)
        }
    }

    @Test
    fun `speech is asked for with the text, and comes back as bytes with their type`() {
        Server().use { server ->
            val api = Api(OrknuxClient(server.url, token = "orkx_t"))
            val (audio, type) = api.speak("3", "Hello.")
            assertEquals(listOf<Byte>(1, 2, 3), audio.toList())
            assertEquals("audio/mp3", type)
            assertEquals("Hello.", Json.parse(String(server.seen.last().second)).str("text"))
            assertEquals("The speech model did not answer.", runCatching { api.speak("4", "Hi") }.exceptionOrNull()?.message)
            // A sound elsewhere on the web is not fetched with this server's credentials.
            assertTrue(runCatching { api.sound("https://elsewhere.example/a.mp3") }.isFailure)
        }
    }

    @Test
    fun `a spoken turn says voice to the stream, and a played sound is an event`() {
        StubOrknux(emptyList()).use { stub ->
            val api = Api(OrknuxClient(stub.url, cookies = mapOf("SESSION" to "s3cr3t")))
            val conversation = Conversation(api, Workspace("1", "Main", null), null, "auto")
            conversation.send("hello", voice = true) {}
            conversation.send("typed")  {}
            assertEquals(listOf(true, false), stub.streamRequests.map { it.bool("voice") })
        }
        val play = ChatEvent.of(SseFrame("play", """{"url":"/api/chats/7/sounds/1","filename":"a.mp3","contentType":"audio/mpeg"}"""))
        assertEquals(ChatEvent.Play("/api/chats/7/sounds/1", "audio/mpeg"), play)
        assertEquals(Slash("voice", "headphones"), SlashCommands.parse("/voice headphones"))
        assertEquals(Slash("voice", null), SlashCommands.parse("/talk"))
    }
}
