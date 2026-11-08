package io.mszymanski.orknux.shell

import java.io.BufferedReader
import java.io.PrintStream

/**
 * `orknux -p "message"`: one message, the answer on standard output,
 * and out. For scripts, and for testing the binary without a terminal.
 *
 * The device door is open while the answer is written, so the agent can run
 * commands here as it would in the chat; a command that needs asking is put
 * on standard error and answered with a line on standard input - `a` or `y`
 * runs it, `p` runs it and keeps the proposed allow rule, anything else (or
 * no input at all) declines.
 */
class PrintMode(
    private val store: ConfigStore,
    private val config: Config,
    private val api: Api,
    private val input: BufferedReader,
    private val out: PrintStream,
    private val err: PrintStream,
    private val theme: Theme,
) {
    fun run(message: String, workspaceName: String?, agentName: String?, mode: String?): Int {
        val workspace = Connection.workspace(api, config, workspaceName) { all ->
            throw OrknuxException("Several workspaces; name one with --workspace: ${all.joinToString(", ") { it.name }}")
        } ?: return 1
        val agents = api.agents(workspace.id).filter { it.enabled }
        val agent = Connection.agent(agents, config, agentName)
        val conversation = Conversation(api, workspace, agent, mode ?: config.mode)
        val policy = CommandPolicy(config.commands)
        policy.problems.forEach { err.println(theme.warning("Rule skipped: $it")) }
        val report = object : DeviceReport {
            override fun ran(request: RunRequest, result: RunResult, approval: String) =
                err.println(theme.muted("$ ${request.command}  ⎿ exit ${result.exitCode} · ${result.seconds}s" + if (approval == "person") " · approved" else ""))
            override fun refused(request: RunRequest, why: String) = err.println(theme.muted("$ ${request.command}  ⎿ ") + theme.danger(why))
            override fun notice(text: String) = err.println(theme.muted(text))
            override fun error(text: String) = err.println(theme.warning(text))
        }
        val approver = Approver { request, decision ->
            synchronized(this) {
                ApprovalBox.lines(theme, request, decision, CommandRunner(config.shell).resolveDirectory(request.cwd).toString(), 90).forEach(err::println)
                err.print("Approve (a), approve for pattern (p) or decline (d)? ")
                err.flush()
                when (input.readLine()?.trim()?.lowercase()) {
                    "a", "y", "yes" -> Approval.Once
                    "p" -> Approval.Pattern(CommandPolicy.proposePattern(request.command))
                    else -> Approval.Declined
                }
            }
        }
        lateinit var handler: CommandHandler
        handler = CommandHandler(policy, CommandRunner(config.shell), approver, report, { conversation.mode }) { pattern ->
            val saved = store.update { it.copy(commands = it.commands.copy(allow = it.commands.allow + pattern)) }
            handler.policy = CommandPolicy(saved.commands)
            store.path.toString()
        }
        val device = DeviceLoop(api.client, handler, report)
        device.start()
        var failed = false
        val output = object : TurnOutput {
            override fun line(text: String) = out.println(text)
            override fun phase(key: String, text: String) {}
        }
        val renderer = TurnRenderer(theme, output, if (config.thinking == "expanded") "expanded" else "hidden", Links(Links.osc8For(config.links, theme.depth), api.client.server))
        try {
            conversation.send(message) { event ->
                if (event is ChatEvent.Error) failed = true
                renderer.handle(event)
            }
            renderer.flush()
            renderer.linkList().forEach(out::println)
        } catch (refused: OrknuxException) {
            err.println(theme.danger(refused.message ?: "The server could not answer."))
            return 1
        } finally {
            device.stop()
            out.flush()
        }
        val u = conversation.usage
        err.println(
            theme.muted(
                "${workspace.name} · ${conversation.agent?.name ?: conversation.chat?.agentName ?: "agent"} · ${conversation.mode} · " +
                    "↑${TurnRenderer.tokens(u.inputTokens)} ↓${TurnRenderer.tokens(u.outputTokens)} (${TurnRenderer.tokens(u.cachedInputTokens)} cached) · ${TurnRenderer.cost(u.cost)}",
            ),
        )
        return if (failed || !renderer.answered) 1 else 0
    }
}
