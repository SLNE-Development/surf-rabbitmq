package dev.slne.surf.eventbus.config.settings

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.EventBusDefaults
import dev.slne.surf.eventbus.credentials.CredentialsConfigurable

/** The Redis settings the client connects with. */
@InternalEventBusApi
data class RedisSettings(
    val host: String = EventBusDefaults.REDIS_HOST,
    val port: Int = EventBusDefaults.REDIS_PORT,
    val password: String? = null,
    val clientName: String = EventBusDefaults.redisClientName(),
) : CredentialsConfigurable {
    override fun toString(): String = "RedisSettings(host=$host, port=$port, password=<redacted>, clientName=$clientName)"
}
