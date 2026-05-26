package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.rpc.ClientRabbitRpcService
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class ClientRabbitMQApi @InternalRabbitMQ constructor(
    config: RabbitMQConfig,
    pluginName: String,
    cbor: Cbor
) : RabbitMQApi(config, pluginName, cbor) {

    @InternalRabbitMQ
    override val connection get() = super.connection as ClientRabbitMQConnection

    @InternalRabbitMQ
    override val rpcService get() = super.rpcService as ClientRabbitRpcService

    @Throws(SurfRabbitRequestTimeoutException::class)
    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R {
        return connection.sendRequest(request, responseClass)
    }

    @Throws(SurfRabbitRequestTimeoutException::class)
    suspend inline fun <reified R : RabbitResponsePacket> sendRequest(request: RabbitRequestPacket<R>): R {
        return sendRequest(request, R::class.java)
    }

    /**
     * Creates a client-side proxy for the given RPC service interface.
     *
     * The service interface must be annotated with `@RpcService` and must have been
     * processed by the Surf RabbitMQ KSP processor. The generated proxy implements
     * the service interface and sends every RPC method invocation as a RabbitMQ
     * request to a server that registered an implementation of the same service
     * contract.
     *
     * The returned instance should usually be created once and reused. Creating a
     * new proxy for every call is unnecessary and may add avoidable allocation
     * overhead.
     *
     * Example:
     *
     * ```kotlin
     * @RpcService
     * interface UserService {
     *     suspend fun findUserName(userId: UUID): String?
     * }
     *
     * val userService = clientApi.createRpcService<UserService>()
     * val name = userService.findUserName(userId)
     * ```
     *
     * The server must register a matching implementation before calls are made:
     *
     * ```kotlin
     * serverApi.registerRpcService<UserService>(UserServiceImpl())
     * ```
     *
     * @param Service the RPC service interface type.
     * @return a generated client proxy implementing [Service].
     * @throws IllegalStateException if no generated descriptor for [Service] can be found.
     */
    inline fun <reified Service : Any> createRpcService(): Service {
        return createRpcService(Service::class)
    }

    /**
     * Creates a client-side proxy for the given RPC service interface.
     *
     * Use this overload when the service type is only available as a [KClass], for
     * example when creating RPC services dynamically. For normal Kotlin call sites,
     * prefer the reified `createRpcService<Service>()` overload.
     *
     * The service class must refer to an interface annotated with `@RpcService`.
     * The generated descriptor and proxy must be present on the classpath.
     *
     * Example:
     *
     * ```kotlin
     * val userService = clientApi.createRpcService(UserService::class)
     * val name = userService.findUserName(userId)
     * ```
     *
     * @param serviceKClass the RPC service interface class.
     * @return a generated client proxy implementing [Service].
     * @throws IllegalStateException if no generated descriptor for [serviceKClass] can be found.
     */
    fun <Service : Any> createRpcService(serviceKClass: KClass<Service>): Service {
        return rpcService.createService(serviceKClass)
    }

    companion object {
        fun create(
            pluginName: String,
            path: Path,
            serializer: SerializersModule = EmptySerializersModule()
        ): ClientRabbitMQApi {
            val config = RabbitMQConfig.create(path)
            val cbor = createCbor(serializer)

            return ClientRabbitMQApi(config, pluginName, cbor)
        }
    }
}