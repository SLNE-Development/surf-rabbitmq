package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolInvalidChunkMetadataException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolUnknownChunkKindException
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking.PACKET_CHUNKING_THRESHOLD_BYTES
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking.PACKET_CHUNK_SIZE_BYTES
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.objects.ObjectArrayList

object RabbitPacketChunking {
    private const val MAGIC = 0x5352_4348 // "SRCH"
    private const val VERSION: Byte = 1

    private const val KIND_REQUEST: Byte = 1
    private const val KIND_RESPONSE: Byte = 2

    private const val CHUNK_CAPABILITY_PREFIX = "srq1:"

    /**
     * Maximum size of a single packet chunk in bytes.
     *
     * Packets larger than [PACKET_CHUNKING_THRESHOLD_BYTES] are split into chunks of
     * at most this size before being sent through RabbitMQ.
     *
     * The default value is `512 KiB`, which keeps individual RabbitMQ messages
     * reasonably small while avoiding too much overhead from creating many chunks.
     */
    const val PACKET_CHUNK_SIZE_BYTES = 512 * 1024

    /**
     * Minimum packet size in bytes at which automatic chunking is enabled.
     *
     * Packets smaller than or equal to this value are sent as a single RabbitMQ
     * message. Packets larger than this value are split into multiple chunks using
     * [PACKET_CHUNK_SIZE_BYTES].
     *
     * The default value is `1 MiB`, so small and medium-sized packets avoid the
     * additional overhead of chunk metadata and reassembly.
     */
    const val PACKET_CHUNKING_THRESHOLD_BYTES = 1024 * 1024

    fun newCorrelationId(rawCorrelationId: String): String = CHUNK_CAPABILITY_PREFIX + rawCorrelationId
    fun supportsChunkedResponses(correlationId: String): Boolean = correlationId.startsWith(CHUNK_CAPABILITY_PREFIX)

    fun shouldChunk(data: ByteArray, config: RabbitMQConfig): Boolean {
        return config.isOutgoingPacketChunkingEnabled() && data.size > PACKET_CHUNKING_THRESHOLD_BYTES
    }

    fun splitRequest(data: ByteArray): ObjectArrayList<ByteArray> = split(data, PacketChunkKind.REQUEST)
    fun splitResponse(data: ByteArray): ObjectArrayList<ByteArray> = split(data, PacketChunkKind.RESPONSE)

    fun decodeOrNull(data: ByteArray): PacketChunk? {
        if (data.size < Int.SIZE_BYTES + 1 + 1 + Int.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES) { // see encodeChunk for details
            return null
        }

        val buf = Unpooled.wrappedBuffer(data)
        try {
            if (buf.readInt() != MAGIC) return null

            val version = buf.readByte()
            if (version != VERSION) return null

            val kind = when (val rawKind = buf.readByte()) {
                KIND_REQUEST -> PacketChunkKind.REQUEST
                KIND_RESPONSE -> PacketChunkKind.RESPONSE
                else -> throw SurfRabbitProtocolUnknownChunkKindException(rawKind)
            }

            val totalChunks = buf.readInt()
            val chunkIndex = buf.readInt()
            val originalSize = buf.readInt()

            if (totalChunks <= 0) {
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "totalChunks",
                    expected = "> 0",
                    actual = totalChunks
                )
            }

            if (chunkIndex !in 0 until totalChunks) {
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "chunkIndex",
                    expected = "0 until $totalChunks",
                    actual = chunkIndex
                )
            }

            if (originalSize < 0) {
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "originalSize",
                    expected = ">= 0",
                    actual = originalSize
                )
            }

            val payloadSize = buf.readableBytes()
            if (payloadSize > PACKET_CHUNK_SIZE_BYTES) {
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "payloadSize",
                    expected = "<= $PACKET_CHUNK_SIZE_BYTES",
                    actual = payloadSize
                )
            }

            val payload = ByteArray(payloadSize)
            buf.readBytes(payload)

            return PacketChunk(
                kind = kind,
                totalChunks = totalChunks,
                chunkIndex = chunkIndex,
                originalSize = originalSize,
                payload = payload
            )
        } finally {
            buf.release()
        }
    }

    private fun split(
        data: ByteArray,
        kind: PacketChunkKind,
    ): ObjectArrayList<ByteArray> {
        val totalChunks = (data.size + PACKET_CHUNK_SIZE_BYTES - 1) / PACKET_CHUNK_SIZE_BYTES
        val chunks = ObjectArrayList<ByteArray>(totalChunks)

        var offset = 0
        var index = 0

        while (offset < data.size) {
            val length = minOf(PACKET_CHUNK_SIZE_BYTES, data.size - offset)
            chunks.add(
                encodeChunk(
                    kind = kind,
                    totalChunks = totalChunks,
                    chunkIndex = index,
                    originalSize = data.size,
                    payload = data,
                    payloadOffset = offset,
                    payloadLength = length,
                )
            )

            offset += length
            index++
        }

        return chunks
    }

    private fun encodeChunk(
        kind: PacketChunkKind,
        totalChunks: Int,
        chunkIndex: Int,
        originalSize: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int
    ): ByteArray {
        val exactSize = // remember to update decodeOrNull if changing this
            Int.SIZE_BYTES + // magic
                    1 + // version
                    1 + // kind
                    Int.SIZE_BYTES + // totalChunks
                    Int.SIZE_BYTES + // chunkIndex
                    Int.SIZE_BYTES + // originalSize
                    payloadLength

        val buf = Unpooled.buffer(exactSize, exactSize)
        try {
            buf.writeInt(MAGIC)
            buf.writeByte(VERSION.toInt())
            buf.writeByte(
                when (kind) {
                    PacketChunkKind.REQUEST -> KIND_REQUEST.toInt()
                    PacketChunkKind.RESPONSE -> KIND_RESPONSE.toInt()
                }
            )
            buf.writeInt(totalChunks)
            buf.writeInt(chunkIndex)
            buf.writeInt(originalSize)
            buf.writeBytes(payload, payloadOffset, payloadLength)

            return buf.array()
        } finally {
            buf.release()
        }
    }

    enum class PacketChunkKind {
        REQUEST,
        RESPONSE
    }
}

data class PacketChunk(
    val kind: RabbitPacketChunking.PacketChunkKind,
    val totalChunks: Int,
    val chunkIndex: Int,
    val originalSize: Int,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketChunk) return false

        if (totalChunks != other.totalChunks) return false
        if (chunkIndex != other.chunkIndex) return false
        if (originalSize != other.originalSize) return false
        if (kind != other.kind) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = totalChunks
        result = 31 * result + chunkIndex
        result = 31 * result + originalSize
        result = 31 * result + kind.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}