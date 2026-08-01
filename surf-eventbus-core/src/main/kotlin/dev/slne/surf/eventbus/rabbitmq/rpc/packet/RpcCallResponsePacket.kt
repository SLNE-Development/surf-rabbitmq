package dev.slne.surf.eventbus.rabbitmq.rpc.packet

import dev.slne.surf.api.core.util.SerializableError
import dev.slne.surf.eventbus.rabbitmq.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.rpc.exception.SerializedException
import kotlinx.serialization.Serializable

@Serializable
data class RpcCallResponsePacket(
    val response: RpcCallResponse
) : RabbitResponsePacket() {

    @Serializable
    sealed interface RpcCallResponse {

        @Serializable
        data class Success(val data: ByteArray) : RpcCallResponse

        /**
         * @param cause legacy error representation, always present so clients older
         * than `1.6.0` keep working
         * @param serializedException full exception snapshot with the original stack
         * trace; `null` when the server predates `1.6.0` or the client did not
         * announce a version supporting it
         */
        @Serializable
        data class Error(
            val cause: SerializableError,
            val serializedException: SerializedException? = null
        ) : RpcCallResponse
    }
}