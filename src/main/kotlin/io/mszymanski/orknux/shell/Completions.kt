package io.mszymanski.orknux.shell

/** What the completer offers from, each asked only when the line needs it. */
interface CompletionSources {
    fun agents(): List<String> = emptyList()
    fun workspaces(): List<String> = emptyList()
    fun skills(): List<String> = emptyList()
    fun marker(): String? = null
    fun models(): List<String> = emptyList()
    fun chats(): List<String> = emptyList()
    fun refs(): List<String> = emptyList()
    fun efforts(): List<String> = listOf("minimal", "low", "medium", "high")
    fun attachments(): Int = 0
    fun links(): Int = 0
    fun subagents(): Int = 0
    /** File names for a typed path prefix, each as the whole word that would replace it; a directory ends in a separator. */
    fun files(prefix: String): List<String> = emptyList()
}

data class Completion(val value: String, val description: String? = null, val complete: Boolean = true)

/**
 * Tab completion for everything the input takes: command names, each
 * command's arguments, `@agent`, a skill after the workspace's marker, and
 * the keywords of `/goal`. Case-insensitive, matching what the current word
 * starts with; the line editor shows a menu where several match.
 */
class Completions(private val sources: CompletionSources) {

    /** Candidates for the word the cursor is in, given the line up to the cursor. */
    fun complete(beforeCursor: String): List<Completion> {
        val words = words(beforeCursor)
        val word = words.last()
        val index = words.size - 1
        val first = words.first()
        val all: List<Completion> = when {
            // The Shell's own commands, then the skills, which are typed with a slash here too.
            index == 0 && first.startsWith("/") -> SlashCommands.ALL.map { Completion("/" + it.name, it.summary) } +
                sources.skills().filter { key -> SlashCommands.ALL.none { it.name.equals(key, ignoreCase = true) } }
                    .map { Completion("/$it", "skill") }
            index == 0 && first.startsWith("@") -> sources.agents().map { Completion("@$it") }
            first.startsWith("/") -> arguments(first.removePrefix("/").lowercase(), words, index, word)
            else -> sources.marker()?.takeIf { word.startsWith(it) }?.let { m -> sources.skills().map { Completion(m + it) } } ?: emptyList()
        }
        return all.filter { it.value.startsWith(word, ignoreCase = true) }
    }

    private fun arguments(command: String, words: List<String>, index: Int, word: String): List<Completion> = when (command) {
        "agent" -> sources.agents().map(::Completion)
        "workspace" -> sources.workspaces().map(::Completion)
        "skill" -> if (index == 1) sources.skills().map(::Completion) else emptyList()
        "mode" -> if (index == 1) Modes.ALL.map(::Completion) else emptyList()
        "thinking" -> if (index == 1) Config.THINKING.map(::Completion) else emptyList()
        "effort" -> if (index == 1) (sources.efforts() + "default").map(::Completion) else emptyList()
        "attach" -> sources.files(word).map { Completion(it, null, !it.endsWith("/") && !it.endsWith("\\")) }
        "detach" -> if (index == 1) ((1..sources.attachments()).map { "$it" } + "all").map(::Completion) else emptyList()
        "open" -> if (index == 1) (1..sources.links()).map { Completion("$it") } else emptyList()
        "subagents" -> if (index == 1) (1..sources.subagents()).map { Completion("$it") } else emptyList()
        "resume" -> sources.chats().map(::Completion)
        "review" -> (listOf("--fix") + sources.refs()).filter { it !in words.dropLast(1) }.map(::Completion) +
            if (word.startsWith(".") || word.contains('/')) sources.files(word).map { Completion(it, null, false) } else emptyList()
        "goal" -> goal(words, index)
        else -> emptyList()
    }

    /** `/goal <what> until <condition> [max <n>] [reviewer <model>]`: `stop` first, then the keywords in turn, and models after `reviewer`. */
    private fun goal(words: List<String>, index: Int): List<Completion> {
        val before = words.subList(1, index).map { it.lowercase() }
        if (index == 1) return listOf(Completion("stop", "Stop the running goal"))
        return when {
            before.lastOrNull() == "reviewer" -> sources.models().map(::Completion)
            before.lastOrNull() == "max" -> emptyList()
            "until" !in before -> listOf(Completion("until", "then the condition that says it is done"))
            else -> listOfNotNull(
                Completion("max", "the most iterations").takeIf { "max" !in before },
                Completion("reviewer", "the model that judges each iteration").takeIf { "reviewer" !in before },
            )
        }
    }

    companion object {
        /** The line split into words at spaces a backslash does not escape; the last is the one being typed, maybe empty. */
        fun words(line: String): List<String> {
            val out = mutableListOf<String>()
            val word = StringBuilder()
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '\\' && i + 1 < line.length && line[i + 1] == ' ' -> { word.append(' '); i++ }
                    c == ' ' || c == '\t' -> if (word.isNotEmpty()) { out += word.toString(); word.setLength(0) }
                    else -> word.append(c)
                }
                i++
            }
            out += word.toString()
            return out
        }
    }
}
