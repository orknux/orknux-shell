package io.mszymanski.orknux.shell

import javazoom.jl.decoder.Bitstream
import javazoom.jl.decoder.Decoder
import javazoom.jl.decoder.SampleBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * The computer's microphone and speakers, through Java Sound - the JDK's own
 * audio library, the same on Linux (ALSA, and PulseAudio or PipeWire behind
 * it) and on Windows (DirectSound) - with MP3, which is what the server's
 * speech endpoint answers, decoded by JLayer. Nothing here is a hand-made
 * driver: what is Orknux's own is deciding when somebody has stopped talking.
 *
 * In the native binary Java Sound needs the JDK's small sound library
 * (`libjsound.so`, `jsound.dll`), which a native image cannot fold into itself.
 * The release build puts it inside the binary as a resource and [prepare]
 * writes it out once, beside the configuration, the first time voice is used;
 * one left beside the executable by a local `native-image` build is used as it
 * is.
 */
object Sound {
    /** What is recorded and sent: 16 kHz mono 16-bit, what speech models are trained on and small to upload. */
    val RECORDING = AudioFormat(16_000f, 16, 1, true, false)
    /** What is tried where the device will not record at 16 kHz itself. */
    private val FALLBACKS = listOf(RECORDING, AudioFormat(48_000f, 16, 1, true, false), AudioFormat(44_100f, 16, 1, true, false))

    private const val RESOURCES = "/io/mszymanski/orknux/shell/sound/"

    @Volatile private var prepared: String? = null

    /** Whether this is the native binary, where Java Sound's library is not where the JDK keeps it. */
    private val native: Boolean get() = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"

    /**
     * Makes Java Sound's library reachable in the native binary; nothing to do
     * on a JVM. Null when ready, else why sound cannot be had.
     */
    @Synchronized
    fun prepare(): String? {
        if (!native) return null
        prepared?.let { return it.ifEmpty { null } }
        val why = runCatching { prepareNative() }.getOrElse { it.message ?: it.javaClass.simpleName }
        prepared = why ?: ""
        return why
    }

    private fun prepareNative(): String? {
        val name = if (Platform.isWindows()) "jsound.dll" else "libjsound.so"
        val beside = ProcessHandle.current().info().command().map { Path.of(it).toAbsolutePath().parent }.orElse(null)
        val dir = if (beside != null && Files.isRegularFile(beside.resolve(name))) {
            beside
        } else {
            val bytes = Sound::class.java.getResourceAsStream(RESOURCES + name)?.use { it.readAllBytes() }
                ?: return "this build of the Shell carries no sound library ($name)"
            val dir = ConfigPaths.configFile().parent.resolve("sound").resolve(Version.VERSION)
            Files.createDirectories(dir)
            runCatching {
                if (!Platform.isWindows()) Files.setPosixFilePermissions(dir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
            val target = dir.resolve(name)
            // Written again where it differs: a library a stranger could have replaced is not one to load.
            if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
                val part = Files.createTempFile(dir, name, ".part")
                Files.write(part, bytes)
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            dir
        }
        // Read by the native image's library loader on each load, and by Java Sound for its own properties.
        val path = System.getProperty("java.library.path").orEmpty()
        System.setProperty("java.library.path", if (path.isEmpty()) dir.toString() else dir.toString() + java.io.File.pathSeparator + path)
        if (System.getProperty("java.home").isNullOrEmpty()) System.setProperty("java.home", dir.toString())
        return null
    }

    /**
     * The microphone, open and recording, or why there is none.
     *
     * `ORKNUX_SHELL_MICROPHONE=<file.wav>` hears that recording instead, once,
     * and then a quiet room: how voice mode is driven end to end on a machine
     * with nobody to talk to it - a test, or the manual's pictures.
     */
    fun microphone(env: Map<String, String> = System.getenv()): Result<Microphone> = runCatching {
        env["ORKNUX_SHELL_MICROPHONE"]?.takeIf { it.isNotBlank() }?.let { file ->
            if (!Files.isRegularFile(Path.of(file))) throw IllegalStateException("ORKNUX_SHELL_MICROPHONE names $file, which is not there")
            val pcm = decode(Files.readAllBytes(Path.of(file)), "audio/wav") ?: throw IllegalStateException("$file is not a WAV recording")
            return@runCatching RecordingMicrophone(pcm)
        }
        prepare()?.let { throw IllegalStateException(it) }
        var last: Throwable? = null
        for (format in FALLBACKS) {
            try {
                val line = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
                line.open(format, (format.frameRate * format.frameSize / 5).toInt())
                line.start()
                return@runCatching LineMicrophone(line, format)
            } catch (failure: Exception) {
                last = failure
            }
        }
        throw IllegalStateException(last?.message ?: "no microphone answered")
    }.recoverCatching { throw IllegalStateException(plainly(it, "microphone")) }

    /** Whether something can be played, without playing anything; null where it can, else why not. */
    fun speakers(): String? = runCatching {
        prepare()?.let { return it }
        val format = AudioFormat(24_000f, 16, 1, true, false)
        if (!AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, format))) return "no audio output answered"
        null
    }.getOrElse { plainly(it, "audio output") }

    /** A failure from Java Sound in words: its messages name interfaces and formats, which says little to anyone. */
    private fun plainly(failure: Throwable, what: String): String {
        val said = failure.message.orEmpty()
        return when {
            said.startsWith("No line matching") -> "no $what was found"
            said.isBlank() -> "the $what could not be opened (${failure.javaClass.simpleName})"
            else -> said
        }
    }

    /** [pcm] recorded in [format], as a WAV file - what whisper.cpp and every other transcriber decodes. */
    fun wav(pcm: ByteArray, format: AudioFormat = RECORDING): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 64)
        AudioInputStream(ByteArrayInputStream(pcm), format, (pcm.size / format.frameSize).toLong()).use {
            AudioSystem.write(it, AudioFileFormat.Type.WAVE, out)
        }
        return out.toByteArray()
    }

    /**
     * A clip as PCM: MP3 through JLayer, and WAV, AIFF or AU through Java Sound
     * itself. Null where it is something neither reads.
     */
    fun decode(audio: ByteArray, contentType: String?): Pcm? {
        val type = contentType.orEmpty().lowercase()
        if (type.contains("mpeg") || type.contains("mp3") || looksLikeMp3(audio)) return mp3(audio)
        return runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(audio)).use { source ->
                val wanted = AudioFormat(source.format.sampleRate, 16, source.format.channels, true, false)
                val pcm = if (source.format.matches(wanted)) source else AudioSystem.getAudioInputStream(wanted, source)
                Pcm(pcm.readAllBytes(), wanted)
            }
        }.getOrNull()
    }

    private fun looksLikeMp3(audio: ByteArray): Boolean =
        audio.size > 3 && ((audio[0] == 'I'.code.toByte() && audio[1] == 'D'.code.toByte() && audio[2] == '3'.code.toByte()) ||
            (audio[0].toInt() and 0xff == 0xff && audio[1].toInt() and 0xe0 == 0xe0))

    private fun mp3(audio: ByteArray): Pcm? {
        val stream = Bitstream(ByteArrayInputStream(audio))
        val decoder = Decoder()
        val out = ByteArrayOutputStream(audio.size * 10)
        var format: AudioFormat? = null
        try {
            while (true) {
                val header = stream.readFrame() ?: break
                val samples = decoder.decodeFrame(header, stream) as SampleBuffer
                if (format == null) format = AudioFormat(decoder.outputFrequency.toFloat(), 16, decoder.outputChannels, true, false)
                val buffer = samples.buffer
                for (i in 0 until samples.bufferLength) {
                    val s = buffer[i].toInt()
                    out.write(s and 0xff)
                    out.write((s shr 8) and 0xff)
                }
                stream.closeFrame()
            }
        } catch (_: Exception) {
            // A clip cut short still plays as far as it goes.
        } finally {
            runCatching { stream.close() }
        }
        return format?.let { Pcm(out.toByteArray(), it) }
    }
}

/** Decoded audio, 16-bit signed little-endian. */
class Pcm(val bytes: ByteArray, val format: AudioFormat) {
    val millis: Long get() = (bytes.size / format.frameSize * 1000L / format.frameRate.toLong().coerceAtLeast(1))
}

/**
 * The microphone, held open for as long as voice mode is: the browser panel
 * learnt that closing it between turns means everything said in the gap is said
 * to nothing. Frames of about 20 ms go to [read] in a loop, at the recording's
 * own format, converted to [Sound.RECORDING] where the device would not record
 * at it.
 */
interface Microphone : AutoCloseable {
    /** One frame of [Sound.RECORDING], blocking until it is there; null once closed. */
    fun read(): ByteArray?

    /** Drops everything recorded and not yet read: what was heard while the Shell was talking. */
    fun flush()
}

/** The device itself, through Java Sound. */
class LineMicrophone(private val line: TargetDataLine, val format: AudioFormat) : Microphone {
    private val frameBytes = ((format.frameRate / 50).toInt() * format.frameSize).coerceAtLeast(format.frameSize)

    override fun read(): ByteArray? {
        if (!line.isOpen) return null
        val buffer = ByteArray(frameBytes)
        var got = 0
        while (got < buffer.size) {
            val n = line.read(buffer, got, buffer.size - got)
            if (n <= 0) return if (line.isOpen) buffer.copyOf(got) else null
            got += n
        }
        return toRecording(buffer)
    }

    override fun flush() {
        runCatching { line.flush() }
    }

    private fun toRecording(frame: ByteArray): ByteArray {
        if (format.matches(Sound.RECORDING)) return frame
        // Every n-th sample, which is enough for speech: 48 and 44.1 kHz down to 16.
        val step = format.frameRate / Sound.RECORDING.frameRate
        val samples = frame.size / 2
        val out = ByteArray(((samples / step).toInt()) * 2)
        var o = 0
        var at = 0.0
        while (o + 1 < out.size) {
            val i = at.toInt() * 2
            if (i + 1 >= frame.size) break
            out[o] = frame[i]
            out[o + 1] = frame[i + 1]
            o += 2
            at += step
        }
        return out.copyOf(o)
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }
}

/**
 * Plays clips one after another on the speakers, and stops at once when told:
 * a person cutting in is not to hear the rest of a sentence first.
 */
class Speaker : AutoCloseable {
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var stopped = false
    private val lock = Any()

    /** Plays [pcm] to the end, or until [stop]; true where it played to the end. */
    fun play(pcm: Pcm): Boolean {
        stopped = false
        val opened = synchronized(lock) {
            val held = line
            if (held != null && held.isOpen && held.format.matches(pcm.format)) held else {
                held?.close()
                (AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, pcm.format)) as SourceDataLine).also {
                    it.open(pcm.format)
                    line = it
                }
            }
        }
        opened.start()
        var at = 0
        val chunk = (pcm.format.frameRate * pcm.format.frameSize / 20).toInt().coerceAtLeast(pcm.format.frameSize)
        while (at < pcm.bytes.size && !stopped) {
            val n = opened.write(pcm.bytes, at, minOf(chunk, pcm.bytes.size - at))
            if (n <= 0) break
            at += n
        }
        if (!stopped) opened.drain()
        return !stopped
    }

    /** Stops what is playing, now. */
    fun stop() {
        stopped = true
        synchronized(lock) {
            line?.let {
                runCatching { it.stop() }
                runCatching { it.flush() }
            }
        }
    }

    override fun close() {
        stop()
        synchronized(lock) {
            runCatching { line?.close() }
            line = null
        }
    }
}

/**
 * A recording heard as if it were said into the microphone, at the pace it was
 * recorded, and then a quiet room for as long as voice mode stays open.
 */
class RecordingMicrophone(pcm: Pcm) : Microphone {
    private val frames: ArrayDeque<ByteArray>
    @Volatile private var open = true

    init {
        val mono16k = if (pcm.format.matches(Sound.RECORDING)) pcm.bytes else {
            val source = AudioInputStream(ByteArrayInputStream(pcm.bytes), pcm.format, (pcm.bytes.size / pcm.format.frameSize).toLong())
            AudioSystem.getAudioInputStream(Sound.RECORDING, source).readAllBytes()
        }
        frames = ArrayDeque(mono16k.toList().chunked(640) { it.toByteArray() })
    }

    override fun read(): ByteArray? {
        if (!open) return null
        Thread.sleep(20)
        if (!open) return null
        return synchronized(frames) { frames.removeFirstOrNull() } ?: ByteArray(640)
    }

    override fun flush() {}

    override fun close() { open = false }
}
