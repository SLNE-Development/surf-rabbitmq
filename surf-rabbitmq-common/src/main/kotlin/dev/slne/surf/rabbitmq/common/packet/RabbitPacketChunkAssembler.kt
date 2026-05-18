package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolChunkKindMismatchException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolChunkPacketLargerThanExpectedException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolChunkPacketSizeMismatchException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitProtocolMissingChunkException
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import kotlin.time.Duration

class RabbitPacketChunkAssembler(
    private val expectedKind: RabbitPacketChunking.PacketChunkKind,
    private val timeout: Duration
) {
    private val partialPackets = Object2ObjectOpenHashMap<String, PartialPacket>()

    @Synchronized
    fun accept(
        correlationId: String,
        body: ByteArray
    ): ChunkAcceptResult {
        cleanupExpired()

        val chunk = RabbitPacketChunking.decodeOrNull(body) ?: return ChunkAcceptResult.NotChunk

        if (chunk.kind != expectedKind) {
            throw SurfRabbitProtocolChunkKindMismatchException(expectedKind.name, chunk.kind.name)
        }

        val partial = partialPackets.computeIfAbsent(correlationId) {
            PartialPacket(
                createdAtNanos = System.nanoTime(),
                totalChunks = chunk.totalChunks,
                originalSize = chunk.originalSize
            )
        }

        require(partial.totalChunks == chunk.totalChunks) {
            "Mismatching totalChunks for correlationId $correlationId"
        }
        require(partial.originalSize == chunk.originalSize) {
            "Mismatching originalSize for correlationId $correlationId"
        }

        partial.add(chunk)

        if (!partial.isComplete()) {
            return ChunkAcceptResult.Stored
        }

        partialPackets.remove(correlationId)

        return ChunkAcceptResult.Complete(partial.assemble())
    }

    @Synchronized
    fun discard(correlationId: String) {
        partialPackets.remove(correlationId)
    }

    @Synchronized
    private fun cleanupExpired() {
        val now = System.nanoTime()
        val timeoutNanos = timeout.inWholeNanoseconds

        partialPackets.entries.removeIf { (_, partial) ->
            now - partial.createdAtNanos > timeoutNanos
        }
    }

    private class PartialPacket(
        val createdAtNanos: Long,
        val totalChunks: Int,
        val originalSize: Int
    ) {
        private val chunks = arrayOfNulls<ByteArray>(totalChunks)
        private var receivedChunks = 0

        fun add(chunk: PacketChunk) {
            if (chunks[chunk.chunkIndex] != null) return

            chunks[chunk.chunkIndex] = chunk.payload
            receivedChunks++
        }

        fun isComplete(): Boolean = receivedChunks == totalChunks

        fun assemble(): ByteArray {
            val result = ByteArray(originalSize)
            var offset = 0

            for (chunk in chunks) {
                if (chunk == null) {
                    throw SurfRabbitProtocolMissingChunkException()
                }

                if (offset + chunk.size > result.size) {
                    throw SurfRabbitProtocolChunkPacketLargerThanExpectedException(
                        result.size,
                        offset + chunk.size,
                        offset,
                        chunk.size
                    )
                }

                chunk.copyInto(result, destinationOffset = offset)
                offset += chunk.size
            }

            if (offset != originalSize) {
                throw SurfRabbitProtocolChunkPacketSizeMismatchException(originalSize, offset)
            }

            return result
        }
    }

    sealed interface ChunkAcceptResult {
        data object NotChunk : ChunkAcceptResult
        data object Stored : ChunkAcceptResult
        data class Complete(val body: ByteArray) : ChunkAcceptResult {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Complete) return false

                if (!body.contentEquals(other.body)) return false

                return true
            }

            override fun hashCode(): Int {
                return body.contentHashCode()
            }
        }
    }
}