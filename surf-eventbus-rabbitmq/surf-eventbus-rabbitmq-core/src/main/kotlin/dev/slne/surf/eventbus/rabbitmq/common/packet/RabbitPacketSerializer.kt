package dev.slne.surf.eventbus.rabbitmq.common.packet

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitEnvelopeDeserializationException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitEnvelopeSerializationException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitSerializationException
import dev.slne.surf.eventbus.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.eventbus.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.eventbus.rabbitmq.shared.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.rabbitmq.shared.serialization.KotlinSerializerNameCache
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer

/**
 * Serializes and deserializes RabbitMQ request/response packets using a compact binary wire format.
 *
 * **Request and response wire format:**
 * ```
 * [2 bytes: classNameLength] [N bytes: className (UTF-8)] [remaining: CBOR payload]
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
object RabbitPacketSerializer {
    private val classNameBytesCache = object : ClassValue<ByteArray>() {
        override fun computeValue(type: Class<*>): ByteArray = type.name.encodeToByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    fun serializeResponse(
        api: SurfRabbitApi,
        serializerCache: KotlinSerializerCache<RabbitResponsePacket>,
        responsePacket: RabbitResponsePacket
    ): ByteArray {
        val serializer = serializerCache.get(responsePacket.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(responsePacket.javaClass.name)

        return serialize(api, serializer, responsePacket)
    }

    fun serializeRequest(
        api: SurfRabbitApi,
        serializer: KSerializer<RabbitRequestPacket<*>>,
        request: RabbitRequestPacket<*>
    ): ByteArray {
        return serialize(api, serializer, request)
    }

    fun serializeEvent(
        api: SurfRabbitApi,
        serializer: KSerializer<RabbitEventPacket>,
        event: RabbitEventPacket
    ): ByteArray {
        return serialize(api, serializer, event)
    }

    private fun <P : RabbitPacket> serialize(
        api: SurfRabbitApi,
        serializer: KSerializer<P>,
        packet: P
    ): ByteArray {
        val classNameBytes = classNameBytesCache.get(packet.javaClass)
        val payloadBytes = wrapSerializationErrors {
            api.cbor.encodeToByteArray(serializer, packet)
        }

        return writeFrame(Short.SIZE_BYTES + classNameBytes.size + payloadBytes.size) { buf ->
            buf.writeShort(classNameBytes.size)
            buf.writeBytes(classNameBytes)
            buf.writeBytes(payloadBytes)
        }
    }

    private fun <R : RabbitPacket> deserialize(
        api: SurfRabbitApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<R>
    ): R {
        val buf = Unpooled.wrappedBuffer(data)

        return try {
            val className = readClassName(buf)
            val serializer = serializerCache.get(className)
                ?: throw SurfRabbitSerializerNotFoundException(className)

            wrapDeserializationErrors {
                api.cbor.decodeFromByteArray(serializer, readRemainingBytes(buf, data))
            }
        } finally {
            buf.release()
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun deserializeRequest(
        api: SurfRabbitApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitRequestPacket<*>>,
    ): RabbitRequestPacket<*> {
        return deserialize(api, data, serializerCache)
    }

    fun deserializeResponse(
        api: SurfRabbitApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitResponsePacket>
    ): RabbitResponsePacket {
        return deserialize(api, data, serializerCache)
    }

    fun deserializeEvent(
        api: SurfRabbitApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitEventPacket>
    ): RabbitEventPacket {
        return deserialize(api, data, serializerCache)
    }

    private inline fun writeFrame(exactSize: Int, write: (ByteBuf) -> Unit): ByteArray {
        val buf = Unpooled.buffer(exactSize, exactSize)

        try {
            write(buf)
            return buf.array()
        } finally {
            buf.release()
        }
    }

    private fun readClassName(buf: ByteBuf): String {
        val length = buf.readUnsignedShort()
        val className = buf.toString(buf.readerIndex(), length, Charsets.UTF_8)
        buf.skipBytes(length)
        return className
    }

    private fun readRemainingBytes(buf: ByteBuf, source: ByteArray): ByteArray {
        val offset = buf.readerIndex()
        val length = buf.readableBytes()
        return source.copyOfRange(offset, offset + length)
    }

    private inline fun <T> wrapDeserializationErrors(block: () -> T): T =
        try {
            block()
        } catch (e: SurfRabbitSerializationException) {
            throw e
        } catch (e: Throwable) {
            throw SurfRabbitEnvelopeDeserializationException(e)
        }

    private inline fun <T> wrapSerializationErrors(block: () -> T): T =
        try {
            block()
        } catch (e: SurfRabbitSerializationException) {
            throw e
        } catch (e: Throwable) {
            throw SurfRabbitEnvelopeSerializationException(e)
        }
}