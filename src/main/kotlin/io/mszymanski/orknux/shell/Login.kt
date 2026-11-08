package io.mszymanski.orknux.shell

import java.io.BufferedReader
import java.io.PrintStream

/** Asks the person things: in the terminal's line editor where there is one, on standard input where there is not. */
interface Prompter {
    fun ask(prompt: String, default: String? = null): String?
    /** Never echoed. */
    fun secret(prompt: String): String?
    fun say(text: String)
}

class PlainPrompter(private val input: BufferedReader, private val out: PrintStream) : Prompter {
    override fun ask(prompt: String, default: String?): String? {
        out.print(prompt + (default?.let { " [$it]" } ?: "") + ": ")
        out.flush()
        val line = input.readLine() ?: return null
        return line.trim().ifEmpty { default ?: "" }
    }

    override fun secret(prompt: String): String? {
        System.console()?.let { console ->
            return console.readPassword("%s: ", prompt)?.let { String(it) }
        }
        out.print("$prompt: ")
        out.flush()
        return input.readLine()
    }

    override fun say(text: String) = out.println(text)
}

/**
 * Signing in and out, and keeping the result.
 *
 * A password goes to `POST /api/session` once and is never written anywhere;
 * what is kept is the session cookie the server sets, which lasts as long as
 * the server's sessions do (fourteen days unless an administrator says
 * otherwise). An access token is kept as given.
 */
class Login(private val store: ConfigStore, private val prompter: Prompter) {

    /** Asked until there is an answer: an empty one is a slip, not a wish to leave. Ctrl+C or Ctrl+D leaves. */
    private fun askServer(before: String?): String? {
        while (true) {
            val said = prompter.ask("Orknux server (for example https://orknux.example.com)", before)?.let(::pasted)
            if (said == null) {
                prompter.say("Not signed in.")
                return null
            }
            if (said.isNotBlank()) return said
            prompter.say("Type or paste the address of your Orknux server, or press Ctrl+C to leave.")
        }
    }

    fun login(serverArgument: String?, token: String?, username: String?, password: String?): Config? {
        val before = store.load()
        val server = serverArgument?.takeIf { it.isNotBlank() } ?: askServer(before.server) ?: return null
        val client = OrknuxClient(server, token = token)
        val api = Api(client)
        api.compatibility()?.let { (compatible, reason) ->
            if (!compatible) {
                prompter.say(reason ?: "This server does not take this version of Orknux Shell.")
                return null
            }
        }
        val sameServer = before.server?.let { OrknuxClient.normaliseServer(it) } == client.server
        val kept = if (sameServer) before else before.copy(workspaceId = null, workspace = null, agentId = null, agent = null)

        if (token != null) {
            val me = api.me() ?: run {
                prompter.say("The server did not accept that token.")
                return null
            }
            val name = me.str("username")
            val saved = kept.copy(server = client.server, token = token, cookies = emptyMap(), user = name)
            store.save(saved)
            prompter.say("Signed in to ${client.server} as $name with an access token.")
            return saved
        }

        val method = runCatching { client.get("/api/auth/method") }.getOrNull()?.takeIf { it.ok }?.json()
        when (method.str("method")) {
            "OIDC" -> {
                prompter.say(
                    "${client.server} signs in through ${method.str("displayName") ?: "single sign-on"} in a browser. " +
                        "Make an access token in Orknux (your profile, Access tokens) and run: orknux login ${client.server} --token orkx_...",
                )
                return null
            }
            "NONE" -> {
                val saved = kept.copy(server = client.server, token = null, cookies = emptyMap())
                store.save(saved)
                prompter.say("${client.server} asks nobody to sign in.")
                return saved
            }
        }
        method.str("notice")?.takeIf { it.isNotBlank() }?.let { prompter.say(it) }

        var attempts = 0
        while (attempts++ < 3) {
            val user = username ?: generateSequence { prompter.ask("Username", before.user.takeIf { sameServer })?.let(::pasted) }
                .firstOrNull { it.isNotEmpty() } ?: return null
            val secret = password ?: prompter.secret("Password for $user") ?: return null
            val session = OrknuxClient(client.server)
            val body = Json.obj().put("username", user).put("password", secret)
            val reply = session.post("/api/session", body)
            when {
                reply.ok -> {
                    val name = runCatching { reply.json().str("username") }.getOrNull() ?: user
                    val saved = kept.copy(server = client.server, token = null, cookies = session.cookies, user = name)
                    if (session.cookies.isEmpty()) {
                        prompter.say("The server signed you in but set no session cookie, so there is nothing to keep.")
                        return null
                    }
                    store.save(saved)
                    prompter.say("Signed in to ${client.server} as $name.")
                    return saved
                }
                reply.status == 401 -> {
                    prompter.say("Invalid username or password.")
                    if (username != null && password != null) return null
                }
                else -> {
                    prompter.say("Sign-in failed: ${OrknuxClient.refusalOf(reply.status, reply.body)}")
                    return null
                }
            }
        }
        return null
    }

    fun logout(): Boolean {
        val config = store.load()
        if (config.server != null && config.cookies.isNotEmpty()) {
            runCatching { OrknuxClient(config.server, cookies = config.cookies).delete("/api/session") }
        }
        store.save(config.copy(token = null, cookies = emptyMap()))
        return config.signedIn
    }
}

/**
 * An answer with what a terminal's paste can leave in it taken out: the
 * bracketed-paste markers, when they arrive split - over ssh, say - and the
 * line editor reads them as text, and the escape before them.
 */
fun pasted(answer: String): String =
    answer.replace(Regex("""\u001b?\[20[01]~"""), "").replace("\u001b", "").trim()
