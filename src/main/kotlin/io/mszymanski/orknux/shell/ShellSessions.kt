package io.mszymanski.orknux.shell

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One MCP session: a jailed throwaway workspace and the processes started in it.
 *
 * This is the contract's isolation requirement in one object. The transport
 * session *is* the sandbox: every path a tool call on this session names must
 * resolve under [root], every process it starts begins there, and when the
 * session closes — explicitly, or by sitting idle past the timeout — the root
 * and everything under it is destroyed.
 */
class ShellSession(val id: String, val root: Path, val credentialsDir: Path) {

    /** Processes started on this session and not yet finished-and-drained, by pid. */
    val processes = ConcurrentHashMap<Long, ProcessSession>()

    /**
     * This session's own git global config, and the credential file it names.
     *
     * They live *outside* the jailed workspace, in [credentialsDir], on
     * purpose: a real process reaches them through the absolute
     * `GIT_CONFIG_GLOBAL` this session sets (see [gitEnvironment]), but the
     * file and search tools — which resolve every path back into [root] —
     * cannot, so an agent cannot `read_file` its own token out of the store.
     * They are pointed at on every process this session runs, which is the
     * whole of how one session's GitHub token stays out of another's: two
     * sessions have two directories and two tokens with nothing shared, and
     * both are destroyed with their sessions.
     */
    val gitConfig: Path get() = credentialsDir.resolve("gitconfig")
    val gitCredentials: Path get() = credentialsDir.resolve("git-credentials")

    /** The git environment every process on this session runs with. */
    fun gitEnvironment(): Map<String, String> = mapOf(
        // The per-session global config; git treats a missing file as empty,
        // so this is safe before github_login has written anything.
        "GIT_CONFIG_GLOBAL" to gitConfig.toString(),
        // No machine-wide config leaks a credential or a helper into a session.
        "GIT_CONFIG_NOSYSTEM" to "1",
    )

    @Volatile
    var touched: Instant = Instant.now()
        private set

    fun touch() {
        touched = Instant.now()
    }

    /**
     * The jail. A relative path resolves against the root; `~` is read as the
     * root, since inside a throwaway workspace that is the only home there is;
     * an absolute path is taken at its word and must land inside. Anything
     * that normalises to outside — however many `..` it took — is refused
     * with the root named, so the agent learns where it is allowed to be.
     *
     * Symlinks are not chased here: a link that points out of the root can
     * only exist if something inside already wrote it, and the deployment (a
     * container, for anything real) is what bounds the process tools anyway.
     */
    fun resolve(raw: String): Path {
        val worded = when {
            raw == "~" -> return root
            raw.startsWith("~/") -> root.resolve(raw.removePrefix("~/"))
            else -> Path.of(raw)
        }
        val resolved = (if (worded.isAbsolute) worded else root.resolve(worded)).normalize()
        if (!resolved.startsWith(root)) {
            throw ToolRefusedException(
                "`$raw` is outside this session's workspace. Everything lives under $root, " +
                    "and relative paths resolve against it.",
            )
        }
        return resolved
    }

    /** Ends everything this session owns: its processes, its files, its token. */
    fun destroy() {
        processes.values.forEach { held -> runCatching { held.destroy() } }
        processes.clear()
        // The workspace and the credential directory both — the token must not
        // outlive the session any more than the files do.
        deleteTree(root)
        deleteTree(credentialsDir)
    }

    private fun deleteTree(top: Path) {
        if (!Files.exists(top)) return
        Files.walkFileTree(
            top,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    runCatching { Files.delete(file) }
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(directory: Path, failed: IOException?): FileVisitResult {
                    runCatching { Files.delete(directory) }
                    return FileVisitResult.CONTINUE
                }

                // A file that vanished mid-walk was going to be deleted anyway.
                override fun visitFileFailed(file: Path, failed: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
    }
}

/**
 * The sessions this server currently holds, keyed by the id the transport
 * carries in `Mcp-Session-Id`.
 *
 * Opened on `initialize`, found and touched on every later request, closed on
 * the transport's DELETE — and swept when a client stopped calling without
 * saying goodbye, which over HTTP is not rude, just how transports end.
 */
@Component
class ShellSessionRegistry(private val properties: ShellProperties, meters: MeterRegistry) {

    private val sessions = ConcurrentHashMap<String, ShellSession>()

    init {
        // How many workspaces exist right now: the number an operator watches,
        // because a leak here is disk filling up in session-sized steps.
        meters.gaugeMapSize("orknux.shell.sessions.open", Tags.empty(), sessions)
    }

    fun open(): ShellSession {
        Files.createDirectories(properties.sessionsRoot)
        // A UUID from SecureRandom: the id travels in a header and is the only
        // thing tying a caller to a workspace, so it must not be guessable.
        val id = UUID.randomUUID().toString()
        val root = Files.createDirectory(properties.sessionsRoot.resolve(id))
        // The credential directory sits beside the workspace, not inside it, so
        // the jailed file tools cannot reach the token; see ShellSession.
        val credentialsDir = Files.createDirectory(properties.sessionsRoot.resolve("$id.cred"))
        // 700 where the filesystem understands it: both are one session's world
        // and no other account's business.
        runCatching {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
            Files.setPosixFilePermissions(credentialsDir, PosixFilePermissions.fromString("rwx------"))
        }
        val opened = ShellSession(id, root, credentialsDir)
        sessions[id] = opened
        log.info("Opened session {} at {}", id, root)
        return opened
    }

    /** The session this id names, touched because being asked for is being used. */
    fun find(id: String?): ShellSession? = id?.let(sessions::get)?.also { it.touch() }

    /** True when there was a session to close; its workspace is gone either way after this. */
    fun close(id: String?): Boolean {
        val held = id?.let(sessions::remove) ?: return false
        held.destroy()
        log.info("Closed session {}", id)
        return true
    }

    /**
     * Destroys sessions nothing has touched within the timeout. A minute of
     * lag on a two-hour timeout is noise, and a sweep that runs often stays
     * cheap because there is rarely anything to do.
     */
    @Scheduled(fixedDelayString = "PT1M")
    fun sweep() {
        val cutoff = Instant.now().minus(properties.sessionTimeout)
        sessions.values.filter { it.touched < cutoff }.forEach { idle ->
            log.info("Sweeping session {} last touched {}", idle.id, idle.touched)
            close(idle.id)
        }
    }

    /** Workspaces are throwaway by contract; a stopping server throws them away. */
    @PreDestroy
    fun shutdown() {
        sessions.keys.toList().forEach(::close)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ShellSessionRegistry::class.java)
    }
}
