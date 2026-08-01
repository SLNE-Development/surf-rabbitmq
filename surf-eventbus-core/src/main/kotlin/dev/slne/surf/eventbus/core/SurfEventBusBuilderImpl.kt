package dev.slne.surf.eventbus.core

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.RedisTransportLocator
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.SurfEventBusBuilder
import dev.slne.surf.eventbus.common.config.LegacyEnvironmentGuard
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.util.UUID

internal class SurfEventBusBuilderImpl(
    private val serviceName: String,
    private val dataPath: Path
) : SurfEventBusBuilder {
    private var instanceName: String? = null
    private var serializers: SerializersModule = SerializersModule { }
    private var rabbitEnabled = false
    private var eventTransport: EventTransport? = null
    private var queryTransport: QueryTransport? = null
    private var redisApi: RedisApi? = null

    override fun instanceName(name: String) = apply { instanceName = name }

    override fun serializers(module: SerializersModule) = apply { serializers = module }

    override fun withRabbit() = apply { rabbitEnabled = true }

    override fun withRedis() = apply {
        eventTransport = RedisTransportLocator.event()
        queryTransport = RedisTransportLocator.query()
        redisApi = RedisTransportLocator.redisApi()
    }

    override fun withRedis(event: EventTransport, query: QueryTransport) = apply {
        eventTransport = event
        queryTransport = query
    }

    override fun build(environment: Map<String, String>?): SurfEventBus {
        LegacyEnvironmentGuard.check(
            if (environment == null) EnvironmentVariables.system else EnvironmentVariables.from(environment)
        )

        check(rabbitEnabled || eventTransport != null) {
            "a bus needs at least one transport: add .withRabbit(), .withRedis(), or both to " +
                    "SurfEventBus.builder(\"$serviceName\", …)"
        }

        val resolvedInstanceId = instanceName ?: "$serviceName-${UUID.randomUUID()}"

        // Built here, not in withRabbit(): serializers()/instanceName() may still be called
        // after withRabbit() in a fluent chain, and both matter to the identity this shares
        // with the rest of the bus.
        val rabbitApi = if (rabbitEnabled) {
            SurfRabbitApi.builder(serviceName, dataPath)
                .instanceName(resolvedInstanceId)
                .serializers(serializers)
                .build()
        } else {
            null
        }

        return SurfEventBusImpl(
            serviceName = serviceName,
            instanceId = resolvedInstanceId,
            dataPath = dataPath,
            serializers = serializers,
            rabbitApi = rabbitApi,
            redisApi = redisApi,
            eventTransport = eventTransport,
            queryTransport = queryTransport
        )
    }
}
