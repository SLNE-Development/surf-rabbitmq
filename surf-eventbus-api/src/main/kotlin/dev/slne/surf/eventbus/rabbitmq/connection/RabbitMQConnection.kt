package dev.slne.surf.eventbus.rabbitmq.connection

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget

@InternalEventBusApi
interface RabbitMQConnection {
    suspend fun connect()
    suspend fun disconnect()

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R

    suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget)

    @InternalEventBusApi
    companion object {
        fun create(api: SurfRabbitApi): RabbitMQConnection =
            RabbitMQConnectionFactory.createConnection(api)
    }
}
