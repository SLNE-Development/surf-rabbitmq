package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.connection.ServerRabbitMQConnection
import dev.slne.surf.rabbitmq.api.internal.config.RabbitMQConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

@OptIn(ExperimentalSerializationApi::class)
class ServerRabbitMQApi @InternalRabbitMQ constructor(
    config: RabbitMQConfig,
    pluginName: String,
    protocolVersion: Int,
    cbor: Cbor
) : RabbitMQApi(config, pluginName, protocolVersion, cbor) {
    override val connection get() = super.connection as ServerRabbitMQConnection

    fun registerRequestHandler(instance: Any) {
        connection.registerRequestHandler(instance)
    }

    companion object {
        fun create(
            protocolVersion: Int,
            path: Path,
            serializer: SerializersModule = EmptySerializersModule()
        ): ServerRabbitMQApi {
            val config = RabbitMQConfig.create(path)
            val cbor = createCbor(serializer)
            val pluginName = "EXTRACT ME"

            return ServerRabbitMQApi(config, pluginName, protocolVersion, cbor)
        }
    }
}