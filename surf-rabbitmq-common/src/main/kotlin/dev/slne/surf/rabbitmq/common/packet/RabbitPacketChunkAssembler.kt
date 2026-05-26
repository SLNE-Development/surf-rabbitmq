package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.exception.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RabbitPacketChunkAssembler(
    private val expectedKind: RabbitPacketChunking.PacketChunkKind,
    timeout: Duration
) {
    private val timeoutNanos = timeout.inWholeNanoseconds.coerceAtLeast(1L)

    private val cleanupIntervalNanos = minOf(timeoutNanos, 1.seconds.inWholeNanoseconds)
    private val lastCleanupNanos = AtomicLong(System.nanoTime())

    private val partialPackets = ConcurrentHashMap<String, PartialPacket>()

    fun accept(
        correlationId: String,
        body: ByteArray
    ): ChunkAcceptResult {
        val now = System.nanoTime()

        cleanupExpiredIfDue(now)

        val chunk = RabbitPacketChunking.decodeOrNull(body) ?: return ChunkAcceptResult.NotChunk

        if (chunk.kind != expectedKind) {
            throw SurfRabbitProtocolChunkKindMismatchException(expectedKind.name, chunk.kind.name)
        }

        val partial = getOrCreatePartial(
            correlationId = correlationId,
            chunk = chunk,
            now = now
        )

        validateMetadata(
            correlationId = correlationId,
            partial = partial,
            chunk = chunk
        )

        partial.add(chunk)

        if (!partial.isComplete()) {
            return ChunkAcceptResult.Stored
        }

        /**
         * Multiple duplicate chunks can arrive concurrently. Only one caller is allowed
         * to observe and publish the final Complete result.
         */
        if (!partial.markCompleted()) {
            return ChunkAcceptResult.Stored
        }

        partialPackets.remove(correlationId, partial)

        return ChunkAcceptResult.Complete(partial.assemble())
    }

    fun discard(correlationId: String) {
        partialPackets.remove(correlationId)
    }

    @Suppress("FoldInitializerAndIfToElvis")
    private fun getOrCreatePartial(
        correlationId: String,
        chunk: PacketChunk,
        now: Long
    ): PartialPacket {
        while (true) {
            val existing = partialPackets[correlationId]

            if (existing != null) {
                if (existing.isExpired(now, timeoutNanos)) {
                    partialPackets.remove(correlationId, existing)
                    continue
                }

                return existing
            }

            val created = PartialPacket(
                createdAtNanos = now,
                totalChunks = chunk.totalChunks,
                originalSize = chunk.originalSize
            )

            val previous = partialPackets.putIfAbsent(correlationId, created)
            if (previous == null) {
                return created
            }
        }
    }

    private fun validateMetadata(
        correlationId: String,
        partial: PartialPacket,
        chunk: PacketChunk
    ) {
        if (partial.totalChunks != chunk.totalChunks) {
            throw SurfRabbitProtocolChunkMetadataMismatchException(
                correlationId = correlationId,
                field = "totalChunks",
                expected = partial.totalChunks,
                actual = chunk.totalChunks
            )
        }

        if (partial.originalSize != chunk.originalSize) {
            throw SurfRabbitProtocolChunkMetadataMismatchException(
                correlationId = correlationId,
                field = "originalSize",
                expected = partial.originalSize,
                actual = chunk.originalSize
            )
        }
    }

    private fun cleanupExpiredIfDue(now: Long) {
        val previousCleanup = lastCleanupNanos.get()

        if (now - previousCleanup < cleanupIntervalNanos) {
            return
        }


        if (!lastCleanupNanos.compareAndSet(previousCleanup, now)) {
            return
        }

        cleanupExpired(now)
    }

    private fun cleanupExpired(now: Long) {
        partialPackets.entries.removeIf { (_, partial) ->
            partial.isExpired(now, timeoutNanos)
        }
    }


    private class PartialPacket(
        val createdAtNanos: Long,
        val totalChunks: Int,
        val originalSize: Int
    ) {
        private val chunks = AtomicReferenceArray<ByteArray?>(totalChunks)
        private val receivedChunks = AtomicInteger()
        private val completed = AtomicBoolean(false)

        fun isExpired(now: Long, timeoutNanos: Long): Boolean {
            return now - createdAtNanos > timeoutNanos
        }

        fun add(chunk: PacketChunk) {
            val stored = chunks.compareAndSet(
                chunk.chunkIndex,
                null,
                chunk.payload
            )

            if (stored) {
                receivedChunks.incrementAndGet()
            }
        }

        fun isComplete(): Boolean = receivedChunks.get() == totalChunks

        fun markCompleted(): Boolean {
            return completed.compareAndSet(false, true)
        }

        fun assemble(): ByteArray {
            val result = ByteArray(originalSize)
            var offset = 0

            for (i in 0 until totalChunks) {
                val chunk = chunks.get(i) ?: throw SurfRabbitProtocolMissingChunkException()

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