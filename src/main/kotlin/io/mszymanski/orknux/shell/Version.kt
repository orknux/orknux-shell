package io.mszymanski.orknux.shell

import java.util.Properties

/** This build's version, from the pom, so a release bumps it in one place. */
object Version {
    val VERSION: String by lazy {
        runCatching {
            Version::class.java.getResourceAsStream("/io/mszymanski/orknux/shell/version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            }
        }.getOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("$") } ?: "0.0.0-dev"
    }
}
