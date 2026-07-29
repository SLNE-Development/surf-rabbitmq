package dev.slne.surf.rabbitmq.api.packet.standard.response.primitive

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

abstract class OptionalPrimitiveResponse : RabbitResponsePacket() {
    companion object {
        val SERIALIZER_MODULE = SerializersModule {
            contextual(OptionalBooleanResponsePacket.serializer())
            contextual(OptionalByteResponsePacket.serializer())
            contextual(OptionalCharResponsePacket.serializer())
            contextual(OptionalDoubleResponsePacket.serializer())
            contextual(OptionalFloatResponsePacket.serializer())
            contextual(OptionalLongResponsePacket.serializer())
            contextual(OptionalShortResponsePacket.serializer())
        }
    }

    @Serializable
    open class OptionalBooleanResponsePacket(
        val value: Boolean?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalByteResponsePacket(
        val value: Byte?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalCharResponsePacket(
        val value: Char?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalDoubleResponsePacket(
        val value: Double?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalFloatResponsePacket(
        val value: Float?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalLongResponsePacket(
        val value: Long?
    ) : OptionalPrimitiveResponse()

    @Serializable
    open class OptionalShortResponsePacket(
        val value: Short?
    ) : OptionalPrimitiveResponse()
}