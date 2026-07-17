package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitEnvelopeDeserializationException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitEnvelopeSerializationException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializationException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import dev.slne.surf.rabbitmq.api.packet.RabbitPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import io.netty.buffer.Unpooled
import io.netty.util.internal.PlatformDependent
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
        api: RabbitMQApi,
        serializerCache: KotlinSerializerCache<RabbitResponsePacket>,
        responsePacket: RabbitResponsePacket
    ): ByteArray {
        val serializer = serializerCache.get(responsePacket.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(responsePacket.javaClass.name)

        return serialize(api, serializer, responsePacket)
    }

    fun serializeRequest(
        api: RabbitMQApi,
        serializer: KSerializer<RabbitRequestPacket<*>>,
        request: RabbitRequestPacket<*>
    ): ByteArray {
        return serialize(api, serializer, request)
    }

    private fun <P : RabbitPacket> serialize(
        api: RabbitMQApi,
        serializer: KSerializer<P>,
        packet: P
    ): ByteArray {
        val classNameBytes = classNameBytesCache.get(packet.javaClass)
        val payloadBytes = wrapSerializationErrors {
            api.cbor.encodeToByteArray(serializer, packet)
        }

        return try {
            RabbitPacketEnvelopeCodec.encode(classNameBytes, payloadBytes)
        } catch (throwable: Throwable) {
            throwable.rethrowIfFatal()
            throw SurfRabbitEnvelopeSerializationException(throwable)
        }
    }

    private fun <R : RabbitPacket> deserialize(
        api: RabbitMQApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<R>
    ): R {
        return wrapDeserializationErrors {
            val envelope = RabbitPacketEnvelopeCodec.decode(data)
            val serializer = serializerCache.get(envelope.className)
                ?: throw SurfRabbitSerializerNotFoundException(envelope.className)

            api.cbor.decodeFromByteArray(serializer, envelope.payload)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun deserializeRequest(
        api: RabbitMQApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitRequestPacket<*>>,
    ): RabbitRequestPacket<*> {
        return deserialize(api, data, serializerCache)
    }

    fun deserializeResponse(
        api: RabbitMQApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitResponsePacket>
    ): RabbitResponsePacket {
        return deserialize(api, data, serializerCache)
    }

    private inline fun <T> wrapDeserializationErrors(block: () -> T): T =
        try {
            block()
        } catch (e: SurfRabbitSerializationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            throw SurfRabbitEnvelopeDeserializationException(e)
        }

    private inline fun <T> wrapSerializationErrors(block: () -> T): T =
        try {
            block()
        } catch (e: SurfRabbitSerializationException) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfFatal()
            throw SurfRabbitEnvelopeSerializationException(e)
        }
}

internal class RabbitPacketEnvelope(
    val className: String,
    val payload: ByteArray
)

internal object RabbitPacketEnvelopeCodec {
    fun encode(classNameBytes: ByteArray, payload: ByteArray): ByteArray {
        require(classNameBytes.isNotEmpty() && classNameBytes.size <= UShort.MAX_VALUE.toInt()) {
            "Packet class name length is outside 1..${UShort.MAX_VALUE}: ${classNameBytes.size}"
        }
        require(payload.isNotEmpty()) { "Packet envelope payload must not be empty" }

        val frameSize = Short.SIZE_BYTES.toLong() + classNameBytes.size + payload.size
        require(frameSize <= Int.MAX_VALUE) { "Serialized packet envelope is too large: $frameSize bytes" }

        val frame = PlatformDependent.allocateUninitializedArray(frameSize.toInt())
        Unpooled.buffer().writeShort(1)
        frame[0] = (classNameBytes.size ushr 8).toByte()
        frame[1] = classNameBytes.size.toByte()
        classNameBytes.copyInto(frame, destinationOffset = Short.SIZE_BYTES)
        payload.copyInto(frame, destinationOffset = Short.SIZE_BYTES + classNameBytes.size)
        return frame
    }

    fun decode(data: ByteArray): RabbitPacketEnvelope {
        require(data.size >= Short.SIZE_BYTES) { "Packet envelope is missing the class-name length" }

        val classNameLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        require(classNameLength > 0 && classNameLength <= data.size - Short.SIZE_BYTES) {
            "Invalid packet class-name length $classNameLength for ${data.size}-byte envelope"
        }

        val payloadOffset = Short.SIZE_BYTES + classNameLength
        require(payloadOffset < data.size) { "Packet envelope does not contain a payload" }

        return RabbitPacketEnvelope(
            className = data.decodeToString(
                startIndex = Short.SIZE_BYTES,
                endIndex = payloadOffset,
                throwOnInvalidSequence = true
            ),
            payload = data.copyOfRange(payloadOffset, data.size)
        )
    }
}
