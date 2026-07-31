package dev.slne.surf.eventbus.rabbitmq.core.event

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.eventbus.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.eventbus.rabbitmq.api.event.SubscriptionMode
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
@RabbitEvent("test.broadcast")
class BroadcastTestEvent(val payload: String) : RabbitEventPacket()

@Serializable
@RabbitEvent("test.shared")
class SharedTestEvent(val payload: String) : RabbitEventPacket()

@Serializable
@RabbitEvent("faction.abc.disbanded")
class PatternTestEvent(val payload: String) : RabbitEventPacket()

@RequiresDocker
class EventDeliveryTest {

    private val dataPath = Files.createTempDirectory("event-test")

    private class BroadcastListener {
        val count = AtomicInteger()

        @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
        suspend fun onEvent(event: BroadcastTestEvent) {
            count.incrementAndGet()
        }
    }

    private class SharedListener {
        val count = AtomicInteger()

        @RabbitSubscribe(mode = SubscriptionMode.SHARED)
        suspend fun onEvent(event: SharedTestEvent) {
            count.incrementAndGet()
        }
    }

    private class PatternListener {
        val count = AtomicInteger()

        @RabbitSubscribe(topic = "faction.*.disbanded")
        suspend fun onEvent(event: PatternTestEvent) {
            count.incrementAndGet()
        }
    }

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()

    @Test
    fun `a broadcast event reaches every instance`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("bcast")
        val listeners = (1..3).map { BroadcastListener() }

        val instances = listeners.map { listener ->
            api(service).also {
                it.registerListener(listener)
                it.freezeAndConnect()
            }
        }

        val publisher = api("publisher").also { it.freezeAndConnect() }

        try {
            publisher.publish(BroadcastTestEvent("hello"))

            awaitCondition("all three instances receive the event") {
                listeners.all { it.count.get() == 1 }
            }

            assertEquals(listOf(1, 1, 1), listeners.map { it.count.get() })
        } finally {
            publisher.disconnect()
            instances.forEach { it.disconnect() }
        }
    }

    @Test
    fun `a shared event reaches exactly one instance`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("shared")
        val listeners = (1..3).map { SharedListener() }

        val instances = listeners.map { listener ->
            api(service).also {
                it.registerListener(listener)
                it.freezeAndConnect()
            }
        }

        val publisher = api("publisher").also { it.freezeAndConnect() }

        try {
            publisher.publish(SharedTestEvent("hello"))

            awaitCondition("exactly one instance receives the event") {
                listeners.sumOf { it.count.get() } == 1
            }

            // Give any wrongly-bound instance time to also receive it.
            delay(1000)

            assertEquals(
                1, listeners.sumOf { it.count.get() },
                "SHARED must deliver once across all instances - more than one means each " +
                        "instance bound its own queue, which would multiply every side effect " +
                        "by the instance count"
            )
        } finally {
            publisher.disconnect()
            instances.forEach { it.disconnect() }
        }
    }

    @Test
    fun `a shared subscription survives all instances restarting`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("durable")

        // Bring an instance up and down so the durable queue exists and stays.
        val first = SharedListener()
        api(service).also {
            it.registerListener(first)
            it.freezeAndConnect()
        }.disconnect()

        val publisher = api("publisher").also { it.freezeAndConnect() }
        publisher.publish(SharedTestEvent("while-down"))
        publisher.disconnect()

        val second = SharedListener()
        val restarted = api(service).also {
            it.registerListener(second)
            it.freezeAndConnect()
        }

        try {
            awaitCondition("the event published while offline is delivered after restart") {
                second.count.get() == 1
            }
        } finally {
            restarted.disconnect()
        }
    }

    @Test
    fun `a topic pattern matches a wildcard segment`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("pattern")
        val listener = PatternListener()

        val instance = api(service).also {
            it.registerListener(listener)
            it.freezeAndConnect()
        }
        val publisher = api("publisher").also { it.freezeAndConnect() }

        try {
            publisher.publish(PatternTestEvent("x"))

            awaitCondition("faction.*.disbanded matches faction.abc.disbanded") {
                listener.count.get() == 1
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @Test
    fun `publishing an event nobody subscribes to is not an error`() = runBlocking {
        val publisher = api("publisher").also { it.freezeAndConnect() }

        try {
            // Must not throw: a publisher never knows whether anyone is listening.
            publisher.publish(BroadcastTestEvent("nobody-home"))
        } finally {
            publisher.disconnect()
        }
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(50)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
