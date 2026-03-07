package dev.slne.surf.rabbitmq.wrapper.packet.packets.response

import dev.slne.surf.rabbitmq.wrapper.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
data class IntResponsePacket(
    val data: Int
) : RabbitResponsePacket