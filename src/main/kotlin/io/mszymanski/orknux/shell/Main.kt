package io.mszymanski.orknux.shell

import org.jline.reader.LineReaderBuilder
import org.jline.reader.MaskingCallback
import org.jline.reader.UserInterruptException
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(Cli.run(args))
}

/** The command line: what `orknux-shell` was asked to do, and doing it. */
object Cli {
    data class Options(
        val command: String = "chat",
        val positional: String? = null,
        val token: String? = null,
        val username: String? = null,
        val passwordStdin: Boolean = false,
        val print: String? = null,
        val workspace: String? = null,
        val agent: String? = null,
        val mode: String? = null,
        val config: String? = null,
    )

    val HELP = """
        Orknux Shell ${Version.VERSION} - Orknux in the terminal.

        Usage:
          orknux                               Chat with an agent (asks for a server and signs in the first time)
          orknux login [server]                Sign in: asks for the server, username and password
              --token orkx_...                 Sign in with an access token instead
              --username NAME                  Do not ask for the username
              --password-stdin                 Read the password from standard input
          orknux logout                        Sign out, and forget the session
          orknux -p "message"                  Say one thing, print the answer, and leave
          orknux --version                     The version

        Options for a chat:
          --workspace NAME   --agent NAME   --mode auto|ask|plan   --config FILE

        The configuration is ${ConfigPaths.configFile()}
    """.trimIndent()

    fun parse(args: Array<String>): Options {
        var o = Options()
        var i = 0
        fun value(name: String): String = args.getOrNull(++i) ?: throw IllegalArgumentException("$name needs a value.")
        while (i < args.size) {
            when (val a = args[i]) {
                "--version", "-V", "-v" -> o = o.copy(command = "version")
                "--help", "-h", "help" -> o = o.copy(command = "help")
                "login", "logout" -> o = o.copy(command = a)
                "--token" -> o = o.copy(token = value(a))
                "--username" -> o = o.copy(username = value(a))
                "--password-stdin" -> o = o.copy(passwordStdin = true)
                "-p", "--print" -> o = o.copy(print = value(a))
                "--workspace" -> o = o.copy(workspace = value(a))
                "--agent" -> o = o.copy(agent = value(a))
                "--mode" -> o = o.copy(mode = value(a).lowercase().also { require(it in Modes.ALL) { "--mode is one of ${Modes.ALL.joinToString()}" } })
                "--config" -> o = o.copy(config = value(a))
                else -> {
                    if (a.startsWith("-")) throw IllegalArgumentException("Unknown option $a. See orknux --help.")
                    if (o.positional != null) throw IllegalArgumentException("Unexpected $a. See orknux --help.")
                    o = o.copy(positional = a)
                }
            }
            i++
        }
        return o
    }

    fun run(args: Array<String>): Int {
        val options = try {
            parse(args)
        } catch (bad: IllegalArgumentException) {
            System.err.println(bad.message)
            return 2
        }
        val store = ConfigStore(options.config?.let { java.nio.file.Path.of(it) } ?: ConfigPaths.configFile())
        return try {
            when (options.command) {
                "version" -> {
                    println("Orknux Shell ${Version.VERSION}")
                    0
                }
                "help" -> {
                    println(HELP)
                    0
                }
                "logout" -> {
                    val was = Login(store, PlainPrompter(stdin(), System.out)).logout()
                    println(if (was) "Signed out." else "Not signed in.")
                    0
                }
                "login" -> login(store, options)
                else -> if (options.print != null) printMode(store, options) else chat(store, options)
            }
        } catch (refused: OrknuxException) {
            System.err.println(refused.message)
            1
        }
    }

    /** UTF-8 out, whatever the platform guessed; a Windows console keeps its own, which is the one it can draw. */
    private fun utf8(stream: java.io.PrintStream, descriptor: java.io.FileDescriptor): java.io.PrintStream =
        if (Platform.isWindows()) stream else java.io.PrintStream(java.io.FileOutputStream(descriptor), true, StandardCharsets.UTF_8)

    private fun stdin() = BufferedReader(InputStreamReader(System.`in`, StandardCharsets.UTF_8))

    private fun login(store: ConfigStore, options: Options): Int {
        val password = if (options.passwordStdin) stdin().readLine() else null
        val interactive = System.console() != null && !options.passwordStdin
        val saved = if (interactive) {
            terminal().use { t -> Login(store, TerminalPrompter(t)).login(options.positional, options.token, options.username, password) }
        } else {
            Login(store, PlainPrompter(stdin(), System.out)).login(options.positional, options.token, options.username, password)
        }
        return if (saved != null) 0 else 1
    }

    private fun printMode(store: ConfigStore, options: Options): Int {
        val config = store.load()
        if (!config.signedIn) {
            System.err.println("Not signed in. Run: orknux login")
            return 1
        }
        val api = Api(Connection.clientFor(store, config))
        val theme = Theme(config.theme, if (System.console() == null) ColourDepth.NONE else ColourDepth.detect())
        return PrintMode(store, config, api, stdin(), utf8(System.out, java.io.FileDescriptor.out), utf8(System.err, java.io.FileDescriptor.err), theme).run(options.print!!, options.workspace, options.agent, options.mode)
    }

    private fun chat(store: ConfigStore, options: Options): Int {
        terminal().use { terminal ->
            var config = store.load()
            if (!config.signedIn || options.positional != null) {
                if (!config.signedIn && config.server == null) terminal.writer().println("Welcome to Orknux Shell. Which Orknux server should it connect to?")
                config = Login(store, TerminalPrompter(terminal)).login(options.positional ?: config.server, options.token, options.username, null) ?: return 1
            }
            var api = Api(Connection.clientFor(store, config))
            if (config.token == null && api.me() == null) {
                terminal.writer().println("The session for ${config.server} has ended; sign in again.")
                config = Login(store, TerminalPrompter(terminal)).login(config.server, null, config.user, null) ?: return 1
                api = Api(Connection.clientFor(store, config))
            }
            if (options.mode != null) config = store.update { it.copy(mode = options.mode) }
            return Interactive(terminal, store, config, api, options.workspace to options.agent).run()
        }
    }

    /** Held, so the setting is not collected with the logger: JLine's warnings would land on the screen being drawn. */
    private val jlineLog = java.util.logging.Logger.getLogger("org.jline").apply { level = java.util.logging.Level.OFF }

    /**
     * The terminal type to read capabilities for. A type this machine's terminfo
     * does not know - xterm-kitty, xterm-ghostty where their entry is not
     * installed - left JLine without the capabilities the status line needs, so
     * it was never drawn; every such terminal speaks xterm, so that is what it
     * is read as.
     */
    fun terminalType(term: String? = System.getenv("TERM"), known: (String) -> Boolean = { t -> runCatching { org.jline.utils.InfoCmp.getInfoCmp(t) }.isSuccess }): String? =
        when {
            term.isNullOrBlank() || Platform.isWindows() -> null
            known(term) -> null
            else -> "xterm-256color"
        }

    fun terminal(): Terminal = TerminalBuilder.builder()
        .system(true)
        .also { builder -> jlineLog.level; terminalType()?.let { builder.type(it) } }
        .name("Orknux Shell")
        // All three: JLine reads the output's encoding from the platform otherwise, and a native image finds US-ASCII there.
        .encoding(StandardCharsets.UTF_8)
        .stdinEncoding(StandardCharsets.UTF_8)
        .stdoutEncoding(StandardCharsets.UTF_8)
        .stderrEncoding(StandardCharsets.UTF_8)
        .jni(true)
        .exec(true)
        .dumb(true)
        .build()
}

/** Prompts in the terminal's line editor; a secret is read with echo off. */
class TerminalPrompter(terminal: Terminal) : Prompter {
    private val reader = LineReaderBuilder.builder().terminal(terminal).build()
    private val writer = terminal.writer()

    override fun ask(prompt: String, default: String?): String? = try {
        reader.readLine("$prompt: ", null, null as MaskingCallback?, default).trim().ifEmpty { default ?: "" }
    } catch (_: UserInterruptException) {
        null
    } catch (_: org.jline.reader.EndOfFileException) {
        null
    }

    override fun secret(prompt: String): String? = try {
        // A mask of 0 echoes nothing at all, not even stars.
        reader.readLine("$prompt: ", 0.toChar())
    } catch (_: UserInterruptException) {
        null
    } catch (_: org.jline.reader.EndOfFileException) {
        null
    }

    override fun say(text: String) {
        writer.println(text)
        writer.flush()
    }
}
