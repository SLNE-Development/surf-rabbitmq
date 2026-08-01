package dev.slne.surf.eventbus.common.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class LegacyEnvironmentGuardTest {

    @Test
    fun `a legacy rabbit variable fails the start and names its replacement`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_RABBITMQ_HOST" to "broker.internal"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assertContains(failure.message!!, "SURF_RABBITMQ_HOST")
        assertContains(failure.message!!, "SURF_EVENTBUS_RABBITMQ_HOST")
    }

    @Test
    fun `a legacy redis variable fails the start and names its replacement`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_REDIS_PASSWORD" to "secret"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assertContains(failure.message!!, "SURF_EVENTBUS_REDIS_PASSWORD")
    }

    @Test
    fun `the failure never contains the value of a sensitive variable`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_REDIS_PASSWORD" to "secret"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assert(!failure.message!!.contains("secret")) { "the guard leaked a password" }
    }

    @Test
    fun `an environment with only new names passes`() {
        val environment = EnvironmentVariables.from(
            mapOf("SURF_EVENTBUS_RABBITMQ_HOST" to "broker.internal")
        )

        LegacyEnvironmentGuard.check(environment)
    }
}
