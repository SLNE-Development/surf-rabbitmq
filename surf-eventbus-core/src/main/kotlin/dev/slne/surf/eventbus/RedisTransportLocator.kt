package dev.slne.surf.eventbus

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport

/**
 * Finds the Redis transports on the classpath.
 *
 * `withRedis()` on the builder must not reference `surf-eventbus-redis-core` — the bus API sits
 * below the transports. A `ServiceLoader`-backed lookup (via [requiredService], the same
 * mechanism `RedisComponentProvider` uses) is the smallest thing that inverts that dependency.
 */
internal object RedisTransportLocator {
    private val provider by lazy { requiredService<RedisTransportProvider>() }

    fun event(): EventTransport = provider.event()

    fun query(): QueryTransport = provider.query()

    /** The `RedisApi` instance backing [event] and [query]. */
    fun redisApi(): RedisApi = provider.redisApi()
}

/** Implemented once, by `surf-eventbus-redis-core`, and discovered via `@AutoService`. */
interface RedisTransportProvider {
    fun event(): EventTransport
    fun query(): QueryTransport

    /** The shared `RedisApi` behind [event]/[query] - same instance, not a new connection. */
    fun redisApi(): RedisApi
}
