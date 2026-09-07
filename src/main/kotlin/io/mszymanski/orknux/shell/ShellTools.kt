package io.mszymanski.orknux.shell

import org.springframework.stereotype.Component

/**
 * The whole tool surface, in the order an agent reads it.
 *
 * One place holds the list so `tools/list` cannot drift from `tools/call`:
 * whatever is advertised is exactly what dispatches, which is the contract's
 * "stable tools/list" made structural rather than disciplined.
 */
@Component
class ShellTools(processes: ProcessTools, files: FileTools, search: SearchTools) {

    val all: List<Tool> = processes.tools + files.tools + search.tools

    private val byName: Map<String, Tool> = all.associateBy(Tool::name)

    fun named(name: String): Tool? = byName[name]
}
