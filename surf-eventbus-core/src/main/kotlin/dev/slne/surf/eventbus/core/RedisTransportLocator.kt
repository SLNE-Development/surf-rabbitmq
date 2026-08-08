package dev.slne.surf.eventbus.core

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
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

/** Implemented once, by `surf-eventbus-redis-core`, and discovered via `@AutoService`. */
interface RedisTransportProvider {

    /**
     * The transports for a consumer rooted at [pluginDataPath].
     *
     * Takes the path because the settings behind the connection are resolved four-layered, and
     * the plugin layer is that folder's `eventbus-plugin.yml`. The provider used to build one
     * `RedisApi` in its constructor from a process-wide config, which is why two plugins on one
     * server shared a connection neither of them could configure.
     */
    fun create(pluginDataPath: Path): RedisTransports
}

/**
 * One consumer's Redis transports and the [RedisApi] behind both of them.
 *
 * Returned together because they must be the same instance: `event()` and `query()` as separate
 * calls invited a second connection for the second call.
 */
class RedisTransports(
    val redisApi: RedisApi,
    val event: EventTransport,
    val query: QueryTransport,
)
