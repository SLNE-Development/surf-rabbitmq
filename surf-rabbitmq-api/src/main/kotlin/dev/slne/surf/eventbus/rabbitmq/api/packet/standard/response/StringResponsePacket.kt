package dev.slne.surf.eventbus.rabbitmq.api.packet.standard.response

import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
open class StringResponsePacket(val value: String) : RabbitResponsePacket()