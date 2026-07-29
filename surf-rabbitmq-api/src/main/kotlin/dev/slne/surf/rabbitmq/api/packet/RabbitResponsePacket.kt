package dev.slne.surf.rabbitmq.api.packet

import kotlinx.serialization.Serializable

@Serializable
abstract class RabbitResponsePacket : RabbitPacket()