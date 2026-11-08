package io.mszymanski.orknux.shell

/** Which way of showing the conversation a line belongs to: both, only folded, or only in full. */
enum class View { BOTH, FOLDED, FULL }

/**
 * Every line the Shell has written into the conversation, kept so the screen
 * can be written again: when Ctrl+O switches between the folded view - a
 * thought as "▸ Thought for 3s", a tool call as one line - and the full one,
 * and when the window changes size. A thought and a tool call are kept both
 * ways, and which is shown is decided as they are written out.
 */
class Transcript(private val cap: Int = CAP) {
    private val entries = ArrayDeque<Pair<String, View>>()

    @Synchronized
    fun add(text: String, view: View = View.BOTH) {
        entries.addLast(text to view)
        while (entries.size > cap) entries.removeFirst()
    }

    /** The lines as [full] shows them, never two blank ones together - the folded view loses lines between blanks. */
    @Synchronized
    fun lines(full: Boolean): List<String> {
        val out = ArrayList<String>(entries.size)
        var blank = true
        for ((text, view) in entries) {
            if (!shown(view, full)) continue
            val isBlank = stripAnsi(text).isBlank()
            if (isBlank && blank) continue
            blank = isBlank
            out += text
        }
        return out
    }

    companion object {
        const val CAP = 20_000

        fun shown(view: View, full: Boolean) = view == View.BOTH || (view == View.FULL) == full
    }
}
