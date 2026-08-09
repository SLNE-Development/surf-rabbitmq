package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.core.FakeEventTransport
import dev.slne.surf.eventbus.core.FakeQueryTransport
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * Spec tests 33–37: a verb that has no transport must say which builder call is missing.
 *
 * No container: every case here is about what the builder refuses before anything connects.
 *
 * **33–36 are not repeated here.** They were written in plan 2 and live in
 * [dev.slne.surf.eventbus.core.SurfEventBusLifecycleTest]:
 * - 33 `publish without the redis transport names the missing builder call`
 * - 34 `subscribing without the redis transport fails at freeze, naming the handler`
 * - 35 `rpc without the rabbit transport names the missing builder call`
 * - 36 `a builder without any transport fails`
 *
 * What is added here is 37, which none of them covers.
 */
class TransportEnablementTest {

    private val dataPath = Files.createTempDirectory("surf-eventbus-enablement")

    private fun redisOnlyBus(): SurfEventBus =
        SurfEventBus.builder("surf-enablement", dataPath)
            .withRedis(FakeEventTransport(), FakeQueryTransport())
            .build()

    @Test
    fun `37 - a redis-only bus reports a failing handler to the log, not to an audit service`() {
        // Audit rides RabbitMQ. A bus built with .withRedis() alone therefore has nowhere to
        // send a report, and that must degrade to a log line rather than to a failure at the
        // publish call - the handler's caller did nothing wrong.
        val bus = redisOnlyBus()
        bus.subscribe(FailingListener())

        runBlocking {
            bus.freezeAndConnect()

            // The throwing handler must not surface here.
            bus.publish(EnablementEvent("x"))

            bus.disconnect()
        }
    }

    @Test
    fun `37 - rpc on a redis-only bus still names the missing builder call`() {
        val bus = redisOnlyBus()
        runBlocking { bus.freezeAndConnect() }

        val failure = assertFailsWith<IllegalStateException> { bus.rpc(EnablementService::class) }

        assertContains(failure.message!!, ".withRabbit()")

        runBlocking { bus.disconnect() }
    }
}

@Serializable
@BusEvent("enablement.test")
class EnablementEvent(val value: String) : SurfBusEvent()

private class FailingListener {
    @SurfSubscribe("enablement.test")
    @Suppress("unused")
    fun onEvent(event: EnablementEvent): Unit = error("this handler always fails")
}

private interface EnablementService
