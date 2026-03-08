package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket

interface ClientRabbitMQConnection : RabbitMQConnection {

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>
    ): R
}

suspend inline fun <reified R : RabbitResponsePacket> ClientRabbitMQConnection.sendRequest(request: RabbitRequestPacket<R>): R {
    return sendRequest(request, R::class.java)
}
