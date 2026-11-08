package io.mszymanski.orknux.shell

/**
 * The Shell's own shortcuts, each a Ctrl+letter, and what `/config` may set
 * them to. A terminal sends Ctrl+letter as one byte everywhere, which is why
 * nothing else is offered: Alt+Enter, the first choice for sending now, is
 * Windows Terminal's full screen, and keys a terminal keeps for itself never
 * arrive at all.
 */
object Keys {
    data class Action(val name: String, val default: String, val what: String)

    val ACTIONS: List<Action> = listOf(
        Action("details", "ctrl+o", "expand thoughts and tool calls, and fold them again"),
        Action("sessions", "ctrl+t", "this chat's sessions - its own and its agents'"),
        Action("send-now", "ctrl+s", "while the agent works, send what you typed into the turn now"),
    )

    /**
     * Letters a shortcut may not take: the ones that are already something -
     * Ctrl+C interrupts, Ctrl+D quits, Ctrl+H, I, J, M and [ are Backspace,
     * Tab, Enter and Esc, Ctrl+L clears, Ctrl+U clears the line, Ctrl+Z
     * suspends - and Ctrl+Q, which a terminal may keep for itself.
     */
    private val RESERVED = setOf('c', 'd', 'h', 'i', 'j', 'l', 'm', 'q', 'u', 'z')

    /** The byte [spec] - `ctrl+o` - arrives as, or null for a spec that is not a Ctrl+letter one may set. */
    fun code(spec: String): Int? {
        val letter = Regex("""ctrl\+([a-z])""").matchEntire(spec.trim().lowercase())?.groupValues?.get(1)?.single() ?: return null
        if (letter in RESERVED) return null
        return letter.code - 'a'.code + 1
    }

    /** The spec for the byte a key arrived as, or null for one that is not a Ctrl+letter that may be set. */
    fun specOf(code: Int): String? = if (code in 1..26) "ctrl+" + ('a' + code - 1) else null

    /** How a person reads it: Ctrl+O. */
    fun label(spec: String): String = spec.split('+').joinToString("+") { it.replaceFirstChar(Char::uppercase) }

    /** What [action] is bound to by [chosen], or its default where it was not set or set to something unusable. */
    fun specFor(action: String, chosen: Map<String, String>): String {
        val default = ACTIONS.first { it.name == action }.default
        return chosen[action]?.takeIf { code(it) != null } ?: default
    }
}
