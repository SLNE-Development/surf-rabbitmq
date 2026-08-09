package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.SurfEventBusBuilder
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.core.audit.LoggingAuditSink
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.redis.SurfRedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.util.UUID

internal class SurfEventBusBuilderImpl(
    private val serviceName: String,
    private val dataPath: Path,
) : SurfEventBusBuilder {
    private var instanceName: String? = null
    private var serializers: SerializersModule = SerializersModule { }
    private var rabbitEnabled = false
    private var eventTransport: EventTransport? = null
    private var queryTransport: QueryTransport? = null
    private var redisApi: SurfRedisApi? = null
    private var standaloneHook: StandaloneLifecycleHook? = null

    override fun instanceName(name: String) = apply { instanceName = name }

    override fun serializers(module: SerializersModule) = apply { serializers = module }

    override fun withRabbit() = apply { rabbitEnabled = true }

    override fun withStandaloneHook(hook: StandaloneLifecycleHook) = apply { standaloneHook = hook }

    /**
     * The consumer's own [dataPath] is what the Redis settings are resolved against, so this
     * plugin's `eventbus-plugin.yml` overrides the broker-wide `eventbus.yml` field by field —
     * the same four layers RabbitMQ has always resolved.
     */
    override fun withRedis() =
        apply {
            val transports = RedisTransportLocator.transports(dataPath)

            eventTransport = transports.event
            queryTransport = transports.query
            redisApi = transports.redisApi
        }

    override fun withRedis(
        event: EventTransport,
        query: QueryTransport,
    ) = apply {
        eventTransport = event
        queryTransport = query
    }

    override fun build(environment: Map<String, String>?): SurfEventBus {
        check(rabbitEnabled || eventTransport != null) {
            "a bus needs at least one transport: add .withRabbit(), .withRedis(), or both to " +
                "SurfEventBus.builder(\"$serviceName\", …)"
        }

        val resolvedInstanceId = instanceName ?: "$serviceName-${UUID.randomUUID()}"

        // Built here, not in withRabbit(): serializers()/instanceName() may still be called
        // after withRabbit() in a fluent chain, and both matter to the identity this shares
        // with the rest of the bus.
        val rabbitApi =
            if (rabbitEnabled) {
                SurfRabbitApi
                    .builder(serviceName, dataPath)
                    .instanceName(resolvedInstanceId)
                    .serializers(serializers)
                    .apply { standaloneHook?.let { standaloneHook(it) } }
                    .apply { environment?.let { environment(it) } }
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
            queryTransport = queryTransport,
            auditSink = auditSinkFor(rabbitApi),
        )
    }

    /**
     * The RabbitMQ sink when there is one, so event and query losses reach the audit service
     * instead of only the log.
     *
     * Indirected rather than resolved here: `api.connection` builds the connection (and with it
     * a `RabbitClient`) on first touch, and `build()` is not the right moment for that. The
     * first report resolves it instead, which is the same deferral `RabbitConnectionImpl` uses
     * for the sink's own proxy.
     */
    private fun auditSinkFor(rabbitApi: SurfRabbitApi?): AuditSink {
        if (rabbitApi == null) return LoggingAuditSink

        return object : AuditSink {
            override suspend fun report(report: AuditReport) = rabbitApi.connection.auditSink.report(report)
        }
    }
}
