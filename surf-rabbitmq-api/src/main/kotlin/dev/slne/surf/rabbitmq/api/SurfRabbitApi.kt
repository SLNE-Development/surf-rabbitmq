package dev.slne.surf.rabbitmq.api

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.rabbitmq.api.identity.RabbitIdentity
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.api.packet.standard.response.StringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.optional.OptionalStringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.OptionalPrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.PrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.ArrayResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.OptionalArrayResponse
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The entry point to RabbitMQ messaging.
 *
 * Replaces the former `ClientRabbitMQApi` / `ServerRabbitMQApi` split. There is no client or
 * server role: a process that calls [registerService] or [registerRequestHandler] hosts a
 * service queue, and one that does not simply has none. Both can call [rpc].
 *
 * A single instance addresses any number of services over one TCP connection, one publisher
 * pool and one reply queue.
 *
 * ```kotlin
 * val rabbit = SurfRabbitApi.builder("surf-factions", dataPath).build()
 *
 * rabbit.registerService<FactionService>(FactionServiceImpl)
 * rabbit.freezeAndConnect()
 *
 * val punish = rabbit.rpc<PunishService>()
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
class SurfRabbitApi @InternalRabbitMQ constructor(
    val identity: RabbitIdentity,
    @InternalRabbitMQ val config: CommonRabbitMQConfig,
    val cbor: Cbor,
    private val standalone: Boolean = false
) {
    @InternalRabbitMQ
    val scope = CoroutineScope(
        Dispatchers.Default +
                CoroutineName("SurfRabbitApi-${identity.instanceId}") +
                SupervisorJob() +
                CoroutineExceptionHandler { context, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log("Unhandled exception in SurfRabbitApi coroutine ${context[CoroutineName]}")
                }
    )

    // Lazy: constructing a SurfRabbitApi (e.g. for tests, or to inspect its identity/config)
    // must not require a broker-facing implementation to already be registered. Eagerly
    // creating these here would force every builder.build() through a ServiceLoader lookup
    // that only surf-rabbitmq-core satisfies.
    @InternalRabbitMQ
    val rpcService by lazy { RabbitRpcServiceFactory.instance.createRpcService(this) }

    @InternalRabbitMQ
    val connection: RabbitMQConnection by lazy { RabbitMQConnection.create(this) }

    private var frozen = false

    /**
     * Locks registration.
     *
     * Handlers and services must be known before the consumer starts, otherwise a message
     * could arrive for a handler that is still being registered.
     */
    fun freeze() {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        frozen = true
    }

    fun isFrozen(): Boolean = frozen

    suspend fun connect() {
        if (!frozen) throw SurfRabbitApiNotFrozenException()

        // Preserves the former ServerRabbitMQApi lifecycle for standalone microservices;
        // on Paper/Velocity the platform manages the lifecycle and the hook must not run.
        if (standalone) StandaloneLifecycleHook.beforeConnect()

        connection.connect()
    }

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    suspend fun disconnect() {
        connection.disconnect()
        scope.cancel("SurfRabbitApi disconnected")

        if (standalone) StandaloneLifecycleHook.afterDisconnect()
    }

    /**
     * Registers `@RabbitHandler` methods on [instance].
     *
     * Hosting a handler makes this process consume the service queue of
     * [RabbitIdentity.serviceName].
     */
    fun registerRequestHandler(instance: Any) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        connection.registerRequestHandler(instance)
    }

    /** Registers the server-side implementation of an `@RpcService` interface. */
    fun <Service : Any> registerService(serviceKClass: KClass<Service>, serviceInstance: Service) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        rpcService.registerService(serviceKClass, serviceInstance)
    }

    /** Registers the server-side implementation of an `@RpcService` interface. */
    inline fun <reified Service : Any> registerService(serviceInstance: Service) {
        registerService(Service::class, serviceInstance)
    }

    /**
     * Creates a client proxy for an `@RpcService` interface.
     *
     * The target service comes from the interface's `@RpcService(service = ...)`. Pass
     * [service] to override it, for example to reach a staging deployment.
     *
     * The returned proxy is cheap to keep but not free to create; create it once and reuse it.
     */
    fun <Service : Any> rpc(serviceKClass: KClass<Service>, service: String? = null): Service =
        rpcService.createService(serviceKClass, service)

    /** Creates a client proxy for an `@RpcService` interface. */
    inline fun <reified Service : Any> rpc(service: String? = null): Service =
        rpc(Service::class, service)

    /** The generated descriptor for an `@RpcService` interface, e.g. to read its `defaultService`. */
    inline fun <reified Service : Any> serviceDescriptorOf(): RabbitRpcServiceDescriptor<Service> =
        rpcService.serviceDescriptorOf(Service::class)

    /**
     * Publishes [event] to every matching subscriber.
     *
     * The publisher does not know who listens, and an event with no subscriber is discarded
     * without error. That is the point: adding or removing a subscriber never touches the
     * publisher.
     */
    suspend fun publish(event: RabbitEventPacket) {
        connection.publishEvent(event)
    }

    /**
     * Sends [packet] to one instance of [target] without waiting for a reply.
     *
     * Unlike an event, this is delivered to exactly one instance and waits in a durable queue
     * if none is running, so the work is done once the service returns.
     *
     * Defaults to this process's own service when [target] is omitted.
     */
    suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget? = null) {
        connection.send(packet, target ?: RabbitTarget.ServiceTarget(identity.serviceName))
    }

    /** Registers `@RabbitSubscribe` methods on [listener]. */
    fun registerListener(listener: Any) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        connection.registerListener(listener)
    }

    companion object {
        private val log = logger()

        fun builder(serviceName: String, dataPath: Path): SurfRabbitApiBuilder =
            SurfRabbitApiBuilder(serviceName, dataPath)

        @InternalRabbitMQ
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                include(SurfSerializerModule.all.overwriteWith(additionalSerializerModule))
                include(defaultSerializersModule)
            }
        }

        private val defaultSerializersModule = SerializersModule {
            include(PrimitiveResponse.SERIALIZER_MODULE)
            include(OptionalPrimitiveResponse.SERIALIZER_MODULE)
            include(ArrayResponse.SERIALIZER_MODULE)
            include(OptionalArrayResponse.SERIALIZER_MODULE)

            contextual(StringResponsePacket.serializer())
            contextual(OptionalStringResponsePacket.serializer())
        }
    }
}
