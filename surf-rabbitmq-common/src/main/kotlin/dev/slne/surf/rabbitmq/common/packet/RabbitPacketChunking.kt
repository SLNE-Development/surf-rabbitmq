package dev.slne.surf.rabbitmq.common.packet

import java.nio.ByteBuffer

object RabbitPacketChunking {
    const val DEFAULT_MAX_CHUNK_SIZE_BYTES: Int = 128 * 1024

    private const val CHUNK_MAGIC = 0x53525546 // "SRUF"
    private const val CHUNK_VERSION: Byte = 1
    private const val CHUNK_HEADER_SIZE =
        Int.SIZE_BYTES + Byte.SIZE_BYTES + Int.SIZE_BYTES + Int.SIZE_BYTES

    data class DecodedChunk(
        val chunkIndex: Int,
        val totalChunks: Int,
        val payload: ByteArray,
    )

    fun chunk(payload: ByteArray, maxChunkSizeBytes: Int): List<ByteArray> {
        val payloadChunkSize = (maxChunkSizeBytes - CHUNK_HEADER_SIZE).coerceAtLeast(1)
        val totalChunks = ((payload.size + payloadChunkSize - 1) / payloadChunkSize).coerceAtLeast(1)

        return List(totalChunks) { chunkIndex ->
            val start = chunkIndex * payloadChunkSize
            val end = minOf(payload.size, start + payloadChunkSize)
            val chunkPayload = payload.copyOfRange(start, end)
            encodeChunk(chunkIndex, totalChunks, chunkPayload)
        }
    }

    fun decodeChunk(frame: ByteArray): DecodedChunk {
        if (frame.size < CHUNK_HEADER_SIZE) {
            return DecodedChunk(chunkIndex = 0, totalChunks = 1, payload = frame)
        }

        val buffer = ByteBuffer.wrap(frame)
        val magic = buffer.int
        if (magic != CHUNK_MAGIC) {
            return DecodedChunk(chunkIndex = 0, totalChunks = 1, payload = frame)
        }

        val version = buffer.get()
        if (version != CHUNK_VERSION) {
            throw IllegalArgumentException("Unsupported chunk version: $version")
        }

        val chunkIndex = buffer.int
        val totalChunks = buffer.int
        if (totalChunks <= 0 || chunkIndex !in 0 until totalChunks) {
            throw IllegalArgumentException("Invalid chunk metadata index=$chunkIndex total=$totalChunks")
        }

        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)

        return DecodedChunk(
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            payload = payload
        )
    }

    private fun encodeChunk(chunkIndex: Int, totalChunks: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(CHUNK_HEADER_SIZE + payload.size)
        buffer.putInt(CHUNK_MAGIC)
        buffer.put(CHUNK_VERSION)
        buffer.putInt(chunkIndex)
        buffer.putInt(totalChunks)
        buffer.put(payload)
        return buffer.array()
    }
}
