package dev.slne.surf.rabbitmq.listener

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket

fun interface RabbitListenerHandler {
    suspend fun handle(message: RabbitRequestPacket<*>)
}
