package dev.slne.surf.rabbitmq.common.topology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RabbitTopologyTest {

    @Test
    fun `exchange and audit queue names match the specification`() {
        assertEquals("surf.rpc", RabbitTopology.RPC_EXCHANGE)
        assertEquals("surf.events", RabbitTopology.EVENTS_EXCHANGE)
        assertEquals("surf.dlx", RabbitTopology.DLX_EXCHANGE)
        assertEquals("surf.unroutable", RabbitTopology.UNROUTABLE_QUEUE)
    }

    @Test
    fun `queue names are built from their prefix`() {
        assertEquals("surf.service.surf-factions", RabbitTopology.serviceQueue("surf-factions"))
        assertEquals("surf.instance.lobby-3", RabbitTopology.instanceQueue("lobby-3"))
        assertEquals("surf.reply.lobby-3", RabbitTopology.replyQueue("lobby-3"))
        assertEquals("surf.dlq.surf-factions", RabbitTopology.deadLetterQueue("surf-factions"))
    }

    @Test
    fun `shared and instance event queues live in separate namespaces`() {
        // Without the extra segment, a service named "x" and an instance named "x"
        // would fight over the same queue.
        assertEquals(
            "surf.events.shared.surf-factions",
            RabbitTopology.sharedEventQueue("surf-factions")
        )
        assertEquals(
            "surf.events.instance.surf-factions",
            RabbitTopology.instanceEventQueue("surf-factions")
        )
        assertTrue(
            RabbitTopology.sharedEventQueue("x") != RabbitTopology.instanceEventQueue("x")
        )
    }

    @Test
    fun `characters illegal in AMQP names are replaced`() {
        assertEquals("surf.service.my_service", RabbitTopology.serviceQueue("my service"))
        assertEquals("surf.service.a_b", RabbitTopology.serviceQueue("a/b"))
        assertEquals("surf.service.a_b", RabbitTopology.serviceQueue("a#b"))
    }

    @Test
    fun `legal characters survive sanitising`() {
        assertEquals("a-b_c.d1", RabbitTopology.sanitize("a-b_c.d1"))
    }

    @Test
    fun `sanitising is stable`() {
        val once = RabbitTopology.sanitize("my service")
        assertEquals(once, RabbitTopology.sanitize(once))
    }

    @Test
    fun `blank names are rejected`() {
        val thrown = runCatching { RabbitTopology.serviceQueue("  ") }.exceptionOrNull()
        assertTrue(
            thrown is IllegalArgumentException,
            "a blank service name would produce the queue 'surf.service.' and silently " +
                    "collide with every other blank-named service"
        )
    }

    @Test
    fun `names stay within the AMQP length limit`() {
        // AMQP caps queue names at 255 bytes; the prefix must not push a long
        // service name past it unnoticed.
        val long = "s".repeat(300)
        val thrown = runCatching { RabbitTopology.serviceQueue(long) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "over-long names must be rejected")
    }
}
