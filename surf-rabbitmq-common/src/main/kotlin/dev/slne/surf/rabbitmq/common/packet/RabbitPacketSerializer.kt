package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.exception.*
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerCache
import dev.slne.surf.rabbitmq.common.util.KotlinSerializerNameCache
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer

/**
 * Serializes and deserializes RabbitMQ request/response packets using a compact binary wire format.
 *
 * **Request wire format:**
 * ```
 * [4 bytes: protocolVersion] [2 bytes: classNameLength] [N bytes: className (UTF-8)] [remaining: CBOR payload]
 * ```
 *
 * **Response wire format:**
 * ```
 * [2 bytes: classNameLength] [N bytes: className (UTF-8)] [remaining: CBOR payload]
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
object RabbitPacketSerializer {

    // region Request serialization
    fun serializeRequest(
        api: RabbitMQApi,
        serializer: KSerializer<RabbitRequestPacket<*>>,
        request: RabbitRequestPacket<*>
    ): ByteArray {
        val classNameBytes = request.javaClass.name.encodeToByteArray()
        val payloadBytes = wrapSerializationErrors { api.cbor.encodeToByteArray(serializer, request) }

        return writeFrame(Int.SIZE_BYTES + Short.SIZE_BYTES + classNameBytes.size + payloadBytes.size) { buf ->
            buf.writeInt(api.protocolVersion)
            buf.writeShort(classNameBytes.size)
            buf.writeBytes(classNameBytes)
            buf.writeBytes(payloadBytes)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun deserializeRequest(
        api: RabbitMQApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitRequestPacket<*>>,
    ): RabbitRequestPacket<*> {
        val buf = Unpooled.wrappedBuffer(data)

        return try {
            val version = buf.readInt()
            if (version != api.protocolVersion) {
                throw SurfRabbitProtocolVersionMismatchException(api.protocolVersion, version)
            }

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
    // endregion

    // region Response serialization

    @Suppress("UNCHECKED_CAST")
    fun serializeResponse(
        api: RabbitMQApi,
        serializerCache: KotlinSerializerCache<RabbitResponsePacket>,
        responsePacket: RabbitResponsePacket
    ): ByteArray {
        val serializer = serializerCache.get(responsePacket.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(responsePacket.javaClass.name)

        val classNameBytes = responsePacket.javaClass.name.encodeToByteArray()
        val payloadBytes = wrapSerializationErrors { api.cbor.encodeToByteArray(serializer, responsePacket) }

        return writeFrame(Short.SIZE_BYTES + classNameBytes.size + payloadBytes.size) { buf ->
            buf.writeShort(classNameBytes.size)
            buf.writeBytes(classNameBytes)
            buf.writeBytes(payloadBytes)
        }
    }

    fun deserializeResponse(
        api: RabbitMQApi,
        data: ByteArray,
        serializerCache: KotlinSerializerNameCache<RabbitResponsePacket>
    ): RabbitResponsePacket {
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
    // endregion

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
        val bytes = ByteArray(length)
        buf.readBytes(bytes)
        return bytes.decodeToString()
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