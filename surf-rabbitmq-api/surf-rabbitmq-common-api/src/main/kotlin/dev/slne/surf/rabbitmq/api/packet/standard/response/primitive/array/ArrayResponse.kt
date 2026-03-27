package dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

@Serializable
abstract class ArrayResponse<A>(
    private val arrayInitializer: () -> Array<A>,

    @Transient
    open val value: Array<A> = arrayInitializer()
) : RabbitResponsePacket(), Iterable<A> {
    companion object {
        val SERIALIZER_MODULE = SerializersModule {
            contextual(BooleanArrayResponsePacket.serializer())
            contextual(ByteArrayResponsePacket.serializer())
            contextual(CharArrayResponsePacket.serializer())
            contextual(DoubleArrayResponsePacket.serializer())
            contextual(FloatArrayResponsePacket.serializer())
            contextual(IntArrayResponsePacket.serializer())
            contextual(LongArrayResponsePacket.serializer())
            contextual(ShortArrayResponsePacket.serializer())
        }
    }

    override fun iterator() = value.iterator()

    @Serializable
    open class BooleanArrayResponsePacket(
        override val value: Array<Boolean>
    ) : ArrayResponse<Boolean>({ emptyArray() }, value)

    @Serializable
    open class ByteArrayResponsePacket(
        override val value: Array<Byte>
    ) : ArrayResponse<Byte>({ emptyArray() }, value)

    @Serializable
    open class CharArrayResponsePacket(
        override val value: Array<Char>
    ) : ArrayResponse<Char>({ emptyArray() }, value)

    @Serializable
    open class DoubleArrayResponsePacket(
        override val value: Array<Double>
    ) : ArrayResponse<Double>({ emptyArray() }, value)

    @Serializable
    open class FloatArrayResponsePacket(
        override val value: Array<Float>
    ) : ArrayResponse<Float>({ emptyArray() }, value)

    @Serializable
    open class IntArrayResponsePacket(
        override val value: Array<Int>
    ) : ArrayResponse<Int>({ emptyArray() }, value)

    @Serializable
    open class LongArrayResponsePacket(
        override val value: Array<Long>
    ) : ArrayResponse<Long>({ emptyArray() }, value)

    @Serializable
    open class ShortArrayResponsePacket(
        override val value: Array<Short>
    ) : ArrayResponse<Short>({ emptyArray() }, value)
}