package dev.slne.surf.rabbitmq.common.packet

import dev.slne.surf.rabbitmq.api.exception.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class RabbitPacketChunkAssembler(
    private val expectedKind: RabbitPacketChunking.PacketChunkKind,
    timeout: Duration
) {
    companion object {
        private const val MAX_IN_FLIGHT_PACKETS = 256
        private const val MAX_BUFFERED_BYTES = 128L * 1024 * 1024 // 128 MB
    }

    private val timeoutNanos = timeout.inWholeNanoseconds.coerceAtLeast(1L)

    private val cleanupIntervalNanos = minOf(timeoutNanos, 1.seconds.inWholeNanoseconds)
    private val lastCleanupNanos = AtomicLong(System.nanoTime())

    private val partialPackets = ConcurrentHashMap<String, PartialPacket>()
    private val inFlightPackets = AtomicInteger()
    private val bufferedBytes = AtomicLong()

    fun accept(
        correlationId: String,
        body: ByteArray
    ): ChunkAcceptResult = accept(correlationId, body, metadata = null)

    fun accept(
        correlationId: String,
        body: ByteArray,
        metadata: Any?
    ): ChunkAcceptResult {
        val now = System.nanoTime()

        cleanupExpiredIfDue(now)

        val chunk = RabbitPacketChunking.decodeOrNull(body) ?: return ChunkAcceptResult.NotChunk

        if (chunk.kind != expectedKind) {
            throw SurfRabbitProtocolChunkKindMismatchException(expectedKind.name, chunk.kind.name)
        }

        while (true) {
            val partial = getOrCreatePartial(
                correlationId = correlationId,
                chunk = chunk,
                now = now,
                metadata = metadata
            )

            synchronized(partial) {
                if (partialPackets[correlationId] !== partial) {
                    return@synchronized
                } else if (partial.isExpired(now, timeoutNanos)) {
                    removePartial(correlationId, partial)
                    return@synchronized
                } else {
                    try {
                        validateMetadata(
                            correlationId = correlationId,
                            partial = partial,
                            chunk = chunk,
                            metadata = metadata
                        )
                        partial.add(correlationId, chunk)
                    } catch (throwable: Throwable) {
                        removePartial(correlationId, partial)
                        throw throwable
                    }

                    if (!partial.isComplete()) {
                        return ChunkAcceptResult.Stored
                    }

                    val assembled = try {
                        partial.assemble()
                    } finally {
                        removePartial(correlationId, partial)
                    }
                    return ChunkAcceptResult.Complete(assembled)
                }
            }
        }
    }

    fun discard(correlationId: String) {
        val partial = partialPackets[correlationId] ?: return
        synchronized(partial) {
            removePartial(correlationId, partial)
        }
    }

    fun clear() {
        for ((correlationId, partial) in partialPackets) {
            synchronized(partial) {
                removePartial(correlationId, partial)
            }
        }
    }

    @Suppress("FoldInitializerAndIfToElvis")
    private fun getOrCreatePartial(
        correlationId: String,
        chunk: PacketChunk,
        now: Long,
        metadata: Any?
    ): PartialPacket {
        while (true) {
            partialPackets[correlationId]?.let { return it }

            val currentCount = inFlightPackets.incrementAndGet()
            if (currentCount > MAX_IN_FLIGHT_PACKETS) {
                inFlightPackets.decrementAndGet()
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "inFlightPackets",
                    expected = "<= $MAX_IN_FLIGHT_PACKETS",
                    actual = currentCount
                )
            }

            val created = PartialPacket(
                createdAtNanos = now,
                totalChunks = chunk.totalChunks,
                originalSize = chunk.originalSize,
                metadata = metadata
            )

            val previous = partialPackets.putIfAbsent(correlationId, created)
            if (previous == null) {
                return created
            }
            inFlightPackets.decrementAndGet()
        }
    }

    private fun validateMetadata(
        correlationId: String,
        partial: PartialPacket,
        chunk: PacketChunk,
        metadata: Any?
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

        if (partial.metadata != metadata) {
            throw SurfRabbitProtocolChunkMetadataMismatchException(
                correlationId = correlationId,
                field = "transportMetadata",
                expected = partial.metadata,
                actual = metadata
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
        for ((correlationId, partial) in partialPackets) {
            synchronized(partial) {
                if (partial.isExpired(now, timeoutNanos)) {
                    removePartial(correlationId, partial)
                }
            }
        }
    }

    private fun removePartial(correlationId: String, partial: PartialPacket) {
        if (partialPackets.remove(correlationId, partial)) {
            inFlightPackets.decrementAndGet()
            bufferedBytes.addAndGet(-partial.bufferedByteCount)
        }
    }

    private fun reserveBytes(byteCount: Int): Boolean {
        while (true) {
            val current = bufferedBytes.get()
            val updated = current + byteCount
            if (updated > MAX_BUFFERED_BYTES) return false
            if (bufferedBytes.compareAndSet(current, updated)) return true
        }
    }


    private inner class PartialPacket(
        val createdAtNanos: Long,
        val totalChunks: Int,
        val originalSize: Int,
        val metadata: Any?
    ) {
        private val chunks = arrayOfNulls<ByteArray>(totalChunks)
        private var receivedChunks = 0
        var bufferedByteCount = 0L
            private set

        fun isExpired(now: Long, timeoutNanos: Long): Boolean {
            return now - createdAtNanos >= timeoutNanos
        }

        fun add(correlationId: String, chunk: PacketChunk) {
            val existing = chunks[chunk.chunkIndex]
            if (existing != null) {
                if (!existing.contentEquals(chunk.payload)) {
                    throw SurfRabbitProtocolChunkMetadataMismatchException(
                        correlationId = correlationId,
                        field = "payload[${chunk.chunkIndex}]",
                        expected = "identical duplicate payload",
                        actual = "different payload"
                    )
                }
                return
            }

            if (!reserveBytes(chunk.payload.size)) {
                throw SurfRabbitProtocolInvalidChunkMetadataException(
                    field = "bufferedBytes",
                    expected = "<= $MAX_BUFFERED_BYTES",
                    actual = bufferedBytes.get() + chunk.payload.size
                )
            }

            chunks[chunk.chunkIndex] = chunk.payload
            receivedChunks++
            bufferedByteCount += chunk.payload.size
        }

        fun isComplete(): Boolean = receivedChunks == totalChunks

        fun assemble(): ByteArray {
            val result = ByteArray(originalSize)
            var offset = 0

            for (i in 0 until totalChunks) {
                val chunk = chunks[i] ?: throw SurfRabbitProtocolMissingChunkException()

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
