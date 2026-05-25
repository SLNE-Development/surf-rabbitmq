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

    fun <Service : Any> registerRpcService(serviceKClass: KClass<Service>, serviceInstance: Service) {
        rpcService.registerService(serviceKClass, serviceInstance)
    }

    inline fun <reified Service : Any> registerRpcService(serviceInstance: Service) {
        registerRpcService(Service::class, serviceInstance)
    }

    fun <Service : Any> unregisterRpcService(serviceKClass: KClass<Service>) {
        rpcService.unregisterService(serviceKClass)
    }

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