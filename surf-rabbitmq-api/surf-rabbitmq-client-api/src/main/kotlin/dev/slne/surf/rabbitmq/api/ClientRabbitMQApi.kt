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
import kotlin.jvm.Throws
import kotlin.reflect.KClass

@OptIn(ExperimentalSerializationApi::class)
class ClientRabbitMQApi @InternalRabbitMQ constructor(
    config: RabbitMQConfig,
    pluginName: String,
    cbor: Cbor
) : RabbitMQApi(config, pluginName, cbor) {
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

    inline fun <reified Service : Any> createRpcService(): Service {
        return rpcService.createService(Service::class)
    }

    fun <Service : Any> createRpcService(kClass: KClass<Service>): Service {
        return rpcService.createService(kClass)
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