package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget

@InternalRabbitMQ
interface RabbitMQConnection {
    suspend fun connect()
    suspend fun disconnect()

    fun registerRequestHandler(instance: Any)

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R

    @InternalRabbitMQ
    companion object {
        fun create(api: SurfRabbitApi): RabbitMQConnection =
            RabbitMQConnectionFactory.createConnection(api)
    }
}
