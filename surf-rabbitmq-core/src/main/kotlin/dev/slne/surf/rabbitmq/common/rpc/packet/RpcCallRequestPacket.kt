package dev.slne.surf.rabbitmq.common.rpc.packet

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import kotlinx.serialization.Serializable

@Serializable
data class RpcCallRequestPacket(
    val rpcCallId: String,
    val rpcServiceFqName: String,
    val rpcCallableName: String,
    val rpcServiceId: Long,
    val data: ByteArray
) : RabbitRequestPacket<RpcCallResponsePacket>() {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RpcCallRequestPacket) return false

        if (rpcServiceId != other.rpcServiceId) return false
        if (rpcCallId != other.rpcCallId) return false
        if (rpcServiceFqName != other.rpcServiceFqName) return false
        if (rpcCallableName != other.rpcCallableName) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = rpcServiceId.hashCode()
        result = 31 * result + rpcCallId.hashCode()
        result = 31 * result + rpcServiceFqName.hashCode()
        result = 31 * result + rpcCallableName.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
