package dev.slne.surf.eventbus.rabbitmq.api

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.eventbus.rabbitmq.api.identity.RabbitIdentity
import dev.slne.surf.eventbus.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.eventbus.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.api.rpc.RabbitRpcServiceFactory
import dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The entry point to RabbitMQ messaging.
 *
 * Replaces the former `ClientRabbitMQApi` / `ServerRabbitMQApi` split. There is no client or
 * server role: a process that calls [registerService] hosts a service queue, and one that does
 * not simply has none. Both can call [rpc].
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
     * [target] to override it, for example to reach one specific instance.
     *
     * The returned proxy is cached per `(serviceKClass, target)` pair, so calling this in a
     * loop body with an [RabbitTarget.InstanceTarget] is cheap.
     */
    fun <Service : Any> rpc(serviceKClass: KClass<Service>, target: RabbitTarget? = null): Service =
        rpcService.createService(serviceKClass, target)

    /** Creates a client proxy for an `@RpcService` interface. */
    inline fun <reified Service : Any> rpc(target: RabbitTarget? = null): Service =
        rpc(Service::class, target)

    /** The generated descriptor for an `@RpcService` interface, e.g. to read its `defaultService`. */
    inline fun <reified Service : Any> serviceDescriptorOf(): RabbitRpcServiceDescriptor<Service> =
        rpcService.serviceDescriptorOf(Service::class)

    companion object {
        private val log = logger()

        fun builder(serviceName: String, dataPath: Path): SurfRabbitApiBuilder =
            SurfRabbitApiBuilder(serviceName, dataPath)

        @InternalRabbitMQ
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SurfSerializerModule.all.overwriteWith(additionalSerializerModule)
        }
    }
}
