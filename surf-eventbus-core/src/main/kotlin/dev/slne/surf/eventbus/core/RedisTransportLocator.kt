package dev.slne.surf.eventbus.core

import dev.slne.surf.api.core.util.requiredService
import java.nio.file.Path

/**
 * Finds the Redis transports on the classpath.
 *
 * `withRedis()` on the builder must not reference `surf-eventbus-redis-core` — the bus API sits
 * below the transports. A `ServiceLoader`-backed lookup (via [requiredService], the same
 * mechanism `RedisComponentProvider` uses) is the smallest thing that inverts that dependency.
 */
internal object RedisTransportLocator {
    private val provider by lazy { requiredService<RedisTransportProvider>() }

    fun transports(pluginDataPath: Path): RedisTransports = provider.create(pluginDataPath)
}
