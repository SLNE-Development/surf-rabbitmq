package dev.slne.surf.eventbus.rabbitmq.common.topology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueArgumentsTest {

    @Test
    fun `service queues are quorum, dead-lettered and bounded`() {
        val args = QueueArguments.serviceQueue()

        assertEquals("quorum", args["x-queue-type"])
        assertEquals(RabbitTopology.DLX_EXCHANGE, args["x-dead-letter-exchange"])
        assertEquals(268_435_456L, args["x-max-length-bytes"])
        assertEquals("reject-publish", args["x-overflow"])
    }

    @Test
    fun `service queues do not override the dead-letter routing key`() {
        // Dead-lettering must preserve the original routing key so a retried message
        // finds its way back to its own service queue.
        assertNull(QueueArguments.serviceQueue()["x-dead-letter-routing-key"])
    }

    @Test
    fun `the dead letter queue is not itself dead-lettered but is bounded`() {
        // Not dead-lettered: a failing DLQ consumer would loop messages forever.
        // Bounded with drop-head: nobody consumes it, so unbounded growth would
        // eventually exhaust broker memory - and reject-publish here would break
        // the dead-letter path itself.
        val args = QueueArguments.deadLetterQueue()

        assertEquals("quorum", args["x-queue-type"])
        assertNull(args["x-dead-letter-exchange"])
        assertEquals(268_435_456L, args["x-max-length-bytes"])
        assertEquals("drop-head", args["x-overflow"])
    }

    @Test
    fun `ephemeral queues carry no quorum or overflow settings`() {
        // Quorum queues cannot be exclusive or auto-delete.
        val args = QueueArguments.ephemeralQueue()

        assertNull(args["x-queue-type"])
        assertNull(args["x-max-length-bytes"])
    }

    @Test
    fun `shared event queues pin the dead-letter routing key to the service`() {
        // Events are delivered with their TOPIC as routing key. Without the pinned key,
        // a nacked event would dead-letter into the direct surf.dlx with a topic key,
        // match no binding, and vanish - exactly the P5 message loss this redesign fixes.
        val args = QueueArguments.sharedEventQueue("surf-stats")

        assertEquals("quorum", args["x-queue-type"])
        assertEquals(RabbitTopology.DLX_EXCHANGE, args["x-dead-letter-exchange"])
        assertEquals("surf-stats", args["x-dead-letter-routing-key"])
    }

    @Test
    fun `shared event queues drop oldest instead of rejecting publishes`() {
        // Publisher confirms only ack once EVERY bound queue accepted the message.
        // reject-publish here would let one full subscriber queue fail every publisher
        // of matching topics fleet-wide. drop-head keeps the failure local to the
        // overflowing subscriber.
        val args = QueueArguments.sharedEventQueue("surf-stats")

        assertEquals(268_435_456L, args["x-max-length-bytes"])
        assertEquals("drop-head", args["x-overflow"])
    }
}
