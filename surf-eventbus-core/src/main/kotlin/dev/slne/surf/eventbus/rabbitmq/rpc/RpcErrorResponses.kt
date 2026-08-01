package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.api.core.util.toSerializableError
import dev.slne.surf.eventbus.rabbitmq.version.RabbitMqVersion
import dev.slne.surf.eventbus.rabbitmq.rpc.exception.SerializedException
import dev.slne.surf.eventbus.rabbitmq.rpc.packet.RpcCallResponsePacket

/**
 * Clients from this version on understand [RpcCallResponsePacket.RpcCallResponse.Error.serializedException].
 */
private val SERIALIZED_EXCEPTION_SINCE = RabbitMqVersion(1, 6, 0)

/**
 * Builds an RPC error response for [cause]. The full [SerializedException] snapshot is
 * only included for clients that announced a version supporting it; older clients keep
 * receiving just the legacy [toSerializableError] representation.
 */
internal fun rpcErrorResponse(cause: Throwable, clientVersion: RabbitMqVersion): RpcCallResponsePacket {
    val serializedException = if (clientVersion.isAtLeast(SERIALIZED_EXCEPTION_SINCE)) {
        SerializedException.from(cause)
    } else {
        null
    }

    return RpcCallResponsePacket(
        RpcCallResponsePacket.RpcCallResponse.Error(
            cause = cause.toSerializableError(),
            serializedException = serializedException
        )
    )
}
