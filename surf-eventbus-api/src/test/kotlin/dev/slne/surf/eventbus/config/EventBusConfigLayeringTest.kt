package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.config.type.BooleanOrDefault
import dev.slne.surf.api.core.config.type.StringOrDefault
import dev.slne.surf.api.core.config.type.number.IntOr
import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * `env > plugin yaml > global yaml > built-in default`, per field, for both transports.
 *
 * Replaces `RabbitEnvironmentTest` and `RedisConfigLayeringTest`, which tested two different
 * layering implementations that disagreed: Rabbit merged per field, Redis merged whole files,
 * so a Redis plugin file with one key in it silently discarded everything the global file set.
 */
class EventBusConfigLayeringTest {

    private val noEnvironment = EnvironmentVariables.from(emptyMap())

    private fun environment(vararg pairs: Pair<String, String>) =
        EnvironmentVariables.from(mapOf(*pairs))

    @Test
    fun `the built-in default applies when nothing else is set`() {
        val resolved = resolveEventBusConfig(environment = noEnvironment)

        assertEquals(EventBusDefaults.RABBIT_HOST, resolved.rabbitmq.host)
        assertEquals(5672, resolved.rabbitmq.port)
        assertEquals(EventBusDefaults.REDIS_HOST, resolved.redis.host)
        assertEquals(6379, resolved.redis.port)
    }

    @Test
    fun `the global yaml beats the default`() {
        val global = EventBusConfig(
            rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-global")),
            redis = RedisSection(host = StringOrDefault.of("redis-global")),
        )

        val resolved = resolveEventBusConfig(global = global, environment = noEnvironment)

        assertEquals("rabbit-global", resolved.rabbitmq.host)
        assertEquals("redis-global", resolved.redis.host)
    }

    @Test
    fun `the plugin yaml beats the global yaml`() {
        val resolved = resolveEventBusConfig(
            global = EventBusConfig(
                rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-global")),
                redis = RedisSection(host = StringOrDefault.of("redis-global")),
            ),
            plugin = EventBusConfig(
                rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-plugin")),
                redis = RedisSection(host = StringOrDefault.of("redis-plugin")),
            ),
            environment = noEnvironment,
        )

        assertEquals("rabbit-plugin", resolved.rabbitmq.host)
        assertEquals("redis-plugin", resolved.redis.host)
    }

    @Test
    fun `the environment beats every yaml layer`() {
        val resolved = resolveEventBusConfig(
            global = EventBusConfig(
                rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-global")),
                redis = RedisSection(host = StringOrDefault.of("redis-global")),
            ),
            plugin = EventBusConfig(
                rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-plugin")),
                redis = RedisSection(host = StringOrDefault.of("redis-plugin")),
            ),
            environment = environment(
                "SURF_EVENTBUS_RABBITMQ_HOST" to "rabbit-env",
                "SURF_EVENTBUS_REDIS_HOST" to "redis-env",
            ),
        )

        assertEquals("rabbit-env", resolved.rabbitmq.host)
        assertEquals("redis-env", resolved.redis.host)
    }

    @Test
    fun `a plugin file that says nothing about a field keeps the global value`() {
        // The bug this pins: Redis used to pick a whole yaml layer, so a plugin file with one
        // key in it discarded every other value the global file set.
        val resolved = resolveEventBusConfig(
            global = EventBusConfig(
                rabbitmq = RabbitMQSection(
                    host = StringOrDefault.of("rabbit-global"),
                    port = IntOr.Default(5673),
                ),
                redis = RedisSection(
                    host = StringOrDefault.of("redis-global"),
                    port = IntOr.Default(6380),
                ),
            ),
            plugin = EventBusConfig(
                rabbitmq = RabbitMQSection(host = StringOrDefault.of("rabbit-plugin")),
                redis = RedisSection(host = StringOrDefault.of("redis-plugin")),
            ),
            environment = noEnvironment,
        )

        assertEquals("rabbit-plugin", resolved.rabbitmq.host)
        assertEquals(5673, resolved.rabbitmq.port, "the global port must survive")
        assertEquals("redis-plugin", resolved.redis.host)
        assertEquals(6380, resolved.redis.port, "the global port must survive")
    }

    @Test
    fun `a boolean set to false in yaml is not mistaken for unset`() {
        val resolved = resolveEventBusConfig(
            global = EventBusConfig(
                rabbitmq = RabbitMQSection(persistRequests = BooleanOrDefault.FALSE)
            ),
            environment = noEnvironment,
        )

        assertFalse(
            resolved.rabbitmq.persistRequests,
            "the built-in default is true, so an explicit false must not fall through"
        )
    }

    @Test
    fun `a port outside the valid range fails`() {
        assertFailsWith<IllegalStateException> {
            resolveEventBusConfig(
                environment = environment("SURF_EVENTBUS_RABBITMQ_PORT" to "70000")
            )
        }

        assertFailsWith<IllegalStateException> {
            resolveEventBusConfig(
                environment = environment("SURF_EVENTBUS_REDIS_PORT" to "70000")
            )
        }
    }

    @Test
    fun `a non-numeric port fails`() {
        assertFailsWith<IllegalStateException> {
            resolveEventBusConfig(
                environment = environment("SURF_EVENTBUS_RABBITMQ_PORT" to "nope")
            )
        }
    }

    @Test
    fun `a failure about the password does not contain it`() {
        val failure = assertFailsWith<IllegalStateException> {
            resolveEventBusConfig(
                environment = environment(
                    "SURF_EVENTBUS_RABBITMQ_PORT" to "nope",
                    "SURF_EVENTBUS_RABBITMQ_PASSWORD" to "hunter2",
                )
            )
        }

        assertFalse(failure.message!!.contains("hunter2"))
    }
}
