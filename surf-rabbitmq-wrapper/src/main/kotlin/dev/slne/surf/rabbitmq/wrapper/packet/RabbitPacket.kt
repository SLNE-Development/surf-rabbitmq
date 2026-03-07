package dev.slne.surf.rabbitmq.wrapper.packet

interface RabbitPacket {
    suspend fun sendAndForget() {
        TODO("SEND")
    }
}