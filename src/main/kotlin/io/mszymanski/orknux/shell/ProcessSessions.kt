package io.mszymanski.orknux.shell

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One started process and everything read from it so far.
 *
 * The reader thread is what makes the rest simple: it drains the process's
 * combined output into [buffer] from the moment the process starts, so the
 * process can never block on a full pipe, and the tools only ever look at the
 * buffer. `read_process_output` remembers how far it has read with [cursor],
 * so each call answers with what is new rather than everything again.
 */
class ProcessSession(private val process: Process, val command: String, private val limit: Int) {

    val pid: Long = process.pid()
    val startedAt: Instant = Instant.now()

    private val buffer = StringBuilder()
    private var cursor = 0
    private var dropped = false

    private val reader = Thread {
        try {
            process.inputStream.bufferedReader().use { held ->
                val chunk = CharArray(8 * 1024)
                while (true) {
                    val read = held.read(chunk)
                    if (read < 0) break
                    append(String(chunk, 0, read))
                }
            }
        } catch (_: IOException) {
            // The stream closing under the reader is how a killed process looks.
        }
    }.apply {
        isDaemon = true
        name = "orknux-shell-output-$pid"
        start()
    }

    @Synchronized
    private fun append(text: String) {
        if (buffer.length >= limit) {
            dropped = true
            return
        }
        if (buffer.length + text.length > limit) dropped = true
        buffer.append(text.take(limit - buffer.length))
    }

    /** What has arrived since the last call; empty when nothing has. */
    @Synchronized
    fun newOutput(): String {
        val fresh = buffer.substring(cursor)
        cursor = buffer.length
        return fresh
    }

    /** Whether output was thrown away for passing the limit. Said once, at the end. */
    @Synchronized
    fun truncated(): Boolean = dropped

    fun finished(): Boolean = !process.isAlive

    fun exitCode(): Int = process.exitValue()

    fun awaitFinished(milliseconds: Long): Boolean = process.waitFor(milliseconds, TimeUnit.MILLISECONDS)

    /**
     * Waits for the reader to have seen the end of the stream, so that output
     * handed back after a process finished is actually all of it.
     */
    fun awaitDrained(milliseconds: Long) {
        runCatching { reader.join(milliseconds) }
    }

    fun closeInput() {
        runCatching { process.outputStream.close() }
    }

    /** Hands the process a line on stdin, for the REPL kind of session. */
    fun send(input: String) {
        try {
            process.outputStream.write(input.toByteArray())
            process.outputStream.flush()
        } catch (closed: IOException) {
            throw ToolRefusedException("Process $pid is not taking input: ${closed.message}")
        }
    }

    /** Asks nicely once, then insists. */
    fun destroy() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
