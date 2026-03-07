package dev.slne.surf.rabbitmq.wrapper.packet

interface RabbitRequestPacket<ResponsePacket : RabbitResponsePacket> : RabbitPacket {
    suspend fun send(): ResponsePacket {
        TODO("SEND")
    }
}