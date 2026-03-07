package dev.slne.surf.rabbitmq.wrapper.packet.packets

import dev.slne.surf.rabbitmq.wrapper.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.wrapper.packet.packets.response.StringResponsePacket
import kotlinx.serialization.Serializable

@Serializable
class TestRequestPacket : RabbitRequestPacket<StringResponsePacket>