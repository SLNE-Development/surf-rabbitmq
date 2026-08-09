package dev.slne.surf.eventbus.rabbitmq

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.config.settings.RabbitMQSettings
import dev.slne.surf.eventbus.connection.EventBusTransporter
import dev.slne.surf.eventbus.exception.api.SurfEventBusAlreadyFrozenException
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.connection.RabbitMQConnection
import dev.slne.surf.eventbus.rabbitmq.identity.RabbitIdentity
import dev.slne.surf.eventbus.rabbitmq.rpc.RabbitRpcServiceFactory
import dev.slne.surf.eventbus.rabbitmq.rpc.descriptor.RpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
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
class SurfRabbitApi
    @InternalEventBusApi
    constructor(
        val identity: RabbitIdentity,
        @InternalEventBusApi val config: RabbitMQSettings,
        val cbor: Cbor,
        standalone: Boolean = false,
        standaloneHook: StandaloneLifecycleHook = StandaloneLifecycleHook.NoOp,
    ) : EventBusTransporter(identity.instanceId, standalone, standaloneHook) {
        // Lazy: constructing a SurfRabbitApi (e.g. for tests, or to inspect its identity/config)
        // must not require a broker-facing implementation to already be registered. Eagerly
        // creating these here would force every builder.build() through a ServiceLoader lookup
        // that only surf-rabbitmq-core satisfies.
        @InternalEventBusApi
        val rpcService by lazy { RabbitRpcServiceFactory.createRpcService(this) }

        // Public because surf-eventbus-core reaches through it for the audit sink and the
        // request path; annotated so the ABI dump does not re-export RabbitMQConnection,
        // which is internal and names types the shadow jar relocates.
        @InternalEventBusApi
        public override val connection: RabbitMQConnection by lazy { RabbitMQConnection.create(this) }

        /** Registers the server-side implementation of an `@RpcService` interface. */
        fun <Service : Any> registerService(
            serviceKClass: KClass<Service>,
            serviceInstance: Service,
        ) {
            if (isFrozen) throw SurfEventBusAlreadyFrozenException()
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
        fun <Service : Any> rpc(
            serviceKClass: KClass<Service>,
            target: RabbitTarget? = null,
        ): Service = rpcService.createService(serviceKClass, target)

        /** Creates a client proxy for an `@RpcService` interface. */
        inline fun <reified Service : Any> rpc(target: RabbitTarget? = null): Service = rpc(Service::class, target)

        /** The generated descriptor for an `@RpcService` interface, e.g. to read its `defaultService`. */
        inline fun <reified Service : Any> serviceDescriptorOf(): RpcServiceDescriptor<Service> =
            rpcService.serviceDescriptorOf(Service::class)

        companion object {
            fun builder(
                serviceName: String,
                dataPath: Path,
            ): SurfRabbitApiBuilder = SurfRabbitApiBuilder(serviceName, dataPath)

            @InternalEventBusApi
            fun createCbor(additionalSerializerModule: SerializersModule): Cbor =
                Cbor {
                    ignoreUnknownKeys = true
                    serializersModule = SurfSerializerModule.all.overwriteWith(additionalSerializerModule)
                }
        }
    }
