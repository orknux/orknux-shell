package io.mszymanski.orknux.shell

/**
 * Orknux's own colours, in the terminal.
 *
 * The defaults are the web interface's dark tokens (`orknux-ui/src/styles/tokens.css`):
 * Orknux blue for the brand (the interface's default accent), the near-white of primary text, zinc for muted, and
 * the thinking red that keeps a model's reasoning from reading as its answer.
 */
class Theme(overrides: Map<String, String> = emptyMap(), val depth: ColourDepth = ColourDepth.detect()) {

    val colours: Map<String, String> = DEFAULTS + overrides.filter { (k, v) -> k in DEFAULTS.keys && parseHex(v) != null }

    val unknown: List<String> = overrides.filter { (k, v) -> k !in DEFAULTS.keys || parseHex(v) == null }.map { (k, v) -> "$k=$v" }

    fun fg(name: String): String = when (depth) {
        ColourDepth.NONE -> ""
        else -> foreground(colours.getValue(name), depth)
    }

    fun paint(name: String, text: String): String = if (depth == ColourDepth.NONE) text else "${fg(name)}$text$RESET"

    fun accent(text: String) = paint("accent", text)
    fun text(text: String) = paint("text", text)
    fun muted(text: String) = paint("muted", text)
    fun success(text: String) = paint("success", text)
    fun warning(text: String) = paint("warning", text)
    fun danger(text: String) = paint("danger", text)
    fun thinking(text: String) = paint("thinking", text)
    fun code(text: String) = paint("code", text)

    fun bold(text: String) = if (depth == ColourDepth.NONE) text else "$BOLD$text$NOT_BOLD"
    fun italic(text: String) = if (depth == ColourDepth.NONE) text else "$ITALIC$text$NOT_ITALIC"
    fun dim(text: String) = if (depth == ColourDepth.NONE) text else "$DIM$text$NOT_BOLD"
    fun underline(text: String) = if (depth == ColourDepth.NONE) text else "$UNDERLINE$text$NOT_UNDERLINE"

    /** The background, where one is set; nothing by default, so the terminal's own shows. */
    fun background(): String = colours["background"]?.let { hex -> if (depth == ColourDepth.NONE) "" else background(hex, depth) } ?: ""

    companion object {
        const val RESET = "\u001b[0m"
        const val BOLD = "\u001b[1m"
        const val DIM = "\u001b[2m"
        const val NOT_BOLD = "\u001b[22m"
        const val ITALIC = "\u001b[3m"
        const val NOT_ITALIC = "\u001b[23m"
        const val UNDERLINE = "\u001b[4m"
        const val NOT_UNDERLINE = "\u001b[24m"

        val DEFAULTS: Map<String, String> = linkedMapOf(
            "accent" to "#4f7fc4",
            "text" to "#e4e4e7",
            "muted" to "#71717a",
            "success" to "#10b981",
            "warning" to "#f59e0b",
            "danger" to "#f87171",
            "thinking" to "#c4453d",
            "code" to "#c084fc",
        )

        fun parseHex(hex: String): Triple<Int, Int, Int>? {
            val h = hex.trim().removePrefix("#")
            if (!Regex("[0-9a-fA-F]{6}").matches(h)) return null
            return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        }

        fun foreground(hex: String, depth: ColourDepth): String {
            val (r, g, b) = parseHex(hex) ?: return ""
            return when (depth) {
                ColourDepth.TRUE -> "\u001b[38;2;$r;$g;${b}m"
                ColourDepth.INDEXED -> "\u001b[38;5;${to256(r, g, b)}m"
                ColourDepth.NONE -> ""
            }
        }

        fun background(hex: String, depth: ColourDepth): String {
            val (r, g, b) = parseHex(hex) ?: return ""
            return when (depth) {
                ColourDepth.TRUE -> "\u001b[48;2;$r;$g;${b}m"
                ColourDepth.INDEXED -> "\u001b[48;5;${to256(r, g, b)}m"
                ColourDepth.NONE -> ""
            }
        }

        /** The nearest of xterm's 256: the 6x6x6 cube or the grey ramp, whichever is closer. */
        fun to256(r: Int, g: Int, b: Int): Int {
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            fun nearest(v: Int) = steps.indices.minBy { kotlin.math.abs(steps[it] - v) }
            val (cr, cg, cb) = Triple(nearest(r), nearest(g), nearest(b))
            val cube = 16 + 36 * cr + 6 * cg + cb
            val cubeDistance = sq(steps[cr] - r) + sq(steps[cg] - g) + sq(steps[cb] - b)
            val grey = ((r + g + b) / 3 - 8).coerceIn(0, 230) / 10
            val greyValue = 8 + grey * 10
            val greyDistance = sq(greyValue - r) + sq(greyValue - g) + sq(greyValue - b)
            return if (greyDistance < cubeDistance) 232 + grey else cube
        }

        private fun sq(v: Int) = v * v
    }
}

enum class ColourDepth {
    TRUE, INDEXED, NONE;

    companion object {
        fun detect(env: Map<String, String> = System.getenv()): ColourDepth {
            if (env.containsKey("NO_COLOR")) return NONE
            if (env["TERM"] == "dumb") return NONE
            val colourTerm = env["COLORTERM"]?.lowercase()
            if (colourTerm == "truecolor" || colourTerm == "24bit") return TRUE
            // Windows Terminal and recent conhost draw 24-bit colour and say so nowhere else.
            if (env.containsKey("WT_SESSION")) return TRUE
            return INDEXED
        }
    }
}

/** The visible width of text that may carry ANSI escapes. */
fun visibleLength(text: String): Int = stripAnsi(text).codePointCount(0, stripAnsi(text).length)

fun stripAnsi(text: String): String = text.replace(ANSI, "")

private val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]|\u001b]8;[^\u001b]*\u001b\\\\")
