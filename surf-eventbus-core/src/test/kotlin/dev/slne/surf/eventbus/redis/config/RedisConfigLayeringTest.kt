package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RedisConfigLayeringTest {

    private val default = RedisConfig()

    @Test
    fun `the default applies when nothing else is set`() {
        val resolved = resolveRedisConfig(global = null, plugin = null, environment = EnvironmentVariables.from(emptyMap()))

        assertEquals(default.host, resolved.host)
        assertEquals(6379, resolved.port)
    }

    @Test
    fun `the global yaml beats the default`() {
        val global = RedisConfig(host = "global-host")

        val resolved = resolveRedisConfig(global, plugin = null, environment = EnvironmentVariables.from(emptyMap()))

        assertEquals("global-host", resolved.host)
    }

    @Test
    fun `the plugin yaml beats the global yaml`() {
        val resolved = resolveRedisConfig(
            global = RedisConfig(host = "global-host"),
            plugin = RedisConfig(host = "plugin-host"),
            environment = EnvironmentVariables.from(emptyMap())
        )

        assertEquals("plugin-host", resolved.host)
    }

    @Test
    fun `the environment beats every yaml layer`() {
        val resolved = resolveRedisConfig(
            global = RedisConfig(host = "global-host"),
            plugin = RedisConfig(host = "plugin-host"),
            environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_REDIS_HOST" to "env-host"))
        )

        assertEquals("env-host", resolved.host)
    }

    @Test
    fun `an invalid port in the environment fails`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_REDIS_PORT" to "70000"))

        kotlin.test.assertFailsWith<IllegalStateException> {
            resolveRedisConfig(null, null, environment).port
        }
    }
}
