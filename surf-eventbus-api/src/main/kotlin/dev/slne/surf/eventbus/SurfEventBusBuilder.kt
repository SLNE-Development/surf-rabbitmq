package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook

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

    /**
     * Uses [hook] for the standalone lifecycle instead of looking one up via `ServiceLoader`.
     *
     * Only consulted when this process has no platform. Injection exists so a test — or a
     * standalone host with its own bootstrap — does not have to register a service.
     */
    fun withStandaloneHook(hook: StandaloneLifecycleHook): SurfEventBusBuilder

    fun build(environment: Map<String, String>? = null): SurfEventBus
}
