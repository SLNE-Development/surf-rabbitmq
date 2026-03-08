package dev.slne.surf.rabbitmq.api.packet.default.response

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
data class IntResponsePacket(val value: Int) : RabbitResponsePacket()