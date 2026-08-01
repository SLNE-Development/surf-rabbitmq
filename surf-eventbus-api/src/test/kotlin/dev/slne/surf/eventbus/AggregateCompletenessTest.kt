package dev.slne.surf.eventbus

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The aggregate exposes every -api module.
 *
 * A forgotten `api(...)` line surfaces as an unresolved reference in a consumer's build, days
 * later and somewhere else. Here it surfaces now.
 */
class AggregateCompletenessTest {

    @Test
    fun `every api module is on the compile classpath of the aggregate`() {
        val expected = listOf(
            "dev.slne.surf.eventbus.SurfEventBus",
            "dev.slne.surf.eventbus.event.SurfBusEvent",
            "dev.slne.surf.eventbus.query.QueryService",
            "dev.slne.surf.eventbus.audit.AuditService",
            "dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi",
            "dev.slne.surf.eventbus.rabbitmq.rpc.RpcService",
            "dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget",
            "dev.slne.surf.eventbus.redis.RedisApi",
            "dev.slne.surf.eventbus.circuitbreaker.CircuitBreaker"
        )

        for (name in expected) {
            val present = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            assertTrue(present, "$name is not reachable through surf-eventbus-api")
        }
    }
}
