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

    /** The present ones, in the order worth mentioning, e.g. `git 2.47.2`. */
    val available: List<String> by lazy { CANDIDATES.mapNotNull(::probe) }

    /** A sentence for the instructions, or empty when nothing was found. */
    val sentence: String by lazy {
        if (available.isEmpty()) {
            ""
        } else {
            " On the PATH: ${available.joinToString(", ")}. Reach any of these through start_process."
        }
    }

    /** The tool's version line if it is on the PATH, or null. */
    private fun probe(tool: String): String? = runCatching {
        val found = ProcessBuilder(properties.shell, "-c", "command -v $tool")
            .redirectErrorStream(true).start()
        if (!found.waitFor(5, TimeUnit.SECONDS) || found.exitValue() != 0) return null

        val version = ProcessBuilder(properties.shell, "-c", "$tool --version 2>/dev/null | head -1")
            .redirectErrorStream(true).start()
        val said = version.inputStream.bufferedReader().readText().trim()
        version.waitFor(5, TimeUnit.SECONDS)
        said.ifEmpty { tool }
    }.onFailure { log.debug("Could not probe for {}", tool, it) }.getOrNull()

    private companion object {
        val log = LoggerFactory.getLogger(Toolchain::class.java)

        /** The commands a coding agent is most likely to want to know are there. */
        val CANDIDATES = listOf("git", "node", "npm", "python3", "desktop-commander")
    }
}
