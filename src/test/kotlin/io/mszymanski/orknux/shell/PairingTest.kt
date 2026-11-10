package io.mszymanski.orknux.shell

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pairing links: read, traded at the server, and kept - or, where the server keeps paired clients to their own doors, said and signed in the other way. */
class PairingTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a link carries its server, under a path prefix too, and a code is any case with its dash or without`() {
        assertEquals("https://o.example.com" to "K7P-4QX", Pairing.parse("https://o.example.com/pair/K7P-4QX"))
        assertEquals("https://o.example.com/orknux" to "k7p4qx", Pairing.parse("  https://o.example.com/orknux/pair/k7p4qx/ "))
        assertEquals("http://localhost:8080" to "ABC-DEF", Pairing.parse("http://localhost:8080/pair/ABC-DEF"))
        assertNull(Pairing.parse("https://o.example.com"))
        assertNull(Pairing.parse("https://o.example.com/pair/K0P-4QX"), "0 is not in the alphabet")
        assertNull(Pairing.parse("/pair/K7P-4QX"))
        assertTrue(Pairing.isCode("k7p-4qx"))
        assertTrue(Pairing.isCode("K7P4QX"))
        assertTrue(!Pairing.isCode("orkx_abc"))
    }

    private class Server(private val doorsOnly: Boolean) : AutoCloseable {
        val http: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pairs = CopyOnWriteArrayList<JsonNodeText>()

        data class JsonNodeText(val text: String)

        init {
            http.createContext("/") { x ->
                val body = String(x.requestBody.readAllBytes())
                val bearer = x.requestHeaders.getFirst("Authorization")
                val (status, answer) = when (x.requestURI.path) {
                    "/api/client/compatibility" -> 200 to """{"product":"orknux","compatible":true}"""
                    "/api/clients/pair" -> {
                        pairs += JsonNodeText(body)
                        if (Json.parse(body).str("code") == "K7P-4QX") 200 to """{"credential":"orkxc_secret","user":"alice"}"""
                        else 400 to """{"error":"That pairing code is not valid, or it has run out.","code":"ClientPairingCodeInvalid"}"""
                    }
                    "/api/session" -> when {
                        x.requestMethod == "POST" -> {
                            x.responseHeaders.add("Set-Cookie", "SESSION=s3cr3t; Path=/")
                            200 to """{"username":"alice"}"""
                        }
                        bearer == "Bearer orkxc_secret" && doorsOnly ->
                            403 to """{"error":"This is a client credential: it opens the doors of the Orknux clients and nothing else.","code":"ClientCredentialElsewhere"}"""
                        bearer == "Bearer orkxc_secret" -> 200 to """{"username":"alice"}"""
                        else -> 401 to "{}"
                    }
                    "/api/auth/method" -> 200 to """{"method":"INTERNAL"}"""
                    else -> 404 to "{}"
                }
                x.responseHeaders.add("Content-Type", "application/json")
                val bytes = answer.toByteArray()
                x.sendResponseHeaders(status, bytes.size.toLong())
                x.responseBody.use { it.write(bytes) }
            }
            http.start()
        }

        val url get() = "http://127.0.0.1:${http.address.port}"
        override fun close() = http.stop(0)
    }

    private class Answers(vararg answers: String) : Prompter {
        private val queue = ArrayDeque(answers.toList())
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>()
        override fun ask(prompt: String, default: String?): String? = queue.removeFirstOrNull().also { asked += prompt }
        override fun secret(prompt: String): String? = queue.removeFirstOrNull().also { asked += prompt }
        override fun say(text: String) { said += text }
    }

    @Test
    fun `a link pasted where the server is asked for connects with the Shell's own credential, nothing typed`() {
        Server(doorsOnly = false).use { server ->
            val store = ConfigStore(dir.resolve("c.json"))
            val prompter = Answers("${server.url}/pair/K7P-4QX")
            val saved = Login(store, prompter).login(null, null, null, null)
            assertNotNull(saved)
            assertEquals(server.url, saved.server)
            assertEquals("orkxc_secret", saved.token)
            assertEquals("alice", saved.user)
            assertEquals(listOf("Orknux server, or a pairing link from Orknux (Preferences › Connected clients)"), prompter.asked)
            val traded = Json.parse(server.pairs.single().text)
            assertEquals("K7P-4QX", traded.str("code"))
            assertEquals("Shell", traded.str("client"))
            assertTrue(traded.str("name")!!.startsWith("Orknux Shell"))
            assertEquals("orkxc_secret", store.load().token)
        }
    }

    @Test
    fun `a link given on the command line, and a bare code for the server already known`() {
        Server(doorsOnly = false).use { server ->
            val store = ConfigStore(dir.resolve("c.json"))
            assertNotNull(Login(store, Answers()).login("${server.url}/pair/K7P-4QX", null, null, null))
            store.save(store.load().copy(token = null))
            val again = Login(store, Answers("k7p-4qx".uppercase())).login(null, null, null, null)
            assertEquals("orkxc_secret", again?.token)
        }
    }

    @Test
    fun `a server that keeps paired clients to their own doors is said so, and the link's server is signed in to instead`() {
        Server(doorsOnly = true).use { server ->
            val store = ConfigStore(dir.resolve("c.json"))
            val prompter = Answers("alice", "wonderland")
            val saved = Login(store, prompter).login("${server.url}/pair/K7P-4QX", null, null, null)
            assertNotNull(saved)
            assertNull(saved.token)
            assertEquals(mapOf("SESSION" to "s3cr3t"), saved.cookies)
            assertEquals(server.url, saved.server)
            assertTrue(prompter.said.any { it.contains("lets a paired client through the clients' own doors only") }, prompter.said.toString())
            assertTrue(prompter.said.any { it == "Sign in to ${server.url} instead." })
        }
    }

    @Test
    fun `a code that has run out is said in the server's words, with where to make another`() {
        Server(doorsOnly = false).use { server ->
            val store = ConfigStore(dir.resolve("c.json"))
            val prompter = Answers("alice", "wonderland")
            val saved = Login(store, prompter).login("${server.url}/pair/ZZZ-ZZZ", null, null, null)
            assertNotNull(saved)
            assertTrue(prompter.said.first().startsWith("Not paired: That pairing code is not valid, or it has run out. Make a new link in Orknux"), prompter.said.toString())
        }
    }
}
