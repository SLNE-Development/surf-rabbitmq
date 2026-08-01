package dev.slne.surf.eventbus.rabbitmq.identity

import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RabbitIdentityTest {

    @Test
    fun `the instance id carries the service name`() {
        val identity = RabbitIdentity.create("surf-factions")

        assertEquals("surf-factions", identity.serviceName)
        assertTrue(
            identity.instanceId.startsWith("surf-factions-"),
            "an instance id that does not name its service is unreadable in broker tooling, " +
                    "but was '${identity.instanceId}'"
        )
    }

    @Test
    fun `two instances of the same service get different ids`() {
        val a = RabbitIdentity.create("surf-factions")
        val b = RabbitIdentity.create("surf-factions")

        assertEquals(a.serviceName, b.serviceName)
        assertNotEquals(
            a.instanceId, b.instanceId,
            "colliding instance ids make two processes share one reply queue"
        )
    }

    @Test
    fun `the suffix is eight hex characters`() {
        val identity = RabbitIdentity.create("svc")
        val suffix = identity.instanceId.removePrefix("svc-")

        assertEquals(8, suffix.length)
        assertTrue(suffix.all { it in "0123456789abcdef" }, "suffix was '$suffix'")
    }

    @Test
    fun `a blank service name is rejected`() {
        val thrown = runCatching { RabbitIdentity.create(" ") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `an explicit instance name is used verbatim`() {
        // Instance targeting only works if the target's id is knowable in advance.
        // A Paper server configured as "lobby-3" must be addressable as exactly that.
        val identity = RabbitIdentity.create("lobby", instanceName = "lobby-3")

        assertEquals("lobby", identity.serviceName)
        assertEquals("lobby-3", identity.instanceId)
    }

    @Test
    fun `a blank instance name is rejected`() {
        val thrown = runCatching {
            RabbitIdentity.create("lobby", instanceName = " ")
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a service target routes by service name`() {
        assertEquals("surf-factions", RabbitTarget.ServiceTarget("surf-factions").routingKey)
    }

    @Test
    fun `an instance target routes by instance id`() {
        assertEquals("lobby-3", RabbitTarget.InstanceTarget("lobby-3").routingKey)
    }

    @Test
    fun `targets of different kinds are not equal even with the same name`() {
        assertNotEquals<RabbitTarget>(
            RabbitTarget.ServiceTarget("x"),
            RabbitTarget.InstanceTarget("x")
        )
    }
}
