package io.mszymanski.orknux.shell

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * What commands the far side actually has, worked out once at start.
 *
 * The image ships node, npm, git and DesktopCommander, but the jar can be run
 * on a machine that has none of them — so this does not claim a toolchain, it
 * checks for one. Each candidate is looked up the way a shell would
 * (`command -v`), and only the ones that answer are named to the MCP client in
 * the `initialize` instructions. An agent then knows what it may reach for
 * without a round trip to find out, and is never told git is here when it is
 * not.
 */
@Component
class Toolchain(private val properties: ShellProperties) {

    /** The present ones, by name, in the order worth mentioning. */
    val available: List<String> by lazy { CANDIDATES.filter(::present) }

    /** A sentence for the instructions, or empty when nothing was found. */
    val sentence: String by lazy {
        if (available.isEmpty()) {
            ""
        } else {
            " On the PATH: ${available.joinToString(", ")}. Reach any of these through start_process."
        }
    }

    /**
     * Whether the tool is on the PATH, asked the way a shell asks — `command
     * -v`, which resolves the name and exits. Deliberately *not* `tool
     * --version`: some of these are not version-printers but servers, and
     * `desktop-commander --version` starts DC's stdio server and reads stdin
     * forever, which once wedged the very first `initialize`. Presence is all
     * the instructions need, and presence is all this asks for.
     */
    private fun present(tool: String): Boolean = runCatching {
        val found = ProcessBuilder(properties.shell, "-c", "command -v $tool")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (!found.waitFor(5, TimeUnit.SECONDS)) {
            found.destroyForcibly()
            false
        } else {
            found.exitValue() == 0
        }
    }.onFailure { log.debug("Could not probe for {}", tool, it) }.getOrDefault(false)

    private companion object {
        val log = LoggerFactory.getLogger(Toolchain::class.java)

        /** The commands a coding agent is most likely to want to know are there. */
        val CANDIDATES = listOf("git", "node", "npm", "python3", "desktop-commander")
    }
}
