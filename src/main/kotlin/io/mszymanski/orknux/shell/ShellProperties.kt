package io.mszymanski.orknux.shell

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

/**
 * Everything an operator decides, read once at start.
 *
 * Each value has an `ORKNUX_SHELL_...` variable named beside it in
 * `application.yml`, so `env | grep ORKNUX_SHELL_` is the whole of an
 * installation's configuration.
 */
@ConfigurationProperties(prefix = "orknux-shell")
data class ShellProperties(
    /**
     * The one credential. Every request must carry it as `Authorization:
     * Bearer <token>`; there is no default and the server refuses to start
     * without one, because a shell that answers unauthenticated requests is a
     * shell for whoever finds the port.
     */
    val token: String = "",
    /** What runs the commands. A POSIX `sh`; the contract's probes assume one. */
    val shell: String = "/bin/sh",
    /** Where session workspaces are made, one directory per session. */
    val sessionsRoot: Path = Path.of(System.getProperty("java.io.tmpdir"), "orknux-shell"),
    /**
     * How long a session may sit untouched before its workspace is destroyed.
     * The client that opened it may simply vanish — a transport has no goodbye
     * — so this is what keeps abandoned roots from accumulating forever.
     */
    val sessionTimeout: Duration = Duration.ofHours(2),
    /**
     * The ceiling on a `start_process` call that asked for no timeout of its
     * own. Such a call blocks until the command finishes, and a command that
     * never does would otherwise hold the request thread for good.
     */
    val commandTimeout: Duration = Duration.ofMinutes(10),
    /** How long `read_process_output` waits for something new before answering empty-handed. */
    val readTimeout: Duration = Duration.ofSeconds(5),
    /** How much of a process's output is kept, in characters. Beyond it, output is dropped and said so. */
    val outputLimit: Int = 1_000_000,
    /** The build's version, filtered in from the pom; what `initialize` reports. */
    val version: String = "0",
)
