package dev.slne.surf.eventbus.rabbitmq.core.publish

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MessageKindTest {

    private val timeout = 60.seconds

    @Test
    fun `an rpc request expires with the request timeout`() {
        // Once the caller has given up, the request must not be executed minutes later.
        assertEquals("60000", MessageKind.RPC_REQUEST.expirationMillis(timeout))
    }

    @Test
    fun `an rpc response expires with the request timeout`() {
        assertEquals("60000", MessageKind.RPC_RESPONSE.expirationMillis(timeout))
    }

    @Test
    fun `a fire-and-forget message never expires`() {
        // It must wait in the durable queue until the service returns, however long that takes.
        assertNull(
            MessageKind.FIRE_AND_FORGET.expirationMillis(timeout),
            "expiring fire-and-forget would silently discard work while a service is down"
        )
    }

    @Test
    fun `an event never expires`() {
        assertNull(MessageKind.EVENT.expirationMillis(timeout))
    }

    @Test
    fun `requests and fire-and-forget follow the persistence setting`() {
        assertEquals(2, MessageKind.RPC_REQUEST.deliveryMode(true, false))
        assertEquals(1, MessageKind.RPC_REQUEST.deliveryMode(false, false))
        assertEquals(2, MessageKind.FIRE_AND_FORGET.deliveryMode(true, false))
    }

    @Test
    fun `responses follow the response persistence setting`() {
        assertEquals(2, MessageKind.RPC_RESPONSE.deliveryMode(true, true))
        assertEquals(1, MessageKind.RPC_RESPONSE.deliveryMode(true, false))
    }

    @Test
    fun `events are always persistent`() {
        // A SHARED subscription targets a durable queue; a transient message there would be
        // lost on broker restart for no benefit.
        assertEquals(2, MessageKind.EVENT.deliveryMode(false, false))
    }
}
