package dev.slne.surf.rabbitmq.common.rpc.packet

import dev.slne.surf.api.core.util.SerializableError
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

@Serializable
data class RpcCallResponsePacket(
    val response: RpcCallResponse
) : RabbitResponsePacket() {

    @Serializable
    sealed interface RpcCallResponse {

        @Serializable
        data class Success(val data: ByteArray) : RpcCallResponse

        @Serializable
        data class Error(val cause: SerializableError) : RpcCallResponse
    }
}