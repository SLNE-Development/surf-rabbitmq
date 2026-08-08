package dev.slne.surf.eventbus.rabbitmq

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.eventbus.rabbitmq.identity.RabbitIdentity
import dev.slne.surf.eventbus.config.RabbitMQSettings
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.rpc.RabbitRpcServiceFactory
import dev.slne.surf.eventbus.rabbitmq.rpc.descriptor.RpcServiceDescriptor
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.overwriteWith
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
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
class SurfRabbitApi @InternalEventBusApi constructor(
    val identity: RabbitIdentity,
    @InternalEventBusApi val config: RabbitMQSettings,
    val cbor: Cbor,
    private val standalone: Boolean = false,
    /**
     * What to run around connect/disconnect when [standalone].
     *
     * Injected rather than looked up globally, so a test can pass a double instead of
     * registering one in `META-INF/services`.
     */
    private val standaloneHook: StandaloneLifecycleHook = StandaloneLifecycleHook.NoOp,
) {
    @InternalEventBusApi
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
    @InternalEventBusApi
    val rpcService by lazy { RabbitRpcServiceFactory.instance.createRpcService(this) }

    @InternalEventBusApi
    val connection: RabbitMQConnection by lazy { RabbitMQConnection.create(this) }

    // An AtomicBoolean rather than a plain var: freeze() is a check-then-act, and without
    // publication two threads could both pass the check and both believe they froze the api.
    // SurfEventBusImpl, EventSubscriptionRegistry, QueryServiceRegistry and RedisApi all guard
    // their own flag; this was the one that did not.
    private val frozen = AtomicBoolean(false)

    /**
     * Locks registration.
     *
     * Handlers and services must be known before the consumer starts, otherwise a message
     * could arrive for a handler that is still being registered.
     */
    fun freeze() {
        if (!frozen.compareAndSet(false, true)) throw SurfRabbitApiAlreadyFrozenException()
    }

    fun isFrozen(): Boolean = frozen.get()

    suspend fun connect() {
        if (!frozen.get()) throw SurfRabbitApiNotFrozenException()

        // Preserves the former ServerRabbitMQApi lifecycle for standalone microservices;
        // on Paper/Velocity the platform manages the lifecycle and the hook must not run.
        if (standalone) standaloneHook.beforeConnect()

        connection.connect()
    }

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    suspend fun disconnect() {
        connection.disconnect()
        scope.cancel("SurfRabbitApi disconnected")

        if (standalone) standaloneHook.afterDisconnect()
    }

    /** Registers the server-side implementation of an `@RpcService` interface. */
    fun <Service : Any> registerService(serviceKClass: KClass<Service>, serviceInstance: Service) {
        if (frozen.get()) throw SurfRabbitApiAlreadyFrozenException()
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
    inline fun <reified Service : Any> serviceDescriptorOf(): RpcServiceDescriptor<Service> =
        rpcService.serviceDescriptorOf(Service::class)

    companion object {
        private val log = logger()

        fun builder(serviceName: String, dataPath: Path): SurfRabbitApiBuilder =
            SurfRabbitApiBuilder(serviceName, dataPath)

        @InternalEventBusApi
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SurfSerializerModule.all.overwriteWith(additionalSerializerModule)
        }
    }
}
