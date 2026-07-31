package dev.slne.surf.eventbus

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.common.config.LegacyEnvironmentGuard
import dev.slne.surf.eventbus.core.SurfEventBusImpl
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.util.UUID

class SurfEventBusBuilder internal constructor(
    private val serviceName: String,
    private val dataPath: Path
) {
    private var instanceName: String? = null
    private var serializers: SerializersModule = SerializersModule { }
    private var rabbitEnabled = false
    private var eventTransport: EventTransport? = null
    private var queryTransport: QueryTransport? = null

    fun instanceName(name: String) = apply { instanceName = name }

    fun serializers(module: SerializersModule) = apply { serializers = module }

    /** Enables RPC and the audit path. */
    fun withRabbit() = apply { rabbitEnabled = true }

    /** Enables events, queries, sync structures and caches. */
    fun withRedis() = apply {
        // The real transports are wired by surf-eventbus-redis-core through a ServiceLoader in
        // plan 3. Until then the overload below is what tests use.
        eventTransport = RedisTransportLocator.event()
        queryTransport = RedisTransportLocator.query()
    }

    /** Test seam: enables Redis with explicit transports. */
    fun withRedis(event: EventTransport, query: QueryTransport) = apply {
        eventTransport = event
        queryTransport = query
    }

    fun build(environment: Map<String, String>? = null): SurfEventBus {
        LegacyEnvironmentGuard.check(
            if (environment == null) EnvironmentVariables.system else EnvironmentVariables.from(environment)
        )

        check(rabbitEnabled || eventTransport != null) {
            "a bus needs at least one transport: add .withRabbit(), .withRedis(), or both to " +
                    "SurfEventBus.builder(\"$serviceName\", …)"
        }

        return SurfEventBusImpl(
            serviceName = serviceName,
            instanceId = instanceName ?: "$serviceName-${UUID.randomUUID()}",
            dataPath = dataPath,
            serializers = serializers,
            rabbitEnabled = rabbitEnabled,
            eventTransport = eventTransport,
            queryTransport = queryTransport
        )
    }
}
