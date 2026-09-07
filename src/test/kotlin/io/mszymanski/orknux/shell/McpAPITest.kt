package io.mszymanski.orknux.shell

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The contract, exercised from the outside: the door, the handshake, the
 * jail, and the tools, all through the one endpoint a client sees.
 *
 * The shell-dependent tests locate a POSIX `sh` first and are skipped on a
 * machine without one (a bare Windows checkout); everything else runs
 * anywhere, because the jail and the file tools are plain JVM.
 */
@SpringBootTest(properties = ["orknux-shell.token=$TOKEN"])
class McpAPITest(
    @Autowired private val context: WebApplicationContext,
    @Autowired private val door: BearerAuth,
    @Autowired private val mapper: ObjectMapper,
) {

    private lateinit var mvc: MockMvc

    @BeforeEach
    fun mvc() {
        // Built by hand with the door in the chain, because the door is the
        // first thing under test and a MockMvc without filters walks past it.
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters<DefaultMockMvcBuilder>(door).build()
    }

    @Test
    fun `a request without the token is turned away at the door`() {
        val answer = mvc.perform(
            post("/mcp").contentType(MediaType.APPLICATION_JSON).content(rpc("initialize")),
        ).andReturn().response
        assertThat(answer.status).isEqualTo(401)
    }

    @Test
    fun `a wrong token is turned away the same as none`() {
        val answer = mvc.perform(
            post("/mcp")
                .header("Authorization", "Bearer not-the-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rpc("initialize")),
        ).andReturn().response
        assertThat(answer.status).isEqualTo(401)
    }

    @Test
    fun `initialize opens a session with a workspace of its own`() {
        val answer = mvc.perform(authed(post("/mcp")).content(rpc("initialize"))).andReturn().response

        val session = answer.getHeader("Mcp-Session-Id")
        assertThat(session).isNotBlank()
        val body = mapper.readTree(answer.contentAsString)
        assertThat(body.path("result").path("serverInfo").path("name").asString()).isEqualTo("orknux-shell")
        assertThat(Files.isDirectory(rootOf(session!!))).isTrue()
    }

    @Test
    fun `a request naming no live session is answered 404`() {
        val answer = mvc.perform(
            authed(post("/mcp")).header("Mcp-Session-Id", "nothing-holds-this").content(rpc("tools/list")),
        ).andReturn().response
        assertThat(answer.status).isEqualTo(404)
    }

    @Test
    fun `a notification is taken without an answer`() {
        val session = open()
        val answer = mvc.perform(
            authed(post("/mcp")).header("Mcp-Session-Id", session)
                .content("""{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}"""),
        ).andReturn().response
        assertThat(answer.status).isEqualTo(202)
    }

    @Test
    fun `the advertised tools are the DC surface`() {
        val session = open()
        val listed = request(session, rpc("tools/list")).path("result").path("tools")
        // Indexed rather than iterated: a JsonNode-typed array does not hand
        // its elements to Kotlin's `map` under Jackson 3.
        val names = (0 until listed.size()).map { listed.path(it).path("name").asString().orEmpty() }.toSet()
        val wanted = setOf(
            "start_process", "read_process_output", "interact_with_process", "force_terminate",
            "list_sessions", "list_processes", "kill_process",
            "read_file", "read_multiple_files", "write_file", "edit_block",
            "create_directory", "list_directory", "move_file", "get_file_info",
            "search_files", "search_code",
        )
        assertThat(wanted - names).withFailMessage("missing tools: %s", wanted - names).isEmpty()
    }

    @Test
    fun `the file tools read, write and edit inside the workspace`() {
        val session = open()

        call(session, "write_file", mapOf("path" to "notes.txt", "content" to "hello world"))
        assertThat(text(call(session, "read_file", mapOf("path" to "notes.txt")))).isEqualTo("hello world")

        call(
            session,
            "edit_block",
            mapOf("file_path" to "notes.txt", "old_string" to "world", "new_string" to "workspace"),
        )
        assertThat(text(call(session, "read_file", mapOf("path" to "notes.txt")))).isEqualTo("hello workspace")

        val listing = text(call(session, "list_directory", mapOf("path" to ".")))
        assertThat(listing).contains("[FILE] notes.txt")

        val found = text(call(session, "search_code", mapOf("path" to ".", "pattern" to "workspace")))
        assertThat(found).contains("notes.txt:1:")
    }

    @Test
    fun `several files come back in one answer, each under its path`() {
        val session = open()
        call(session, "write_file", mapOf("path" to "one.txt", "content" to "first"))
        call(session, "write_file", mapOf("path" to "two.txt", "content" to "second"))

        val answer = text(call(session, "read_multiple_files", mapOf("paths" to listOf("one.txt", "two.txt"))))
        assertThat(answer).contains("=== one.txt ===", "first", "=== two.txt ===", "second")
    }

    @Test
    fun `metrics are behind the token and the probes are not`() {
        open()

        val unauthenticated = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus"))
            .andReturn().response
        assertThat(unauthenticated.status).isEqualTo(401)

        val probe = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/health/liveness"))
            .andReturn().response
        assertThat(probe.status).isEqualTo(200)

        val scraped = mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/prometheus")
                .header("Authorization", "Bearer $TOKEN"),
        ).andReturn().response
        assertThat(scraped.status).isEqualTo(200)
        assertThat(scraped.contentAsString).contains("orknux_shell_sessions_open")
    }

    @Test
    fun `a path outside the workspace is refused with the jail named`() {
        val session = open()
        val answer = call(session, "read_file", mapOf("path" to "../../somebody-elses-files"), expectRefusal = true)
        assertThat(text(answer)).contains("outside this session's workspace")
    }

    @Test
    fun `closing the session destroys the workspace`() {
        val session = open()
        call(session, "write_file", mapOf("path" to "keep/me.txt", "content" to "gone soon"))
        val root = rootOf(session)
        assertThat(Files.exists(root.resolve("keep/me.txt"))).isTrue()

        val closed = mvc.perform(authed(delete("/mcp")).header("Mcp-Session-Id", session)).andReturn().response
        assertThat(closed.status).isEqualTo(204)
        assertThat(Files.exists(root)).isFalse()

        val again = mvc.perform(authed(delete("/mcp")).header("Mcp-Session-Id", session)).andReturn().response
        assertThat(again.status).isEqualTo(404)
    }

    @Test
    fun `start_process blocks, returns output verbatim, and a failed command is not an error`() {
        assumeTrue(sh != null, "no POSIX sh on this machine")
        val session = open()

        val answer = call(session, "start_process", mapOf("command" to "echo out; echo err >&2; exit 3"))
        // Combined output, exit 3 notwithstanding: the contract reads a normal
        // result as the command's text, and reserves errors for a shell that
        // could not run at all.
        assertThat(text(answer).trim().lines()).containsExactly("out", "err")
    }

    @Test
    fun `the workspace persists between commands on one session`() {
        assumeTrue(sh != null, "no POSIX sh on this machine")
        val session = open()

        call(session, "start_process", mapOf("command" to "mkdir -p kept && echo ready"))
        val listing = text(call(session, "list_directory", mapOf("path" to ".")))
        assertThat(listing).contains("[DIR] kept")
    }

    @Test
    fun `a long command hands back a pid to follow`() {
        assumeTrue(sh != null, "no POSIX sh on this machine")
        val session = open()

        val started = text(
            call(session, "start_process", mapOf("command" to "sleep 2; echo done", "timeout_ms" to 200)),
        )
        assertThat(started).contains("still running")
        val pid = Regex("process (\\d+)").find(started)!!.groupValues[1].toLong()

        val followed = text(
            call(session, "read_process_output", mapOf("pid" to pid, "timeout_ms" to 10_000)),
        )
        assertThat(followed).contains("done")
    }

    // -- the plumbing under the assertions ------------------------------------

    private fun open(): String {
        val answer = mvc.perform(authed(post("/mcp")).content(rpc("initialize"))).andReturn().response
        return answer.getHeader("Mcp-Session-Id")!!
    }

    private fun call(
        session: String,
        tool: String,
        arguments: Map<String, Any?>,
        expectRefusal: Boolean = false,
    ): JsonNode {
        val params = mapper.createObjectNode()
        params.put("name", tool)
        params.set("arguments", mapper.valueToTree(arguments))
        val body = mapper.createObjectNode()
        body.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
        body.set("params", params)

        val answered = request(session, mapper.writeValueAsString(body))
        assertThat(answered.path("error").isMissingNode)
            .withFailMessage("unexpected JSON-RPC error: %s", answered)
            .isTrue()
        assertThat(answered.path("result").path("isError").asBoolean(false)).isEqualTo(expectRefusal)
        return answered
    }

    private fun request(session: String, body: String): JsonNode {
        val answer = mvc.perform(authed(post("/mcp")).header("Mcp-Session-Id", session).content(body))
            .andReturn().response
        assertThat(answer.status).isEqualTo(200)
        return mapper.readTree(answer.contentAsString)
    }

    private fun text(answered: JsonNode): String =
        answered.path("result").path("content").path(0).path("text").asString()

    private fun authed(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder) =
        builder.header("Authorization", "Bearer $TOKEN").contentType(MediaType.APPLICATION_JSON)

    private fun rpc(method: String): String =
        """{"jsonrpc":"2.0","id":1,"method":"$method","params":{}}"""

    private fun rootOf(session: String): Path = Path.of(SESSIONS_ROOT, session)

    companion object {
        /**
         * A real `sh` if this machine has one, found once for the whole class.
         * `/bin/sh` first — the deployment's answer — then whatever the PATH
         * calls `sh`, which is how a Windows checkout with Git's tools finds one.
         */
        val sh: String? = listOf("/bin/sh", "sh").firstOrNull { candidate ->
            runCatching {
                val probe = ProcessBuilder(candidate, "-c", "true").start()
                probe.waitFor(10, TimeUnit.SECONDS) && probe.exitValue() == 0
            }.getOrDefault(false)
        }

        const val SESSIONS_ROOT = "target/test-sessions"

        @JvmStatic
        @DynamicPropertySource
        fun shell(registry: DynamicPropertyRegistry) {
            registry.add("orknux-shell.shell") { sh ?: "/bin/sh" }
            registry.add("orknux-shell.sessions-root") { SESSIONS_ROOT }
        }
    }
}

private const val TOKEN = "orkx_test_token"
