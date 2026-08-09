package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.redis.SurfRedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport

/**
 * One consumer's Redis transports and the [SurfRedisApi] behind both of them.
 *
 * Returned together because they must be the same instance: `event()` and `query()` as separate
 * calls invited a second connection for the second call.
 */
class RedisTransports(
    val redisApi: SurfRedisApi,
    val event: EventTransport,
    val query: QueryTransport,
)
