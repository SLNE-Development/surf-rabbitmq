package dev.slne.surf.eventbus.rabbitmq.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RabbitEnvironmentTest {

    private val yamlFallback = GlobalRabbitMQConfig()

    @Test
    fun `an unset variable falls through to the yaml layer`() {
        val resolved = RabbitEnvironment.resolve(EnvironmentVariables.from(emptyMap()), yamlFallback)

        assertEquals(yamlFallback.getHost(), resolved.getHost())
        assertEquals(yamlFallback.getPort(), resolved.getPort())
    }

    @Test
    fun `a set variable overrides the yaml layer`() {
        val environment = EnvironmentVariables.from(
            mapOf(
                "SURF_EVENTBUS_RABBITMQ_HOST" to "broker.internal",
                "SURF_EVENTBUS_RABBITMQ_PORT" to "5673"
            )
        )

        val resolved = RabbitEnvironment.resolve(environment, yamlFallback)

        assertEquals("broker.internal", resolved.getHost())
        assertEquals(5673, resolved.getPort())
    }

    @Test
    fun `a port outside the valid range fails`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "70000"))

        assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }
    }

    @Test
    fun `a non-numeric port fails`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "nope"))

        assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }
    }

    @Test
    fun `a failure about the password does not contain it`() {
        val environment = EnvironmentVariables.from(
            mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "nope", "SURF_EVENTBUS_RABBITMQ_PASSWORD" to "hunter2")
        )

        val failure = assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }

        assert(!failure.message!!.contains("hunter2"))
    }
}
