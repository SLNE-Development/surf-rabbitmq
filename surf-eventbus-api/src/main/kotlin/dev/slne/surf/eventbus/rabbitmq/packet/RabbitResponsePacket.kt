package dev.slne.surf.eventbus.rabbitmq.packet

import kotlinx.serialization.Serializable

@Serializable
abstract class RabbitResponsePacket : RabbitPacket()