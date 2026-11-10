package io.mszymanski.orknux.shell

/**
 * A pairing link from Orknux - Preferences › Connected clients › Connect a
 * client - traded once for this Shell's own credential, as the Chrome
 * extension, Orknux for Windows, Orknux for Linux and the Claude Code plugin
 * trade theirs.
 *
 * The shapes are theirs: a code like K7P-4QX, in any case and with its dash or
 * without, and a link `<server>/pair/<code>` whose every part before the last
 * `/pair/` is the server, so one under a path prefix works. No client
 * registers a URL scheme for it - the link is an https address, which a
 * browser opens on the server's own page saying where to paste it - so neither
 * does this one: it is pasted where the Shell asks for a server, or given as
 * `orknux <link>` or `orknux login <link>`.
 */
object Pairing {
    /** The server's alphabet: no 0, O, 1, I or L, since a code is read off a screen. */
    private const val CODE = "[A-HJ-NP-Za-hj-np-z2-9]{3}-?[A-HJ-NP-Za-hj-np-z2-9]{3}"
    private val CODE_SHAPE = Regex("^$CODE$")
    private val LINK_SHAPE = Regex("^(https?://.+)/pair/($CODE)/?$", RegexOption.IGNORE_CASE)

    /** What the server lists the credential as, under Connected clients. */
    const val CLIENT = "Shell"

    /** The refusal a client credential gets on a door that is not a client's - `InternalAuthentication` on the server. */
    const val ELSEWHERE = "ClientCredentialElsewhere"

    fun isCode(text: String): Boolean = CODE_SHAPE.matches(text.trim())

    /** The server and the code of a pairing link, or null where [text] is not one. */
    fun parse(text: String): Pair<String, String>? {
        val match = LINK_SHAPE.matchEntire(text.trim()) ?: return null
        val server = match.groupValues[1].trimEnd('/')
        if (!Regex("^https?://[^/\\s]+", RegexOption.IGNORE_CASE).containsMatchIn(server)) return null
        return server to match.groupValues[2]
    }

    /** "Orknux Shell on michjak-pc", for the list of connected clients. */
    fun name(): String {
        val host = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.substringBefore('.')?.takeIf { it.isNotBlank() }
            ?: System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
        return if (host.isNullOrBlank()) "Orknux Shell" else "Orknux Shell on $host"
    }

    /** Trades [code] at [client]'s server for a credential (`orkxc_...`), or says in the server's words why not. */
    fun trade(client: OrknuxClient, code: String, name: String = name()): String {
        val reply = client.post("/api/clients/pair", Json.obj().put("code", code.trim()).put("client", CLIENT).put("name", name))
        if (reply.status == 404) throw OrknuxException("${client.server} does not pair clients - is it an Orknux server, and a recent one?", 404)
        val said = runCatching { reply.json() }.getOrNull()
        if (!reply.ok) throw OrknuxException(said.str("error") ?: OrknuxClient.refusalOf(reply.status, reply.body), reply.status, said.str("code"))
        return said.str("credential") ?: throw OrknuxException("${client.server} did not hand back a credential.")
    }

    /**
     * Whether a credential is enough for the Shell: a session read with it is.
     * A server that keeps paired clients to their own doors refuses it here
     * with [ELSEWHERE], and the Shell's chat - GraphQL, a chat's stream - is not
     * one of those doors.
     */
    sealed interface Reach {
        data class Enough(val username: String?) : Reach
        data object ClientDoorsOnly : Reach
        data class Refused(val reason: String) : Reach
    }

    fun reach(server: String, credential: String): Reach {
        val reply = OrknuxClient(server, token = credential).get("/api/session")
        if (reply.ok) return Reach.Enough(runCatching { reply.json().str("username") }.getOrNull())
        val said = runCatching { reply.json() }.getOrNull()
        if (said.str("code") == ELSEWHERE) return Reach.ClientDoorsOnly
        return Reach.Refused(said.str("error") ?: OrknuxClient.refusalOf(reply.status, reply.body))
    }
}
