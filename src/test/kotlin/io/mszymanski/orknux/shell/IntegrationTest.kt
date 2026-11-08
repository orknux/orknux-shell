package io.mszymanski.orknux.shell

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Signs in to a stub Orknux and has one conversation in print mode: the agent
 * thinks, writes a tool call, has this computer run `echo hi` - which runs at
 * once - and `sudo touch ...`, which is asked about, declined, and never runs.
 *
 * In-process by default. With `-Dorknux.shell.binary=/path/to/orknux-shell` the
 * same conversation is held by that binary - the native image - instead.
 */
class IntegrationTest {
    private val binary: String? = System.getProperty("orknux.shell.binary")?.takeIf { it.isNotBlank() }

    @Test
    fun `sign in, chat, run a command here, and refuse sudo without approval`(@TempDir dir: Path) {
        assumeTrue(!Platform.isWindows())
        val marker = dir.resolve("sudo-ran")
        val commands = listOf(
            Json.obj().put("id", "c1").put("action", "run").set<com.fasterxml.jackson.databind.JsonNode>(
                "arguments", Json.obj().put("command", "echo hi").put("dangerous", false).put("reason", "Say hi"),
            ),
            Json.obj().put("id", "c2").put("action", "run").set(
                "arguments", Json.obj().put("command", "sudo touch $marker").put("dangerous", false).put("reason", "Needs root"),
            ),
        )
        StubOrknux(commands).use { stub ->
            val config = dir.resolve("orknux-shell.json")

            val login = run(config, listOf("login", stub.url, "--username", "alice", "--password-stdin"), "wonderland\n")
            assertEquals(0, login.exit, login.toString())
            assertTrue(login.out.contains("Signed in to ${stub.url} as alice"), login.toString())
            val saved = ConfigStore(config).load()
            assertEquals(mapOf("SESSION" to "s3cr3t"), saved.cookies)
            assertTrue(!Files.readString(config).contains("wonderland"), "the password is never written")

            // "n": the sudo command is asked about on standard input, and declined.
            val chat = run(config, listOf("-p", "say hi"), "n\n")
            assertEquals(0, chat.exit, chat.toString())
            assertTrue(chat.out.contains("All done: the command said hi."), chat.toString())
            assertTrue(chat.out.contains("✓ terminal_run echo hi"), chat.toString())
            assertTrue(chat.err.contains("$ echo hi  ⎿ exit 0"), chat.toString())
            assertTrue(chat.err.contains("Run a command on this computer?"), chat.toString())
            assertTrue(chat.err.contains("It runs with elevated rights."), chat.toString())
            assertTrue(chat.err.contains("The person declined to run it."), chat.toString())
            assertTrue(chat.err.contains("Home · Builder · auto · ↑1.2k ↓120 (600 cached) · $0.0042"), chat.toString())

            val hi = stub.answers.getValue("c1").get("result")
            assertEquals(0, hi.get("exitCode").intValue())
            assertEquals("hi\n", hi.str("stdout"))
            assertEquals("", hi.str("stderr"))
            assertEquals(false, hi.bool("truncated"))
            assertEquals("auto", hi.str("approval"))
            assertEquals("The person declined to run it.", stub.answers.getValue("c2").str("error"))
            assertTrue(!Files.exists(marker), "sudo ran without approval")

            assertEquals("SHELL", stub.startChatInputs.single().str("openedIn"))
            assertEquals("1", stub.startChatInputs.single().str("workspaceId"))
            assertTrue(stub.nextQueries.all { it.contains("client=Shell") && it.contains("wait=25") }, stub.nextQueries.toString())
            assertEquals(emptyList(), stub.missingOrigin.toList(), "every POST carries Origin")
        }
    }

    @Test
    fun `a wrong password is refused and nothing is kept`(@TempDir dir: Path) {
        StubOrknux(emptyList()).use { stub ->
            val config = dir.resolve("orknux-shell.json")
            val login = run(config, listOf("login", stub.url, "--username", "alice", "--password-stdin"), "nope\n")
            assertEquals(1, login.exit)
            assertTrue(login.out.contains("Invalid username or password."), login.toString())
            assertTrue(!ConfigStore(config).load().signedIn)
        }
    }

    data class Ran(val exit: Int, val out: String, val err: String)

    private fun run(config: Path, args: List<String>, stdin: String): Ran {
        val all = args + listOf("--config", config.toString())
        if (binary != null) {
            val process = ProcessBuilder(listOf(binary) + all).start()
            process.outputStream.use { it.write(stdin.toByteArray()) }
            var err = ""
            val errReader = Thread.ofPlatform().start { err = process.errorStream.readAllBytes().toString(StandardCharsets.UTF_8) }
            val out = process.inputStream.readAllBytes().toString(StandardCharsets.UTF_8)
            errReader.join()
            check(process.waitFor(60, TimeUnit.SECONDS))
            return Ran(process.exitValue(), out, err)
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val options = Cli.parse(all.toTypedArray())
        val store = ConfigStore(config)
        val input = BufferedReader(StringReader(stdin))
        val exit = PrintStream(out, true, StandardCharsets.UTF_8).use { o ->
            PrintStream(err, true, StandardCharsets.UTF_8).use { e ->
                when (options.command) {
                    "login" -> {
                        val password = if (options.passwordStdin) input.readLine() else null
                        if (Login(store, PlainPrompter(input, o)).login(options.positional, options.token, options.username, password) != null) 0 else 1
                    }
                    else -> {
                        val loaded = store.load()
                        PrintMode(store, loaded, Api(Connection.clientFor(store, loaded)), input, o, e, Theme(depth = ColourDepth.NONE))
                            .run(options.print!!, options.workspace, options.agent, options.mode)
                    }
                }
            }
        }
        return Ran(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8))
    }
}
