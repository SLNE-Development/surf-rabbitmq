package dev.slne.surf.eventbus.rabbitmq.api.packet.standard.response.optional

import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
open class OptionalStringResponsePacket(val value: String?) : RabbitResponsePacket()