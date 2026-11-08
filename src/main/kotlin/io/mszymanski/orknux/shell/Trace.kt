package io.mszymanski.orknux.shell

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** `ORKNUX_SHELL_TRACE=<file>`: what the keyboard and the phases did, for finding out why a terminal misbehaves. */
object Trace {
    private val file: Path? = System.getenv("ORKNUX_SHELL_TRACE")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    fun log(what: String) {
        val to = file ?: return
        runCatching {
            Files.writeString(to, "${System.currentTimeMillis() % 100_000} [${Thread.currentThread().name}] $what\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        }
    }
}
