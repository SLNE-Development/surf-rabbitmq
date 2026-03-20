package dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array

import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
abstract class OptionalArrayResponse<A>(
    private val arrayInitializer: () -> Array<A>,

    @Transient
    open val value: Array<A>? = arrayInitializer()
) : RabbitResponsePacket(), Iterable<A> {
    override fun iterator(): Iterator<A> = value?.iterator() ?: arrayInitializer().iterator()
    
    @Serializable
    open class OptionalBooleanArrayResponsePacket(
        override val value: Array<Boolean>?
    ) : OptionalArrayResponse<Boolean>({ emptyArray() }, value)

    @Serializable
    open class OptionalByteArrayResponsePacket(
        override val value: Array<Byte>?
    ) : OptionalArrayResponse<Byte>({ emptyArray() }, value)

    @Serializable
    open class OptionalCharArrayResponsePacket(
        override val value: Array<Char>?
    ) : OptionalArrayResponse<Char>({ emptyArray() }, value)

    @Serializable
    open class OptionalDoubleArrayResponsePacket(
        override val value: Array<Double>?
    ) : OptionalArrayResponse<Double>({ emptyArray() }, value)

    @Serializable
    open class OptionalFloatArrayResponsePacket(
        override val value: Array<Float>?
    ) : OptionalArrayResponse<Float>({ emptyArray() }, value)

    @Serializable
    open class OptionalIntArrayResponsePacket(
        override val value: Array<Int>?
    ) : OptionalArrayResponse<Int>({ emptyArray() }, value)

    @Serializable
    open class OptionalLongArrayResponsePacket(
        override val value: Array<Long>?
    ) : OptionalArrayResponse<Long>({ emptyArray() }, value)

    @Serializable
    open class OptionalShortArrayResponsePacket(
        override val value: Array<Short>?
    ) : OptionalArrayResponse<Short>({ emptyArray() }, value)
}