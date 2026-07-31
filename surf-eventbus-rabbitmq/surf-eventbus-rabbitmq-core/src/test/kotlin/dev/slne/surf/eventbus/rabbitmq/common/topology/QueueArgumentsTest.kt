package dev.slne.surf.eventbus.rabbitmq.common.topology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class QueueArgumentsTest {

    @Test
    fun `a service queue no longer dead-letters`() {
        val arguments = QueueArguments.serviceQueue()

        assertFalse(
            arguments.containsKey("x-dead-letter-exchange"),
            "failures are audited now; a DLX would park a second, unread copy"
        )
        assertEquals("quorum", arguments["x-queue-type"])
        assertEquals("reject-publish", arguments["x-overflow"])
    }

    @Test
    fun `service queues are bounded`() {
        val args = QueueArguments.serviceQueue()

        assertEquals(268_435_456L, args["x-max-length-bytes"])
    }

    @Test
    fun `ephemeral queues carry no quorum or overflow settings`() {
        // Quorum queues cannot be exclusive or auto-delete.
        val args = QueueArguments.ephemeralQueue()

        assertNull(args["x-queue-type"])
        assertNull(args["x-max-length-bytes"])
    }
}
