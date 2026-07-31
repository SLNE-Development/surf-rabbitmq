package dev.slne.surf.eventbus.rabbitmq.listener

import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket

fun interface RabbitListenerHandler {
    suspend fun handle(message: RabbitRequestPacket<*>)
}
