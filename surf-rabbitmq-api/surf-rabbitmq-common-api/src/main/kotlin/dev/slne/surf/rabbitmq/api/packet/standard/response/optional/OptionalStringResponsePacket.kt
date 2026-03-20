package dev.slne.surf.rabbitmq.api.packet.standard.response.optional

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
open class OptionalStringResponsePacket(val value: String?) : RabbitResponsePacket()