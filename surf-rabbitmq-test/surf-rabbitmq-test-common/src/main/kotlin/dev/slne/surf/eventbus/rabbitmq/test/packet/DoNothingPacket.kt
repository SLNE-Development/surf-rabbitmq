package dev.slne.surf.eventbus.rabbitmq.test.packet

import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
class DoNothingPacket : RabbitRequestPacket<DoNothingResponsePacket>()

@Serializable
class DoNothingResponsePacket : RabbitResponsePacket()