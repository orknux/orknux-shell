package io.mszymanski.orknux.shell

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** What the person said to a command they were asked about. */
sealed interface Approval {
    data object Once : Approval
    /** Run it, and keep [pattern] as an allow rule. */
    data class Pattern(val pattern: String) : Approval
    data object Declined : Approval
}

/** Asks the person about one command. Blocks until they answer. */
fun interface Approver {
    fun ask(request: RunRequest, decision: Decision): Approval
}

/** What the person sees of the device door: each command that ran or did not. */
interface DeviceReport {
    fun running(request: RunRequest, approval: String) {}
    fun ran(request: RunRequest, result: RunResult, approval: String) {}
    fun refused(request: RunRequest, why: String) {}
    fun notice(text: String) {}
    fun error(text: String) {}
}

/**
 * The door the server's agent reaches this computer by, while the shell is open.
 *
 * Long polling, as the Chrome extension does it: ask `GET /api/terminal/next`
 * for work, get a command or a 204 after the wait, do the command, post the
 * answer, ask again. Nothing listens on this machine. Each command is handled
 * on its own thread, so a long build does not stop the next request being
 * heard; asking the person is one at a time, behind [Approver].
 */
class DeviceLoop(
    private val client: OrknuxClient,
    private val handler: CommandHandler,
    private val report: DeviceReport,
    private val waitSeconds: Int = 25,
    private val retryMillis: Long = 5_000,
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    @Volatile
    var connected = false
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread.ofPlatform().name("orknux-shell-device").daemon().start { loop() }
    }

    fun stop() {
        running.set(false)
        thread?.interrupt()
    }

    private fun loop() {
        var saidMissing = false
        var saidUnreachable = false
        // Since when the server has not answered: a restart takes a few seconds, which is not news.
        var downSince = 0L
        while (running.get()) {
            // A poll on its way and not failed is a door that is open: one with nothing to do waits half a minute
            // for an answer, and the Shell would say the door was down for all of that.
            if (downSince == 0L) connected = true
            val reply = try {
                client.get(
                    "$PATH/next?wait=$waitSeconds&client=$CLIENT&version=${OrknuxClient.encode(Version.VERSION)}&instance=$INSTANCE",
                    Duration.ofSeconds(waitSeconds + 20L),
                )
            } catch (failure: OrknuxException) {
                connected = false
                if (!running.get()) return
                if (downSince == 0L) downSince = System.currentTimeMillis()
                if (!saidUnreachable && System.currentTimeMillis() - downSince >= QUIET_OUTAGE_MILLIS) {
                    report.error("Local commands: ${failure.message}. Trying again.")
                    saidUnreachable = true
                }
                pause(retryMillis)
                continue
            }
            if (saidUnreachable) report.notice("Local commands: the server answers again.")
            saidUnreachable = false
            downSince = 0L
            when {
                reply.status == 204 -> connected = true
                reply.status == 200 -> {
                    connected = true
                    val command = runCatching { reply.json() }.getOrNull()
                    if (command != null) Thread.ofVirtual().name("orknux-shell-command").start { handle(command) }
                }
                reply.status == 404 -> {
                    connected = false
                    if (!saidMissing) report.notice("This server has no terminal door (/api/terminal), so its agents cannot run commands here.")
                    saidMissing = true
                    pause(60_000)
                }
                reply.status == 401 || reply.status == 403 -> {
                    connected = false
                    report.error("Local commands stopped: the server says this sign-in is not good ($PATH ${reply.status}).")
                    pause(60_000)
                }
                else -> {
                    connected = false
                    pause(retryMillis)
                }
            }
        }
    }

    private fun handle(command: JsonNode) {
        val id = command.str("id") ?: return
        val answer = try {
            when (val action = command.str("action")) {
                "run" -> handler.run(command.get("arguments") ?: Json.obj())
                else -> Answer.Failed("Orknux Shell does not know the action \"$action\".")
            }
        } catch (failure: Exception) {
            Answer.Failed(failure.message ?: failure.javaClass.simpleName)
        }
        val body = Json.obj()
        when (answer) {
            is Answer.Done -> body.set<JsonNode>("result", answer.result)
            is Answer.Failed -> body.put("error", answer.error)
        }
        val posted = runCatching { client.post("$PATH/answers/${OrknuxClient.encode(id)}", body) }
        posted.onFailure { report.error("Could not send the command's answer: ${it.message}") }
        posted.getOrNull()?.let { if (it.status == 410) report.notice("The agent had stopped waiting for that answer.") }
    }

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val PATH = "/api/terminal"
        const val CLIENT = "Shell"
        /** How long the server may not answer before it is said: a restart is a few seconds. */
        const val QUIET_OUTAGE_MILLIS = 20_000L
    }
}

sealed interface Answer {
    data class Done(val result: JsonNode) : Answer
    data class Failed(val error: String) : Answer
}

/**
 * The `run` action: the rules, the person where the rules say to ask, and the
 * command itself.
 */
class CommandHandler(
    @Volatile var policy: CommandPolicy,
    private val runner: CommandRunner,
    private val approver: Approver,
    private val report: DeviceReport,
    private val mode: () -> String,
    /** Keeps an allow rule the person chose; returns where it was written. */
    private val keepRule: (String) -> String,
) {
    private val asking = Any()

    fun run(arguments: JsonNode): Answer {
        val request = RunRequest(
            command = arguments.str("command")?.takeIf { it.isNotBlank() } ?: return Answer.Failed("No command was given."),
            cwd = arguments.str("cwd"),
            timeoutSeconds = arguments.long("timeoutSeconds")?.toInt(),
            dangerous = arguments.bool("dangerous") ?: false,
            reason = arguments.str("reason"),
        )
        val decision = policy.decide(request.command, request.dangerous, mode())
        val approval = when (decision.verdict) {
            Verdict.DENY -> {
                report.refused(request, DECLINED_BY_RULE)
                return Answer.Failed(DECLINED_BY_RULE)
            }
            Verdict.RUN -> "auto"
            Verdict.ASK -> {
                // One question at a time: two commands arriving together are asked about in turn.
                val said = synchronized(asking) { approver.ask(request, decision) }
                when (said) {
                    Approval.Declined -> {
                        report.refused(request, DECLINED)
                        return Answer.Failed(DECLINED)
                    }
                    is Approval.Pattern -> {
                        val where = runCatching { keepRule(said.pattern) }
                        where.onSuccess { report.notice("Allow rule ${said.pattern} kept in $it.") }
                        where.onFailure { report.error("Could not keep the allow rule: ${it.message}") }
                    }
                    Approval.Once -> {}
                }
                "person"
            }
        }
        report.running(request, approval)
        val result = try {
            runner.run(request)
        } catch (failure: Exception) {
            report.error("Could not run it: ${failure.message}")
            return Answer.Failed("It could not be started: ${failure.message ?: failure.javaClass.simpleName}")
        }
        report.ran(request, result, approval)
        val json = Json.obj()
            .put("exitCode", result.exitCode)
            .put("stdout", result.stdout)
            .put("stderr", result.stderr)
            .put("truncated", result.truncated)
            .put("seconds", result.seconds)
            .put("approval", approval)
        if (result.timedOut) json.put("timedOut", true)
        return Answer.Done(json)
    }

    companion object {
        const val DECLINED = "The person declined to run it."
        const val DECLINED_BY_RULE = "Your rules forbid this command."
    }
}
