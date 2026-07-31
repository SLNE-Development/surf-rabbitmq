package dev.slne.surf.eventbus.rabbitmq.core.packet

import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.eventbus.rabbitmq.common.packet.RabbitPacketChunking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChunkSeriesTest {

    private fun assembler() = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.RESPONSE,
        timeout = 60.seconds
    )

    /** Distinct content of the same length, mimicking two attempts differing only by timestamp. */
    private fun payload(fill: Char, size: Int = 1_500_000) = ByteArray(size) { fill.code.toByte() }

    @Test
    fun `each split gets its own series id`() {
        val a = RabbitPacketChunking.splitResponse(payload('a'))
        val b = RabbitPacketChunking.splitResponse(payload('a'))

        val seriesA = RabbitPacketChunking.decodeOrNull(a[0])!!.seriesId
        val seriesB = RabbitPacketChunking.decodeOrNull(b[0])!!.seriesId

        assertTrue(
            seriesA != seriesB,
            "two separate splits must be distinguishable, otherwise their chunks can be mixed"
        )
    }

    @Test
    fun `all chunks of one split share a series id`() {
        val chunks = RabbitPacketChunking.splitResponse(payload('a'))
        val ids = chunks.map { RabbitPacketChunking.decodeOrNull(it)!!.seriesId }.toSet()

        assertEquals(1, ids.size, "one split is one series")
    }

    @Test
    fun `a partial series is not completed by chunks of a different series`() {
        val assembler = assembler()
        val correlationId = "srq1:test-1"

        val first = RabbitPacketChunking.splitResponse(payload('a'))
        val second = RabbitPacketChunking.splitResponse(payload('b'))

        assertTrue(first.size >= 3, "the payload must span several chunks for this test")

        // First attempt sends all but the last chunk, then the service dies.
        for (i in 0 until first.size - 1) {
            assertEquals(
                RabbitPacketChunkAssembler.ChunkAcceptResult.Stored,
                assembler.accept(correlationId, first[i])
            )
        }

        // Second attempt sends a complete series under the same correlation id.
        var completed: RabbitPacketChunkAssembler.ChunkAcceptResult? = null
        for (chunk in second) {
            completed = assembler.accept(correlationId, chunk)
        }

        val result = completed
        assertTrue(
            result is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete,
            "the second, complete series must assemble on its own"
        )

        assertTrue(
            result.body.all { it == 'b'.code.toByte() },
            "the assembled packet must come entirely from the second series - any 'a' byte " +
                    "means chunks of two different responses were merged into one packet"
        )
    }

    @Test
    fun `an abandoned series does not block a later one`() {
        val assembler = assembler()
        val correlationId = "srq1:test-2"

        val abandoned = RabbitPacketChunking.splitResponse(payload('a'))
        assembler.accept(correlationId, abandoned[0])

        val complete = RabbitPacketChunking.splitResponse(payload('b'))
        var last: RabbitPacketChunkAssembler.ChunkAcceptResult? = null
        for (chunk in complete) {
            last = assembler.accept(correlationId, chunk)
        }

        assertTrue(last is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete)
    }

    @Test
    fun `duplicate chunks of the same series are still idempotent`() {
        val assembler = assembler()
        val correlationId = "srq1:test-3"
        val chunks = RabbitPacketChunking.splitResponse(payload('a'))

        for (chunk in chunks) assembler.accept(correlationId, chunk)

        // A redelivered duplicate of an already-assembled series must not resurrect it.
        val afterComplete = assembler.accept(correlationId, chunks[0])
        assertEquals(RabbitPacketChunkAssembler.ChunkAcceptResult.Stored, afterComplete)
    }

    @Test
    fun `a single-chunk message still round-trips`() {
        val assembler = assembler()
        val small = ByteArray(1024) { 'x'.code.toByte() }

        // Below the threshold, so it is never chunked and must pass through untouched.
        assertEquals(
            RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk,
            assembler.accept("srq1:test-4", small)
        )
    }
}
