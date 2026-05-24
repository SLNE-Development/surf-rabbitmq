package dev.slne.surf.rabbitmq.test.packet

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
class DoNothingPacket : RabbitRequestPacket<DoNothingResponsePacket>()

@Serializable
class DoNothingResponsePacket : RabbitResponsePacket()