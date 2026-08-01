package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule

/**
 * Builds a [SurfEventBus]. Implemented in `surf-eventbus-bus-core`, obtained through
 * [SurfEventBus.builder].
 */
interface SurfEventBusBuilder {

    fun instanceName(name: String): SurfEventBusBuilder

    fun serializers(module: SerializersModule): SurfEventBusBuilder

    /** Enables RPC and the audit path. */
    fun withRabbit(): SurfEventBusBuilder

    /** Enables events, queries, sync structures and caches. */
    fun withRedis(): SurfEventBusBuilder

    /** Test seam: enables Redis with explicit transports. `.redis` throws, since a fake
     * `EventTransport`/`QueryTransport` pair has no `RedisApi` behind it. */
    fun withRedis(event: EventTransport, query: QueryTransport): SurfEventBusBuilder

    fun build(environment: Map<String, String>? = null): SurfEventBus
}
