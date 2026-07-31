package dev.slne.surf.eventbus.rabbitmq.api.connection

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget

@InternalRabbitMQ
interface RabbitMQConnection {
    suspend fun connect()
    suspend fun disconnect()

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R

    suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget)

    @InternalRabbitMQ
    companion object {
        fun create(api: SurfRabbitApi): RabbitMQConnection =
            RabbitMQConnectionFactory.createConnection(api)
    }
}
