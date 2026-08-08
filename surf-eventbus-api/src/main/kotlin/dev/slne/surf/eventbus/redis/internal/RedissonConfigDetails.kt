package dev.slne.surf.eventbus.redis.internal

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.RedisSettings
import kotlinx.serialization.modules.SerializersModule
import org.redisson.misc.RedisURI

/**
 * @param settings the resolved four-layer settings this client is being built from.
 *
 *   Carried alongside the [redisURI] derived from it because the config builder needs more than
 *   the address — `clientName` among it. It used to read that one field off the `redisConfig`
 *   global, so a plugin could override the host (via the URI, which was passed) but not the
 *   client name (which was not), from the same file.
 */
@InternalEventBusApi
data class RedissonConfigDetails(
    val redisURI: RedisURI,
    val settings: RedisSettings,
    val serializerModule: SerializersModule,
    val pluginName: String
)
