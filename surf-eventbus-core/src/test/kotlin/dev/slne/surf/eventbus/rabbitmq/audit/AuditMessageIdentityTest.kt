package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuditMessageIdentityTest {

    private fun properties(headers: Map<String, Any?> = emptyMap()): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder().headers(headers).build()

    @Test
    fun `a message without the header gets a fresh identity`() {
        val first = AuditMessageIdentity.of(properties())
        val second = AuditMessageIdentity.of(properties())

        assertNotEquals(first, second)
    }

    @Test
    fun `an existing header is reused so ladder attempts group together`() {
        val stamped = AuditMessageIdentity.stamp(properties(), "id-1")

        assertEquals("id-1", AuditMessageIdentity.of(stamped))
    }

    @Test
    fun `stamping preserves the other headers`() {
        val stamped = AuditMessageIdentity.stamp(properties(mapOf("x-attempts" to 2)), "id-1")

        assertEquals(2, stamped.headers["x-attempts"])
        assertEquals("id-1", stamped.headers[AuditMessageIdentity.HEADER])
    }
}
