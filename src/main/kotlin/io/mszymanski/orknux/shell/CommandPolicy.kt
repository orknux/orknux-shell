package io.mszymanski.orknux.shell

import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The modes `/mode` and Shift+Tab cycle through. */
object Modes {
    /** Local commands run under the rules; the agent works as chats always did. */
    const val AUTO = "auto"
    /** Every local command not denied is asked about. */
    const val ASK = "ask"
    /** The server's plan mode: the agent only looks, and puts a plan to approve. Local commands are asked about. */
    const val PLAN = "plan"

    val ALL = listOf(AUTO, ASK, PLAN)

    fun next(mode: String): String = ALL[(ALL.indexOf(mode).coerceAtLeast(0) + 1) % ALL.size]

    /** The chat's `ChatMode` on the server for a mode here. */
    fun serverMode(mode: String): String = if (mode == PLAN) "PLAN" else "DEFAULT"

    /** The approval mode local commands are held to. */
    fun approvalOf(mode: String): String = if (mode == AUTO) AUTO else ASK
}

enum class Verdict { RUN, ASK, DENY }

data class Decision(val verdict: Verdict, val why: String, val rule: String? = null)

/**
 * Whether a command the agent sent runs, is asked about, or is refused.
 *
 * First match wins, in this order: a deny rule refuses without asking; an ask
 * rule asks; the `ask` mode asks for everything not denied, an allow rule
 * included; an allow rule runs it - over the built-in caution, because a rule
 * the person wrote is the person's decision; then the built-in caution asks for
 * elevation (`sudo`, `su`, `doas`, `runas`) and for anything the model itself
 * marked dangerous; and whatever is left runs.
 *
 * Rules are Java regular expressions found anywhere in the command line,
 * case-sensitive; `^...$` anchors one to the whole of it.
 */
class CommandPolicy(rules: CommandRules) {
    private val broken = mutableListOf<String>()
    private val deny = compile("deny", rules.deny)
    private val ask = compile("ask", rules.ask)
    private val allow = compile("allow", rules.allow)

    /** Rules that are not regular expressions, by list and position; skipped. */
    val problems: List<String> get() = broken.toList()

    private fun compile(list: String, patterns: List<String>): List<Pair<String, Pattern>> =
        patterns.mapIndexedNotNull { index, source ->
            try {
                source to Pattern.compile(source)
            } catch (bad: PatternSyntaxException) {
                broken += "commands.$list[$index] \"$source\" is not a regular expression (${bad.description}); it is skipped."
                null
            }
        }

    fun decide(command: String, dangerous: Boolean, mode: String): Decision {
        deny.firstOrNull { it.second.matcher(command).find() }?.let {
            return Decision(Verdict.DENY, "Your rules forbid this command.", it.first)
        }
        ask.firstOrNull { it.second.matcher(command).find() }?.let {
            return Decision(Verdict.ASK, "It matches your ask rule ${it.first}", it.first)
        }
        if (Modes.approvalOf(mode) == Modes.ASK) return Decision(Verdict.ASK, "Every command is asked about in $mode mode.")
        allow.firstOrNull { it.second.matcher(command).find() }?.let {
            return Decision(Verdict.RUN, "Your allow rule ${it.first}", it.first)
        }
        if (elevates(command)) return Decision(Verdict.ASK, "It runs with elevated rights.")
        if (dangerous) return Decision(Verdict.ASK, "The agent marked it dangerous.")
        return Decision(Verdict.RUN, "Nothing asks about it.")
    }

    companion object {
        /** `sudo` and its kin as a word at the start of the line or of any command chained after it. */
        private val ELEVATION = Regex("""(^|[;&|\n(]|&&|\|\||\$\()\s*(sudo|su|doas|runas|pkexec)(\s|$)""", RegexOption.IGNORE_CASE)
        private val RUN_AS = Regex("""(?i)-Verb\s+RunAs\b""")

        fun elevates(command: String): Boolean = ELEVATION.containsMatchIn(command) || RUN_AS.containsMatchIn(command)

        /**
         * The allow rule offered for "Approve for pattern": the program and its first
         * subcommand, anchored at the start - `^git\s+push\b` - so the same kind of
         * command is not asked about again while different ones still are.
         */
        fun proposePattern(command: String): String {
            val words = command.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return "^$"
            val program = words[0]
            val second = words.getOrNull(1)
            val sub = second?.takeIf { Regex("[A-Za-z][A-Za-z0-9_.:-]*").matches(it) }
            val last = sub ?: program
            // \b only means something after a word character; `c++` ends where a space or the line does.
            val end = if (last.last().isLetterOrDigit() || last.last() == '_') "\\b" else "(?=\\s|$)"
            return if (sub != null) "^${escape(program)}\\s+${escape(sub)}$end" else "^${escape(program)}$end"
        }

        /** Escapes what a regular expression would read as syntax, one character at a time, so the rule stays readable. */
        fun escape(text: String): String = buildString {
            text.forEach { c -> if (c in "\\.[]{}()*+?^$|") append('\\'); append(c) }
        }
    }
}
