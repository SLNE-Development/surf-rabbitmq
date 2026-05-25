package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.rabbitmq.api.rpc.ServerRabbitRpcService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class ServerRabbitMQApi @InternalRabbitMQ constructor(
    config: RabbitMQConfig,
    pluginName: String,
    cbor: Cbor
) : RabbitMQApi(config, pluginName, cbor) {
    override val connection get() = super.connection as ServerRabbitMQConnection

    @InternalRabbitMQ
    override val rpcService get() = super.rpcService as ServerRabbitRpcService

    fun registerRequestHandler(instance: Any) {
        connection.registerRequestHandler(instance)
    }

    /**
     * Registers a server-side implementation for the given RPC service interface.
     *
     * The service interface must be annotated with `@RpcService` and must have been
     * processed by the Surf RabbitMQ KSP processor. After registration, incoming RPC
     * requests for this service contract are routed to [serviceInstance].
     *
     * Register services before calling `freeze()` or `freezeAndConnect()`, because
     * RabbitMQ handler registration is finalized when the API is frozen.
     *
     * Example:
     *
     * ```kotlin
     * @RpcService
     * interface UserService {
     *     suspend fun findUserName(userId: UUID): String?
     * }
     *
     * class UserServiceImpl : UserService {
     *     override suspend fun findUserName(userId: UUID): String? {
     *         return userRepository.findName(userId)
     *     }
     * }
     *
     * serverApi.registerRpcService(UserService::class, UserServiceImpl())
     * serverApi.freezeAndConnect()
     * ```
     *
     * Clients can then create and use the matching proxy:
     *
     * ```kotlin
     * val userService = clientApi.createRpcService<UserService>()
     * val name = userService.findUserName(userId)
     * ```
     *
     * @param serviceKClass the RPC service interface class.
     * @param serviceInstance the implementation that should handle incoming calls.
     * @throws IllegalStateException if no generated descriptor for [serviceKClass] can be found.
     * @throws IllegalArgumentException if a service with the same descriptor is already registered.
     */
    fun <Service : Any> registerRpcService(serviceKClass: KClass<Service>, serviceInstance: Service) {
        rpcService.registerService(serviceKClass, serviceInstance)
    }

    /**
     * Registers a server-side implementation for the reified RPC service interface.
     *
     * This is the preferred overload for normal Kotlin usage. The [Service] type
     * must be an interface annotated with `@RpcService`, and [serviceInstance] must
     * implement that interface.
     *
     * Example:
     *
     * ```kotlin
     * serverApi.registerRpcService<UserService>(UserServiceImpl())
     * ```
     *
     * @param Service the RPC service interface type.
     * @param serviceInstance the implementation that should handle incoming calls.
     * @throws IllegalStateException if no generated descriptor for [Service] can be found.
     * @throws IllegalArgumentException if a service with the same descriptor is already registered.
     */
    inline fun <reified Service : Any> registerRpcService(serviceInstance: Service) {
        registerRpcService(Service::class, serviceInstance)
    }

    /**
     * Unregisters the server-side implementation for the given RPC service interface.
     *
     * After this method returns, incoming RPC requests for the service are no longer
     * routed to the previously registered implementation. Calls that arrive after
     * unregistration fail because no server-side service is available for the
     * service descriptor.
     *
     * Use this when a service implementation is unloaded, replaced, or no longer
     * intended to receive RPC calls.
     *
     * Example:
     *
     * ```kotlin
     * serverApi.unregisterRpcService(UserService::class)
     * ```
     *
     * @param serviceKClass the RPC service interface class.
     * @throws IllegalStateException if no generated descriptor for [serviceKClass] can be found.
     */
    fun <Service : Any> unregisterRpcService(serviceKClass: KClass<Service>) {
        rpcService.unregisterService(serviceKClass)
    }

    /**
     * Unregisters the server-side implementation for the reified RPC service interface.
     *
     * This is the preferred overload for normal Kotlin usage.
     *
     * Example:
     *
     * ```kotlin
     * serverApi.unregisterRpcService<UserService>()
     * ```
     *
     * @param Service the RPC service interface type.
     * @throws IllegalStateException if no generated descriptor for [Service] can be found.
     */
    inline fun <reified Service : Any> unregisterRpcService() {
        unregisterRpcService(Service::class)
    }

    override suspend fun connect() {
        StandaloneLifecycleHook.beforeConnect()
        super.connect()
    }

    override suspend fun disconnect() {
        super.disconnect()
        StandaloneLifecycleHook.afterDisconnect()
    }

    companion object {
        fun create(
            pluginName: String,
            path: Path,
            serializer: SerializersModule = EmptySerializersModule()
        ): ServerRabbitMQApi {
            StandaloneLifecycleHook.onInit()

            val config = RabbitMQConfig.create(path)
            val cbor = createCbor(serializer)

            return ServerRabbitMQApi(config, pluginName, cbor)
        }
    }
}