package dev.slne.surf.eventbus.rabbitmq.api.packet

import kotlinx.serialization.Serializable

@Serializable
abstract class RabbitResponsePacket : RabbitPacket()