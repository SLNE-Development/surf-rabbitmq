package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.api.core.environment.requireIn

private const val PREFIX = "SURF_EVENTBUS_REDIS_"

/**
 * Resolves the four configuration layers: `env > plugin yaml > global yaml > default`.
 *
 * Unlike RabbitMQ's per-field `IntOr.Default` merging, a YAML layer here wins or loses as a
 * whole: [plugin] if given, otherwise [global] if given, otherwise [RedisConfig]'s defaults.
 * `RedisConfig` has no sentinel "unset" value for a `String`/`Int` field, so there is nothing to
 * merge field-by-field - only the environment layer, which is genuinely optional per field,
 * overrides individual values on top of whichever YAML layer won.
 */
fun resolveRedisConfig(
    global: RedisConfig?,
    plugin: RedisConfig?,
    environment: EnvironmentVariables = EnvironmentVariables.system,
): RedisConfig {
    val yamlConfig = plugin ?: global ?: RedisConfig()

    return yamlConfig.copy(
        host = environment.optional(PREFIX + "HOST") {
            require("expected a non-blank host") { it.isNotBlank() }
        } ?: yamlConfig.host,
        port = environment.optionalInt(PREFIX + "PORT") { requireIn(0..65535) } ?: yamlConfig.port,
        password = environment.optional(PREFIX + "PASSWORD", sensitive = true) ?: yamlConfig.password,
        clientName = environment.optional(PREFIX + "CLIENT_NAME") {
            require("expected a non-blank client name") { it.isNotBlank() }
        } ?: yamlConfig.clientName,
    )
}
