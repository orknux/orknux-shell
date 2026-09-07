package io.mszymanski.orknux.shell

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * The process tools: a POSIX shell, spoken to the way DesktopCommander is.
 *
 * `start_process` carries the contract's oldest promise. Called without
 * `timeout_ms` — which is how Orknux's own `McpShellClient` calls it — it
 * blocks until the command finishes and answers with its combined output,
 * verbatim: no exit code, no commentary, because the caller reads that text
 * as stdout (`uname -sr` must come back as exactly the line `uname` printed).
 * A command that ran and failed is still a normal answer; the only error here
 * is a shell that could not run at all.
 *
 * With `timeout_ms`, it is DC's shape instead: wait that long, and a command
 * still going is handed back as a pid with its output so far, to be followed
 * through `read_process_output`, fed through `interact_with_process`, or
 * ended with `force_terminate`.
 */
@Component
class ProcessTools(private val properties: ShellProperties) {

    val tools: List<Tool> = listOf(
        Tool(
            name = "start_process",
            description = "Run a command in a POSIX shell inside this session's workspace. Without timeout_ms it " +
                "blocks until the command finishes and returns its combined output; with timeout_ms it waits that " +
                "long, and a command still running is returned as a pid to follow with read_process_output.",
            parameters = listOf(
                ToolParameter("command", "string", "A POSIX sh command line."),
                ToolParameter("cwd", "string", "Working directory, inside the workspace. The workspace root when absent.", required = false),
                ToolParameter("timeout_ms", "number", "How long to wait before handing the command back as a background pid.", required = false),
            ),
            run = ::startProcess,
        ),
        Tool(
            name = "read_process_output",
            description = "Read new output from a process started here, waiting briefly for more when there is none yet.",
            parameters = listOf(
                ToolParameter("pid", "number", "The process, as start_process returned it."),
                ToolParameter("timeout_ms", "number", "How long to wait for new output.", required = false),
            ),
            run = ::readOutput,
        ),
        Tool(
            name = "interact_with_process",
            description = "Send a line to a running process's stdin and read what it answers. For REPLs and prompts.",
            parameters = listOf(
                ToolParameter("pid", "number", "The process, as start_process returned it."),
                ToolParameter("input", "string", "What to send. A newline is appended."),
                ToolParameter("timeout_ms", "number", "How long to wait for the answer.", required = false),
            ),
            run = ::interact,
        ),
        Tool(
            name = "force_terminate",
            description = "End a process started in this session.",
            parameters = listOf(ToolParameter("pid", "number", "The process to end.")),
            run = ::terminate,
        ),
        Tool(
            name = "list_sessions",
            description = "List the processes still running in this session.",
            parameters = emptyList(),
            run = { session, _ -> listSessions(session) },
        ),
        Tool(
            name = "list_processes",
            description = "List processes on the whole machine: pid, owner and command.",
            parameters = emptyList(),
            run = { _, _ -> listProcesses() },
        ),
        Tool(
            name = "kill_process",
            description = "Kill any process on the machine by pid.",
            parameters = listOf(ToolParameter("pid", "number", "The process to kill.")),
            run = { _, arguments -> kill(arguments) },
        ),
    )

    private fun startProcess(session: ShellSession, arguments: JsonNode): String {
        val command = arguments.requiredString("command")
        val directory = arguments.optionalString("cwd")?.let(session::resolve) ?: session.root
        if (!Files.isDirectory(directory)) {
            throw ToolRefusedException("`cwd` $directory is not a directory in this workspace")
        }

        val builder = ProcessBuilder(properties.shell, "-c", command)
            .directory(directory.toFile())
            .redirectErrorStream(true)

        val process = try {
            builder.start()
        } catch (failed: IOException) {
            // The one MCP-level error in this file: the shell itself would not
            // run, which is the contract's definition of unreachable.
            throw ShellUnavailableException("The shell `${properties.shell}` could not be started: ${failed.message}")
        }

        val run = ProcessSession(process, command, properties.outputLimit)
        session.processes[run.pid] = run

        val asked = arguments.optionalLong("timeout_ms")
        if (asked == null) {
            // Nothing will ever feed this process; a command that reads stdin
            // should see the end of it rather than wait out the ceiling.
            run.closeInput()
            val done = run.awaitFinished(properties.commandTimeout.toMillis())
            if (!done) run.destroy()
            run.awaitDrained(DRAIN_WAIT)
            session.processes.remove(run.pid)
            val output = run.newOutput()
            return when {
                !done -> output + "\n[command killed after ${properties.commandTimeout}]"
                run.truncated() -> output + "\n[output truncated at ${properties.outputLimit} characters]"
                else -> output
            }
        }

        val done = run.awaitFinished(asked.coerceAtLeast(0))
        if (done) {
            run.awaitDrained(DRAIN_WAIT)
            session.processes.remove(run.pid)
            return run.newOutput() +
                if (run.truncated()) "\n[output truncated at ${properties.outputLimit} characters]" else ""
        }
        return run.newOutput() +
            "\n[process ${run.pid} is still running; follow it with read_process_output]"
    }

    private fun readOutput(session: ShellSession, arguments: JsonNode): String {
        val run = held(session, arguments.requiredLong("pid"))
        return collect(session, run, arguments.optionalLong("timeout_ms"))
    }

    private fun interact(session: ShellSession, arguments: JsonNode): String {
        val run = held(session, arguments.requiredLong("pid"))
        run.send(arguments.requiredString("input") + "\n")
        return collect(session, run, arguments.optionalLong("timeout_ms"))
    }

    /**
     * What is new from a process, waited for a little.
     *
     * Polling, on purpose: the reader thread owns the stream, so this side can
     * only watch the buffer grow, and fifty milliseconds of granularity is
     * well under anything an agent notices. A process seen finished is drained,
     * forgotten, and reported with its exit code — the follow-up tools are the
     * place that code is wanted, and the blocking path is the place it must
     * not appear.
     */
    private fun collect(session: ShellSession, run: ProcessSession, asked: Long?): String {
        val deadline = Instant.now().plusMillis(asked ?: properties.readTimeout.toMillis())
        while (true) {
            if (run.finished()) {
                run.awaitDrained(DRAIN_WAIT)
                session.processes.remove(run.pid)
                val rest = run.newOutput()
                val note = "[process ${run.pid} exited with code ${run.exitCode()}]"
                return if (rest.isEmpty()) note else "$rest\n$note"
            }
            val fresh = run.newOutput()
            if (fresh.isNotEmpty()) return fresh
            if (Instant.now() > deadline) {
                return "[process ${run.pid} is still running with no new output; started ${run.startedAt}]"
            }
            Thread.sleep(50)
        }
    }

    private fun terminate(session: ShellSession, arguments: JsonNode): String {
        val run = held(session, arguments.requiredLong("pid"))
        run.destroy()
        run.awaitDrained(DRAIN_WAIT)
        session.processes.remove(run.pid)
        val rest = run.newOutput()
        val note = "[process ${run.pid} terminated]"
        return if (rest.isEmpty()) note else "$rest\n$note"
    }

    private fun listSessions(session: ShellSession): String {
        val running = session.processes.values.sortedBy { it.startedAt }
        if (running.isEmpty()) return "No processes are running in this session."
        return running.joinToString("\n") { held ->
            val state = if (held.finished()) "finished" else "running for ${Duration.between(held.startedAt, Instant.now())}"
            "${held.pid}  $state  ${held.command}"
        }
    }

    private fun listProcesses(): String =
        ProcessHandle.allProcesses().limit(PROCESS_LIST_LIMIT).map { held ->
            val info = held.info()
            listOf(
                held.pid().toString(),
                info.user().orElse("?"),
                info.commandLine().orElse(info.command().orElse("?")),
            ).joinToString("  ")
        }.toList().joinToString("\n")

    private fun kill(arguments: JsonNode): String {
        val pid = arguments.requiredLong("pid")
        val held = ProcessHandle.of(pid).orElseThrow { ToolRefusedException("There is no process $pid") }
        held.destroyForcibly()
        return "[process $pid killed]"
    }

    /** The session's process, or a refusal naming the pid nothing here owns. */
    private fun held(session: ShellSession, pid: Long): ProcessSession =
        session.processes[pid]
            ?: throw ToolRefusedException("No process $pid was started in this session, or it has already been read to its end")

    private companion object {
        /** How long finishing waits for the reader to catch the stream's tail. */
        const val DRAIN_WAIT = 5_000L
        const val PROCESS_LIST_LIMIT = 500L
    }
}
