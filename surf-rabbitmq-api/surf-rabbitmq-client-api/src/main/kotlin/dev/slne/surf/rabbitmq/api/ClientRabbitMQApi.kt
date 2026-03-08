package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.connection.ClientRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
class ClientRabbitMQApi @InternalRabbitMQ constructor(
    config: RabbitMQConfig,
    pluginName: String,
    protocolVersion: Int,
    cbor: Cbor
) : RabbitMQApi(config, pluginName, protocolVersion, cbor) {
    override val connection get() = super.connection as ClientRabbitMQConnection

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R {
        return connection.sendRequest(request, responseClass)
    }

    suspend inline fun <reified R : RabbitResponsePacket> sendRequest(request: RabbitRequestPacket<R>): R {
        return sendRequest(request, R::class.java)
    }

    companion object {
        fun create(
            protocolVersion: Int,
            path: Path,
            pluginName: String,
            serializer: SerializersModule = EmptySerializersModule()
        ): ClientRabbitMQApi {
            val config = RabbitMQConfig.create(path)
            val cbor = createCbor(serializer)

            return ClientRabbitMQApi(config, pluginName, protocolVersion, cbor)
        }
    }
}