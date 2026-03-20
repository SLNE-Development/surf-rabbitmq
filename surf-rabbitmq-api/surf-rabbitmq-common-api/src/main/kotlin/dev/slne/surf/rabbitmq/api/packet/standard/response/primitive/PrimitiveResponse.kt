package dev.slne.surf.rabbitmq.api.packet.standard.response.primitive

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable

abstract class PrimitiveResponse : RabbitResponsePacket() {
    @Serializable
    open class BooleanResponsePacket(
        val value: Boolean
    ) : PrimitiveResponse()

    @Serializable
    open class ByteResponsePacket(
        val value: Byte
    ) : PrimitiveResponse()

    @Serializable
    open class CharResponsePacket(
        val value: Char
    ) : PrimitiveResponse()

    @Serializable
    open class DoubleResponsePacket(
        val value: Double
    ) : PrimitiveResponse()

    @Serializable
    open class FloatResponsePacket(
        val value: Float
    ) : PrimitiveResponse()

    @Serializable
    open class LongResponsePacket(
        val value: Long
    ) : PrimitiveResponse()

    @Serializable
    open class ShortResponsePacket(
        val value: Short
    ) : PrimitiveResponse()
}