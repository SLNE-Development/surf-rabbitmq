package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport

/**
 * Forward reference to the ServiceLoader-based lookup of the real Redis transports, which
 * surf-eventbus-redis-core provides once it exists.
 *
 * Until then, `.withRedis()` with no arguments has nothing to find; tests and callers use the
 * explicit `withRedis(event, query)` overload instead.
 */
internal object RedisTransportLocator {
    fun event(): EventTransport = error(
        "no EventTransport is registered yet; surf-eventbus-redis-core wires this in a later plan"
    )

    fun query(): QueryTransport = error(
        "no QueryTransport is registered yet; surf-eventbus-redis-core wires this in a later plan"
    )
}
