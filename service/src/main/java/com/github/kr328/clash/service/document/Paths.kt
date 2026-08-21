package com.github.kr328.clash.service.document

import java.util.*

object Paths {
    const val CONFIGURATION_ID = "config.yaml"
    const val CONFIGURATION_RAW_ID = "config.yaml.raw"
    const val A_YAML_ID = "a.yaml"
    const val PROVIDERS_ID = "providers"

    fun resolve(path: String): Path {
        val segments = path.split("/").filter { it.isNotBlank() && it != "." && it != ".." }

        return when (segments.size) {
            0 -> Path(
                uuid = null,
                scope = null,
                relative = null,
            )
            1 -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = null,
                relative = null,
            )
            2 -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = when (segments[1]) {
                    CONFIGURATION_ID -> Path.Scope.Configuration
                    CONFIGURATION_RAW_ID -> Path.Scope.ConfigurationRaw
                    A_YAML_ID -> Path.Scope.AYaml
                    PROVIDERS_ID -> Path.Scope.Providers
                    else -> throw IllegalArgumentException("unknown scope ${segments[1]}")
                },
                relative = null,
            )
            else -> Path(
                uuid = UUID.fromString(segments[0]),
                scope = when (segments[1]) {
                    CONFIGURATION_ID -> Path.Scope.Configuration
                    CONFIGURATION_RAW_ID -> Path.Scope.ConfigurationRaw
                    A_YAML_ID -> Path.Scope.AYaml
                    PROVIDERS_ID -> Path.Scope.Providers
                    else -> throw IllegalArgumentException("unknown scope ${segments[1]}")
                },
                relative = segments.drop(2),
            )
        }
    }
}