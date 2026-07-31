package dev.slne.surf.eventbus.rabbitmq.test.server.handler

import dev.slne.surf.eventbus.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.eventbus.rabbitmq.test.packet.DoNothingPacket
import dev.slne.surf.eventbus.rabbitmq.test.packet.DoNothingResponsePacket

object TestRabbitMqHandler {

    @RabbitHandler
    suspend fun handleDoNothing(packet: DoNothingPacket) {
        packet.respond(DoNothingResponsePacket())
    }
}