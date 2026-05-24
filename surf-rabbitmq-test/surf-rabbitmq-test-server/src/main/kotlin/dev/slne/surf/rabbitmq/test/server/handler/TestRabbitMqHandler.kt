package dev.slne.surf.rabbitmq.test.server.handler

import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.test.packet.DoNothingPacket
import dev.slne.surf.rabbitmq.test.packet.DoNothingResponsePacket

object TestRabbitMqHandler {

    @RabbitHandler
    suspend fun handleDoNothing(packet: DoNothingPacket) {
        packet.respond(DoNothingResponsePacket())
    }
}