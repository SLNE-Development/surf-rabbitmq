package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.core.audit.LoggingAuditSink
import dev.slne.surf.eventbus.core.dispatch.BusEventCodecs
import dev.slne.surf.eventbus.core.dispatch.EventDispatcher
import dev.slne.surf.eventbus.core.dispatch.EventTypeResolver
import dev.slne.surf.eventbus.core.dispatch.QueryDispatcher
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.core.registry.QueryServiceRegistry
import dev.slne.surf.eventbus.event.BusEventCodec
import dev.slne.surf.eventbus.event.EventTopics
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.query.QueryService
import dev.slne.surf.eventbus.query.descriptor.QueryServiceDescriptor
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import io.netty.buffer.Unpooled
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * Holds registry, dispatcher and the lifecycle behind [SurfEventBus].
 *
 * Transport enablement is code, not configuration: a verb whose transport is missing fails
 * loudly and names the builder call. [freeze] rejects a subscription without its transport and
 * names the handler.
 */
class SurfEventBusImpl(
    private val serviceName: String,
    private val instanceId: String,
    @Suppress("unused") private val dataPath: Path,
    private val serializers: SerializersModule,
    private val rabbitEnabled: Boolean,
    private val eventTransport: EventTransport?,
    private val queryTransport: QueryTransport?
) : SurfEventBus {

    private val json = Json {
        serializersModule = serializers
        ignoreUnknownKeys = true
    }

    private val eventRegistry = EventSubscriptionRegistry()
    private val queryRegistry = QueryServiceRegistry()
    private val dispatcher = EventDispatcher(
        registry = eventRegistry,
        instanceId = instanceId,
        auditSink = LoggingAuditSink,
        json = json,
        typeResolver = EventTypeResolver(),
        serviceName = serviceName
    )
    private val queryDispatcher = QueryDispatcher(queryRegistry)
    private val serializerCache = KotlinSerializerCache<SurfBusEvent>(serializers)

    @Volatile
    private var frozen = false

    override fun subscribe(listener: Any) {
        check(!frozen) { "registration is closed: freeze() has already run" }
        eventRegistry.register(listener)
    }

    override fun <T : Any> registerService(contract: KClass<T>, implementation: T) {
        check(!frozen) { "registration is closed: freeze() has already run" }

        val javaContract = contract.java
        require(javaContract.getAnnotation(QueryService::class.java) != null) {
            "${javaContract.name} is not annotated with @QueryService (@RpcService arrives in a later plan)"
        }
        queryRegistry.register(javaContract, implementation)
    }

    override suspend fun publish(event: SurfBusEvent) {
        val transport = requireRedis("bus.publish()")
        val topic = EventTopics.topicOf(event.javaClass)
        val codec = BusEventCodecs.codecFor(event.javaClass)

        val envelope = EventEnvelope(
            topic = topic,
            type = event.javaClass.name,
            originInstanceId = instanceId,
            publishedAtEpochMs = System.currentTimeMillis(),
            payload = if (codec == null) json.encodeToString(serializerOf(event.javaClass), event) else null
        )

        transport.publish(envelope, codec?.let { encodeBinary(it, event) })
    }

    override fun <T : Any> query(contract: KClass<T>): T {
        val transport = requireQueryTransport("query()")
        val descriptor = queryDescriptorOf(contract)

        @Suppress("UNCHECKED_CAST")
        return descriptor.createInstance(instanceId, json, transport) as T
    }

    override fun <T : Any> rpc(contract: KClass<T>): T {
        throw NotImplementedError(
            "rpc() requires the Plan 3 KSP client-proxy generator, not yet available for " +
                    (contract.qualifiedName ?: contract.java.name)
        )
    }

    override fun freeze() {
        if (eventTransport == null && !eventRegistry.isEmpty()) {
            val handlers = eventRegistry.subscriptions().joinToString(", ") { it.displayName }
            error(
                """
                These @SurfSubscribe handlers need the Redis transport: $handlers
                -> add .withRedis() to SurfEventBus.builder(...)
                """.trimIndent()
            )
        }

        eventRegistry.freeze()
        queryRegistry.freeze()
        frozen = true
    }

    override suspend fun connect() {
        check(frozen) { "connect() before freeze(): a message could hit a half-registered handler" }

        try {
            eventTransport?.connect(
                exactTopics = eventRegistry.exactTopics(),
                wildcardPatterns = eventRegistry.wildcardPatterns(),
                onEvent = dispatcher::dispatch
            )
            queryTransport?.connect(queryRegistry.contracts()) { frame -> queryDispatcher.dispatch(frame) }
        } catch (throwable: Throwable) {
            // Half connected is harder to diagnose than not started.
            runCatching { eventTransport?.disconnect() }
            runCatching { queryTransport?.disconnect() }
            throw throwable
        }
    }

    override suspend fun disconnect() {
        eventTransport?.disconnect()
        queryTransport?.disconnect()
    }

    override val rabbit: Any
        get() {
            check(rabbitEnabled) {
                "the RabbitMQ surface requires the RabbitMQ transport,\n" +
                        "but this bus was built without it.\n" +
                        "-> add .withRabbit() to SurfEventBus.builder(...)"
            }
            error("the RabbitMQ surface is not implemented yet (arrives with the aggregate modules in a later plan)")
        }

    override val redis: Any
        get() {
            check(eventTransport != null) {
                "the Redis surface requires the Redis transport,\n" +
                        "but this bus was built without it.\n" +
                        "-> add .withRedis() to SurfEventBus.builder(...)"
            }
            error("the Redis surface is not implemented yet (arrives with the aggregate modules in a later plan)")
        }

    private fun requireRedis(verb: String): EventTransport = eventTransport ?: error(
        """
        $verb requires the Redis transport,
        but this bus was built without it.
        -> add .withRedis() to SurfEventBus.builder(...)
        """.trimIndent()
    )

    private fun requireQueryTransport(verb: String): QueryTransport = queryTransport ?: error(
        """
        $verb requires the Redis transport,
        but this bus was built without it.
        -> add .withRedis() to SurfEventBus.builder(...)
        """.trimIndent()
    )

    private fun <T : Any> queryDescriptorOf(contract: KClass<T>): QueryServiceDescriptor<T> {
        val descriptor = QueryDescriptorCache.get(contract.java)
            ?: error(
                "no generated descriptor found for ${contract.qualifiedName ?: contract.java.name}; " +
                        "is the interface annotated with @QueryService?"
            )

        @Suppress("UNCHECKED_CAST")
        return descriptor as QueryServiceDescriptor<T>
    }

    private object QueryDescriptorCache : ClassValue<Any?>() {
        override fun computeValue(type: Class<*>): Any? {
            if (!type.isInterface) return null
            val descriptorFqName = "${type.packageName}.${type.simpleName}Descriptor"

            return try {
                Class.forName(descriptorFqName, false, type.classLoader).kotlin.objectInstance
            } catch (_: ClassNotFoundException) {
                null
            } catch (_: LinkageError) {
                null
            }
        }
    }

    private fun serializerOf(eventClass: Class<out SurfBusEvent>) =
        serializerCache.get(eventClass)
            ?: error("no kotlinx.serialization serializer for ${eventClass.name}")

    private fun encodeBinary(codec: BusEventCodec<SurfBusEvent>, event: SurfBusEvent): ByteArray {
        val buffer = Unpooled.buffer()
        try {
            codec.encode(buffer, event)
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            return bytes
        } finally {
            buffer.release()
        }
    }
}
