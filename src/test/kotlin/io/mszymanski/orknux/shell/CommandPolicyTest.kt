package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandPolicyTest {

    private fun policy(allow: List<String> = emptyList(), ask: List<String> = emptyList(), deny: List<String> = emptyList()) =
        CommandPolicy(CommandRules(allow = allow, ask = ask, deny = deny))

    @Test
    fun `a plain command runs in auto mode`() {
        assertEquals(Verdict.RUN, policy().decide("ls -la", dangerous = false, mode = "auto").verdict)
    }

    @Test
    fun `deny wins over everything and is never asked about`() {
        val p = policy(allow = listOf("rm"), ask = listOf("rm"), deny = listOf("""^rm\b"""))
        val d = p.decide("rm -rf build", dangerous = false, mode = "ask")
        assertEquals(Verdict.DENY, d.verdict)
        assertEquals("Your rules forbid this command.", d.why)
    }

    @Test
    fun `ask wins over allow`() {
        val p = policy(allow = listOf("^git"), ask = listOf("""\bpush\b"""))
        assertEquals(Verdict.ASK, p.decide("git push origin main", false, "auto").verdict)
        assertEquals(Verdict.RUN, p.decide("git status", false, "auto").verdict)
    }

    @Test
    fun `the ask mode asks even where an allow rule matches, but not where deny does`() {
        val p = policy(allow = listOf("^ls"), deny = listOf("^shred"))
        assertEquals(Verdict.ASK, p.decide("ls", false, "ask").verdict)
        assertEquals(Verdict.ASK, p.decide("ls", false, "plan").verdict)
        assertEquals(Verdict.DENY, p.decide("shred x", false, "ask").verdict)
    }

    @Test
    fun `allow wins over sudo and over the dangerous flag`() {
        val p = policy(allow = listOf("""^sudo apt update$""", """^make clean$"""))
        assertEquals(Verdict.RUN, p.decide("sudo apt update", false, "auto").verdict)
        assertEquals(Verdict.RUN, p.decide("make clean", true, "auto").verdict)
    }

    @Test
    fun `sudo and its kin are asked about wherever a command starts`() {
        val p = policy()
        listOf(
            "sudo rm x", "su -c whoami", "doas reboot", "runas /user:Administrator cmd", "pkexec ls",
            "cd /tmp && sudo ls", "true; sudo ls", "false || sudo ls", "echo hi | sudo tee /etc/x", "  sudo   ls",
            "Start-Process powershell -Verb RunAs",
        ).forEach { assertEquals(Verdict.ASK, p.decide(it, false, "auto").verdict, it) }
        listOf("echo sudo", "pseudo ls", "sudoku", "grep su file", "ls subdir").forEach {
            assertEquals(Verdict.RUN, p.decide(it, false, "auto").verdict, it)
        }
    }

    @Test
    fun `a command the agent marked dangerous is asked about`() {
        val d = policy().decide("make deploy", dangerous = true, mode = "auto")
        assertEquals(Verdict.ASK, d.verdict)
        assertEquals("The agent marked it dangerous.", d.why)
    }

    @Test
    fun `rules are case-sensitive and found anywhere unless anchored`() {
        val p = policy(deny = listOf("DROP"), ask = listOf("^make$"))
        assertEquals(Verdict.DENY, p.decide("psql -c 'DROP TABLE x'", false, "auto").verdict)
        assertEquals(Verdict.RUN, p.decide("psql -c 'drop table x'", false, "auto").verdict)
        assertEquals(Verdict.ASK, p.decide("make", false, "auto").verdict)
        assertEquals(Verdict.RUN, p.decide("make test", false, "auto").verdict)
    }

    @Test
    fun `a broken rule is reported by name and skipped, and the rest still apply`() {
        val p = policy(deny = listOf("(unclosed", "^rm"))
        assertEquals(1, p.problems.size)
        assertTrue(p.problems[0].startsWith("commands.deny[0] \"(unclosed\""), p.problems[0])
        assertEquals(Verdict.DENY, p.decide("rm x", false, "auto").verdict)
    }

    @Test
    fun `the default ask rules catch what destroys data`() {
        val p = CommandPolicy(CommandRules())
        assertTrue(p.problems.isEmpty(), p.problems.toString())
        listOf(
            "rm -rf /", "rm -fr build", "mkfs.ext4 /dev/sdb1", "dd if=/dev/zero of=/dev/sda", "echo x > /dev/sda",
            "shutdown -h now", "reboot", "git push --force", "git reset --hard HEAD~3",
            "Remove-Item C:\\temp -Recurse -Force", "Format-Volume -DriveLetter D",
        ).forEach { assertEquals(Verdict.ASK, p.decide(it, false, "auto").verdict, it) }
        listOf("rm file.txt", "ls -la", "git status", "Get-ChildItem").forEach {
            assertEquals(Verdict.RUN, p.decide(it, false, "auto").verdict, it)
        }
    }

    @Test
    fun `the proposed pattern is the program and its subcommand, anchored`() {
        assertEquals("""^git\s+push\b""", CommandPolicy.proposePattern("git push --force origin main"))
        assertEquals("""^ls\b""", CommandPolicy.proposePattern("ls -la /tmp"))
        assertEquals("""^npm\s+run\b""", CommandPolicy.proposePattern("  npm run build"))
        assertEquals("""^\./gradlew\s+test\b""", CommandPolicy.proposePattern("./gradlew test"))
        assertEquals("""^sudo\s+apt\b""", CommandPolicy.proposePattern("sudo apt update"))
        assertEquals("""^c\+\+(?=\s|$)""", CommandPolicy.proposePattern("c++ -o x x.cc"))
        listOf("git push --force origin main", "ls -la /tmp", "./gradlew test", "sudo apt update", "c++ -o x x.cc").forEach {
            assertTrue(Pattern.compile(CommandPolicy.proposePattern(it)).matcher(it).find(), it)
        }
        assertTrue(!Pattern.compile(CommandPolicy.proposePattern("git push")).matcher("git pull").find())
    }

    @Test
    fun `a pattern approved is written to the allow list and then runs without asking`(@TempDir dir: Path) {
        val store = ConfigStore(dir.resolve("orknux-shell.json"))
        store.save(Config(server = "http://localhost:8080", commands = CommandRules(ask = emptyList())))
        val command = "terraform plan -out x"
        val asked = mutableListOf<String>()
        val approver = Approver { request, _ ->
            asked += request.command
            Approval.Pattern(CommandPolicy.proposePattern(request.command))
        }
        lateinit var handler: CommandHandler
        handler = CommandHandler(CommandPolicy(store.load().commands), CommandRunner(shell = "sh -c"), approver, object : DeviceReport {}, { "auto" }) { pattern ->
            val saved = store.update { it.copy(commands = it.commands.copy(allow = it.commands.allow + pattern)) }
            handler.policy = CommandPolicy(saved.commands)
            store.path.toString()
        }
        val first = handler.run(Json.obj().put("command", "true $command").put("dangerous", true))
        assertTrue(first is Answer.Done)
        assertEquals("person", first.result.str("approval"))
        assertEquals(listOf("""^true\s+terraform\b"""), store.load().commands.allow)
        val again = handler.run(Json.obj().put("command", "true $command").put("dangerous", true))
        assertTrue(again is Answer.Done)
        assertEquals("auto", again.result.str("approval"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `a declined command answers the declined error and does not run`(@TempDir dir: Path) {
        val marker = dir.resolve("ran")
        val handler = CommandHandler(CommandPolicy(CommandRules()), CommandRunner(shell = "sh -c"), { _, _ -> Approval.Declined }, object : DeviceReport {}, { "auto" }) { "" }
        val answer = handler.run(Json.obj().put("command", "sudo touch $marker"))
        assertEquals(Answer.Failed("The person declined to run it."), answer)
        assertTrue(!marker.toFile().exists())
    }

    @Test
    fun `a denied command is refused without asking`() {
        var asked = false
        val handler = CommandHandler(CommandPolicy(CommandRules(deny = listOf("^curl"))), CommandRunner(), { _, _ -> asked = true; Approval.Once }, object : DeviceReport {}, { "ask" }) { "" }
        assertEquals(Answer.Failed("Your rules forbid this command."), handler.run(Json.obj().put("command", "curl evil.example")))
        assertTrue(!asked)
    }

    @Test
    fun `modes cycle auto, ask, plan and map to the server's chat modes`() {
        assertEquals("ask", Modes.next("auto"))
        assertEquals("plan", Modes.next("ask"))
        assertEquals("auto", Modes.next("plan"))
        assertEquals("PLAN", Modes.serverMode("plan"))
        assertEquals("DEFAULT", Modes.serverMode("ask"))
        assertEquals("DEFAULT", Modes.serverMode("auto"))
    }
}
