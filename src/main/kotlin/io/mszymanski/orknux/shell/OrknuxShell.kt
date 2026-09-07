package io.mszymanski.orknux.shell

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * The deployable: an MCP server that is a POSIX shell with a workspace.
 *
 * Orknux reaches it over Streamable HTTP with a bearer token and relays every
 * tool it advertises to a granted agent, so the surface here — process, file
 * and search tools — is the whole of what an agent can do on this machine.
 * What bounds the agent is the jail: one MCP session is one throwaway root
 * directory, and every path a tool takes on that session must resolve inside
 * it. See `docs/orknux-shell-contract.md` in orknux-server for the contract
 * this implements.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class OrknuxShell

fun main(args: Array<String>) {
    runApplication<OrknuxShell>(*args)
}
