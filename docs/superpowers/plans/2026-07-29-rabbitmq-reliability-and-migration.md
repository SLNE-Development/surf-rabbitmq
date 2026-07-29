# RabbitMQ Reliability and Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Do NOT use subagent-driven-development.** The repository owner's global instructions forbid delegating work to subagents.

**Prerequisites:** Plan 1 (`surf-circuitbreaker`), Plan 2 (topology foundation) and Plan 3 (events) must be complete.

**Goal:** Stop losing messages on failure. Add dead-letter queues, delayed retries with backoff, fail-fast on unroutable messages, and per-service circuit breakers — then migrate the test module and document the result.

**Architecture:** A failed handler republishes the message into one of three globally shared retry queues whose TTL expires it back into its own service queue, because dead-lettering preserves the original routing key. After three retries the message goes to the service's dead-letter queue. Unroutable publishes are caught by a `ReturnListener` and surfaced as an immediate exception instead of a request timeout. Each target service gets its own circuit breaker from `surf-circuitbreaker`.

**Tech Stack:** Kotlin (JVM toolchain 25), amqp-client 5.34.0, `surf-circuitbreaker`, JUnit 5, Testcontainers.

## Global Constraints

- All Global Constraints from Plans 2 and 3 still apply.
- Retry queues, verbatim from the spec: `surf.retry.10s` (TTL 10 000 ms),
  `surf.retry.60s` (60 000 ms), `surf.retry.300s` (300 000 ms). All dead-letter to `surf.rpc`.
- **Never set `x-dead-letter-routing-key` on a retry queue.** RabbitMQ then preserves the
  original routing key, which is the entire reason three shared queues can serve every service.
- Retry ladder, verbatim from the spec: `n = 0` → `10s`, `n = 1` → `60s`, `n = 2` → `300s`,
  `n = 3` → dead-letter queue. Four deliveries maximum.
- **`basicNack(requeue = true)` is forbidden.** It returns the message to the queue head for
  immediate redelivery, producing a hot loop that also blocks the queue.
- Circuit breaker defaults, verbatim: `failureThreshold = 5`, `openDuration = 30.seconds`.
- Only transport failures count toward the breaker. Business exceptions must not.
- Commit after every task.

## File Structure

| File | Responsibility |
|---|---|
| `surf-rabbitmq-core/.../retry/RetryPolicy.kt` | Attempt counting and ladder selection |
| `surf-rabbitmq-core/.../retry/RetryPublisher.kt` | Republishing to retry queue or DLQ |
| `surf-rabbitmq-core/.../connection/ReturnListenerBridge.kt` | Unroutable publishes → exception |
| `surf-rabbitmq-core/.../rpc/BreakerGuardedRpc.kt` | Per-service breaker wiring |
| `surf-rabbitmq-ksp/.../RpcServiceModelFactory.kt` | Reading `@RpcService(service = ...)` |

---

### Task 1: Retry policy

Decides how often a message has already been tried and where it goes next. Pure logic over the `x-death` header, fully unit-testable — and worth testing carefully, because an off-by-one here either drops messages a retry too early or loops them forever.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPolicy.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPolicyTest.kt`

**Interfaces:**
- Consumes: `RabbitTopology` (Plan 2)
- Produces:
  ```kotlin
  enum class RetryTier(val queueName: String, val ttlMillis: Long) {
      TEN_SECONDS("surf.retry.10s", 10_000),
      ONE_MINUTE("surf.retry.60s", 60_000),
      FIVE_MINUTES("surf.retry.300s", 300_000)
  }

  sealed interface RetryDecision {
      data class Retry(val tier: RetryTier) : RetryDecision
      data object DeadLetter : RetryDecision
  }

  object RetryPolicy {
      const val MAX_RETRIES = 3
      fun attemptsFrom(headers: Map<String, Any?>?): Int
      fun decide(attempts: Int, retryEnabled: Boolean): RetryDecision
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPolicyTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.retry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RetryPolicyTest {

    private fun deathHeader(count: Long): Map<String, Any?> =
        mapOf("x-death" to listOf(mapOf("count" to count, "queue" to "surf.service.x")))

    @Test
    fun `a first delivery has no attempts`() {
        assertEquals(0, RetryPolicy.attemptsFrom(null))
        assertEquals(0, RetryPolicy.attemptsFrom(emptyMap()))
    }

    @Test
    fun `the attempt count comes from x-death`() {
        assertEquals(1, RetryPolicy.attemptsFrom(deathHeader(1)))
        assertEquals(3, RetryPolicy.attemptsFrom(deathHeader(3)))
    }

    @Test
    fun `multiple x-death entries are summed`() {
        // A message dead-lettered through several queues carries one entry per queue.
        val headers = mapOf(
            "x-death" to listOf(
                mapOf("count" to 2L, "queue" to "surf.retry.10s"),
                mapOf("count" to 1L, "queue" to "surf.retry.60s")
            )
        )

        assertEquals(3, RetryPolicy.attemptsFrom(headers))
    }

    @Test
    fun `a malformed x-death is treated as no attempts`() {
        // Better to retry a message once too often than to crash the consumer on a header.
        assertEquals(0, RetryPolicy.attemptsFrom(mapOf("x-death" to "nonsense")))
        assertEquals(0, RetryPolicy.attemptsFrom(mapOf("x-death" to listOf("nonsense"))))
    }

    @Test
    fun `the ladder climbs ten seconds, one minute, five minutes`() {
        assertEquals(RetryDecision.Retry(RetryTier.TEN_SECONDS), RetryPolicy.decide(0, true))
        assertEquals(RetryDecision.Retry(RetryTier.ONE_MINUTE), RetryPolicy.decide(1, true))
        assertEquals(RetryDecision.Retry(RetryTier.FIVE_MINUTES), RetryPolicy.decide(2, true))
    }

    @Test
    fun `the fourth failure dead-letters`() {
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(3, true))
    }

    @Test
    fun `attempts beyond the maximum still dead-letter`() {
        // Defensive: a message must never loop, whatever its header claims.
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(99, true))
    }

    @Test
    fun `disabling retry dead-letters on the first failure`() {
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(0, false))
    }

    @Test
    fun `the ladder allows exactly four deliveries`() {
        val retries = (0..RetryPolicy.MAX_RETRIES)
            .map { RetryPolicy.decide(it, true) }
            .count { it is RetryDecision.Retry }

        assertEquals(3, retries, "three retries plus the first delivery is four in total")
    }

    @Test
    fun `tier queue names and ttls match the specification`() {
        assertEquals("surf.retry.10s", RetryTier.TEN_SECONDS.queueName)
        assertEquals(10_000L, RetryTier.TEN_SECONDS.ttlMillis)
        assertEquals("surf.retry.60s", RetryTier.ONE_MINUTE.queueName)
        assertEquals(60_000L, RetryTier.ONE_MINUTE.ttlMillis)
        assertEquals("surf.retry.300s", RetryTier.FIVE_MINUTES.queueName)
        assertEquals(300_000L, RetryTier.FIVE_MINUTES.ttlMillis)
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*RetryPolicyTest*'`
Expected: `Unresolved reference: RetryPolicy`.

- [ ] **Step 3: Implement**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPolicy.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.retry

/**
 * One rung of the retry ladder.
 *
 * The queues are shared by the entire fleet rather than created per service. That works
 * because dead-lettering preserves the original routing key when no
 * `x-dead-letter-routing-key` is set, so a message expiring out of `surf.retry.10s` returns to
 * whichever service queue it came from.
 */
enum class RetryTier(val queueName: String, val ttlMillis: Long) {
    TEN_SECONDS("surf.retry.10s", 10_000),
    ONE_MINUTE("surf.retry.60s", 60_000),
    FIVE_MINUTES("surf.retry.300s", 300_000)
}

/** What to do with a message whose handler failed. */
sealed interface RetryDecision {
    /** Park the message in [tier] and let its TTL return it to the service queue. */
    data class Retry(val tier: RetryTier) : RetryDecision

    /** Give up and move the message to the service's dead-letter queue. */
    data object DeadLetter : RetryDecision
}

/**
 * Decides how many times a message has been tried and what happens next.
 *
 * Attempt counting reads RabbitMQ's own `x-death` header rather than a custom one, so the
 * count survives even when a message travels through queues this library did not publish to.
 */
object RetryPolicy {

    /** Retries after the first delivery. Four deliveries in total. */
    const val MAX_RETRIES = 3

    /**
     * How often this message has already been dead-lettered.
     *
     * A malformed header yields `0`. Retrying once too often is recoverable; throwing while
     * handling a failure is not.
     */
    fun attemptsFrom(headers: Map<String, Any?>?): Int {
        val deaths = headers?.get("x-death") as? List<*> ?: return 0

        return deaths.sumOf { entry ->
            val map = entry as? Map<*, *> ?: return@sumOf 0L
            (map["count"] as? Number)?.toLong() ?: 0L
        }.toInt()
    }

    /**
     * Where a message goes after [attempts] failures.
     *
     * @param retryEnabled `false` for handlers that are not idempotent, which must not see the
     *   same message twice
     */
    fun decide(attempts: Int, retryEnabled: Boolean): RetryDecision {
        if (!retryEnabled) return RetryDecision.DeadLetter

        return when (attempts) {
            0 -> RetryDecision.Retry(RetryTier.TEN_SECONDS)
            1 -> RetryDecision.Retry(RetryTier.ONE_MINUTE)
            2 -> RetryDecision.Retry(RetryTier.FIVE_MINUTES)
            else -> RetryDecision.DeadLetter
        }
    }
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*RetryPolicyTest*'`
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(retry): add retry ladder and x-death attempt counting"
```

---

### Task 2: Retry queues and republishing

Declares the three shared retry queues and moves failed messages into them.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPublisher.kt`
- Modify: `surf-rabbitmq-core/.../topology/RabbitTopologyDeclarer.kt`
- Modify: `surf-rabbitmq-core/.../topology/QueueArguments.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryQueueTest.kt`

**Interfaces:**
- Consumes: `RetryPolicy` (Task 1), `RabbitTopologyDeclarer` (Plan 2)
- Produces:
  ```kotlin
  // QueueArguments
  fun retryQueue(tier: RetryTier): Map<String, Any>

  // RabbitTopologyDeclarer
  fun declareRetryQueues()

  class RetryPublisher(private val client: RabbitClient) {
      suspend fun handleFailure(
          delivery: Delivery,
          serviceName: String,
          retryEnabled: Boolean
      ): RetryDecision
  }
  ```

- [ ] **Step 1: Add the retry queue arguments**

Append to `QueueArguments`:

```kotlin
    /**
     * A holding queue whose TTL expiry returns the message to its origin.
     *
     * `x-dead-letter-routing-key` is deliberately **absent**: RabbitMQ then reuses the
     * message's original routing key, so one queue serves every service. Setting it would
     * pin every retried message to a single destination.
     */
    fun retryQueue(tier: RetryTier): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-message-ttl" to tier.ttlMillis,
        "x-dead-letter-exchange" to RabbitTopology.RPC_EXCHANGE
    )
```

- [ ] **Step 2: Declare them**

Append to `RabbitTopologyDeclarer`:

```kotlin
    /**
     * Declares the three shared retry queues.
     *
     * Bound to nothing: messages are published into them by name through the default
     * exchange, and leave by TTL expiry rather than by being consumed. Nothing ever
     * consumes these queues.
     */
    fun declareRetryQueues() {
        for (tier in RetryTier.entries) {
            channel.queueDeclare(
                tier.queueName,
                /* durable = */ true,
                /* exclusive = */ false,
                /* autoDelete = */ false,
                QueueArguments.retryQueue(tier)
            )
        }
    }
```

Call it from `declareExchanges()`'s caller in `RabbitConnectionImpl.connect()`, right after
`declareExchanges()`.

- [ ] **Step 3: Write the failing integration test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryQueueTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.retry

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.topology.QueueArguments
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.rabbitmq.common.topology.RabbitTopologyDeclarer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RequiresDocker
class RetryQueueTest {

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private lateinit var declarer: RabbitTopologyDeclarer

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("retry-test")
        channel = connection.createChannel()
        declarer = RabbitTopologyDeclarer(channel)
        declarer.declareExchanges()
        declarer.declareRetryQueues()
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `a message parked in a retry queue returns to its own service queue`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-return")
        val serviceQueue = declarer.declareServiceQueue(service)

        // Use a short-lived queue of our own so the test does not wait ten seconds.
        val fastRetry = "surf.retry.test-${System.nanoTime()}"
        channel.queueDeclare(
            fastRetry, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-message-ttl" to 1_000L,
                "x-dead-letter-exchange" to RabbitTopology.RPC_EXCHANGE
            )
        )

        // Publish with the service as routing key, into the retry queue by name.
        channel.basicPublish(
            "",
            fastRetry,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "retry-me".toByteArray()
        )

        // The message must not be in the service queue yet.
        assertEquals(
            null, channel.basicGet(serviceQueue, true),
            "the message should still be held in the retry queue"
        )

        val returned = awaitMessage(serviceQueue, timeoutMillis = 15_000)
        assertEquals(
            "retry-me", String(returned),
            "expiry must route the message back using its original routing key - if this " +
                    "fails, an x-dead-letter-routing-key was set somewhere"
        )
    }

    @Test
    fun `a returned message carries an incremented attempt count`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-count")
        val serviceQueue = declarer.declareServiceQueue(service)

        val fastRetry = "surf.retry.test-${System.nanoTime()}"
        channel.queueDeclare(
            fastRetry, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-message-ttl" to 1_000L,
                "x-dead-letter-exchange" to RabbitTopology.RPC_EXCHANGE
            )
        )

        channel.basicPublish(
            "", fastRetry,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "counted".toByteArray()
        )

        val response = awaitDelivery(serviceQueue, timeoutMillis = 15_000)
        val attempts = RetryPolicy.attemptsFrom(response.props.headers)

        assertTrue(
            attempts >= 1,
            "x-death must record the expiry so the ladder can advance, but attempts=$attempts"
        )
    }

    @Test
    fun `the declared retry queues carry the specified ttl`() {
        // Redeclaring with identical arguments succeeds; differing ones fail the channel.
        for (tier in RetryTier.entries) {
            val ok = channel.queueDeclare(
                tier.queueName, true, false, false, QueueArguments.retryQueue(tier)
            )
            assertNotNull(ok)
        }
    }

    @Test
    fun `retry queues do not pin the routing key`() {
        for (tier in RetryTier.entries) {
            assertEquals(
                null, QueueArguments.retryQueue(tier)["x-dead-letter-routing-key"],
                "setting it would send every retried message of every service to one queue"
            )
        }
    }

    private fun awaitMessage(queue: String, timeoutMillis: Long): ByteArray =
        awaitDelivery(queue, timeoutMillis).body

    private fun awaitDelivery(queue: String, timeoutMillis: Long): com.rabbitmq.client.GetResponse {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val response = channel.basicGet(queue, true)
            if (response != null) return response
            Thread.sleep(50)
        }

        throw AssertionError("no message arrived in '$queue' within ${timeoutMillis}ms")
    }
}
```

- [ ] **Step 4: Implement the publisher**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPublisher.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.retry

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Delivery
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology

/**
 * Moves a message whose handler failed either onto the retry ladder or into the dead-letter
 * queue.
 *
 * Republishing rather than `basicNack(requeue = true)` is deliberate: requeueing returns the
 * message to the head of its own queue for immediate redelivery, which spins at full CPU and
 * blocks every message behind it. Parking the message in a TTL queue delays the retry instead.
 */
class RetryPublisher(private val client: RabbitClient) {

    companion object {
        private val log = logger()
    }

    /**
     * Republishes the failed [delivery] and returns what was decided.
     *
     * The caller must `ack` the original delivery afterwards: the message now exists in
     * another queue, and leaving the original unacked would duplicate it.
     */
    suspend fun handleFailure(
        delivery: Delivery,
        serviceName: String,
        retryEnabled: Boolean
    ): RetryDecision {
        val attempts = RetryPolicy.attemptsFrom(delivery.properties.headers)
        val decision = RetryPolicy.decide(attempts, retryEnabled)

        when (decision) {
            is RetryDecision.Retry -> {
                log.atInfo().log(
                    "Retrying message for %s in %s (attempt %s of %s)",
                    serviceName, decision.tier.queueName, attempts + 1, RetryPolicy.MAX_RETRIES
                )

                // Published by queue name through the default exchange. The original routing
                // key is restored by the broker when the TTL expires.
                client.publish(
                    exchange = "",
                    routingKey = decision.tier.queueName,
                    body = delivery.body,
                    properties = preserveRoutingKey(delivery),
                    mandatory = false
                )
            }

            RetryDecision.DeadLetter -> {
                log.atWarning().log(
                    "Dead-lettering message for %s after %s attempts (retry enabled: %s)",
                    serviceName, attempts, retryEnabled
                )

                client.publish(
                    exchange = RabbitTopology.DLX_EXCHANGE,
                    routingKey = serviceName,
                    body = delivery.body,
                    properties = delivery.properties,
                    mandatory = false
                )
            }
        }

        return decision
    }

    /**
     * Copies the delivery properties, dropping `expiration`.
     *
     * A retried RPC request would otherwise expire inside the retry queue before its TTL
     * moved it back, and disappear without reaching the dead-letter queue.
     */
    private fun preserveRoutingKey(delivery: Delivery): AMQP.BasicProperties =
        delivery.properties.builder()
            .expiration(null)
            .build()
}
```

- [ ] **Step 5: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*RetryQueueTest*'`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

Without Docker: skipped, record as unverified.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(retry): declare shared retry queues and republish failed messages"
```

---

### Task 3: Wire retry into the consumers

Replaces the blanket `nack(requeue = false)` in the request and event paths with the retry decision.

**Files:**
- Modify: `surf-rabbitmq-core/.../connection/RabbitConnectionImpl.kt`
- Modify: `surf-rabbitmq-core/.../listener/RabbitListenerHandlerManager.kt`
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/handler/RabbitHandler.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryIntegrationTest.kt`

**Interfaces:**
- Consumes: `RetryPublisher` (Task 2)
- Produces: `annotation class RabbitHandler(val retry: Boolean = true)`

- [ ] **Step 1: Add the retry switch to the handler annotation**

Edit `RabbitHandler.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.handler

/**
 * Marks a method as a request handler.
 *
 * @property retry whether a failed invocation is retried on the 10s/60s/300s ladder before
 *   being dead-lettered. Set `false` for handlers that are not idempotent — a retried handler
 *   may run more than once for the same message.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitHandler(val retry: Boolean = true)
```

Record the flag alongside each handler in `RabbitListenerHandlerManager` and expose
`fun retryEnabledFor(requestClass: Class<*>): Boolean`.

- [ ] **Step 2: Replace the failure paths**

In `RabbitConnectionImpl`, every `ack.nack(requeue = false)` that follows a **handler failure**
becomes:

```kotlin
                retryPublisher.handleFailure(
                    delivery = message,
                    serviceName = api.identity.serviceName,
                    retryEnabled = listenerHandler.retryEnabledFor(request.javaClass)
                )
                ack.ack()
```

Leave the `nack(requeue = false)` in place for failures that retrying cannot fix — an
undeserialisable body or a message with no registered handler. Those go straight to the
dead-letter queue via the service queue's own `x-dead-letter-exchange`.

Apply the same change in the event consumer from Plan 3, using the subscription's `retry` flag.

- [ ] **Step 3: Write the integration test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryIntegrationTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.retry

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
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
class FailingPacket(val text: String) : RabbitRequestPacket<FailingResponse>()

@Serializable
class FailingResponse : RabbitResponsePacket()

@RequiresDocker
class RetryIntegrationTest {

    private val dataPath = Files.createTempDirectory("retry-integration")

    private class AlwaysFailing {
        val attempts = AtomicInteger()

        @RabbitHandler
        suspend fun onPacket(packet: FailingPacket) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    private class NeverRetried {
        val attempts = AtomicInteger()

        @RabbitHandler(retry = false)
        suspend fun onPacket(packet: FailingPacket) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    @Test
    fun `a handler marked retry=false is attempted once and dead-lettered`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("no-retry")
        val handler = NeverRetried()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the handler runs once") { handler.attempts.get() >= 1 }
            delay(3_000)

            assertEquals(
                1, handler.attempts.get(),
                "retry=false must not retry - more than one attempt means the flag is ignored"
            )

            val dlqDepth = messageCount(RabbitTopology.deadLetterQueue(service))
            assertEquals(
                1, dlqDepth,
                "the failed message must be preserved in the dead-letter queue, not dropped"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a retryable handler is attempted again after the first delay`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("retry")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("x"), RabbitTarget.ServiceTarget(service))

            awaitCondition("first attempt") { handler.attempts.get() >= 1 }

            // The first rung is ten seconds; allow margin for scheduling.
            awaitCondition("second attempt after the 10s tier", timeoutMillis = 25_000) {
                handler.attempts.get() >= 2
            }

            assertTrue(
                handler.attempts.get() >= 2,
                "the message must be redelivered after the retry TTL expires"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a message with no registered handler is dead-lettered, not lost`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("no-handler")

        // A server that hosts the queue but has no handler for this packet type.
        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(object {
            @RabbitHandler
            suspend fun unrelated(packet: FailingPacket) = Unit
        })
        server.freezeAndConnect()
        server.disconnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("orphan"), RabbitTarget.ServiceTarget(service))
            delay(2_000)

            assertEquals(
                1, messageCount(RabbitTopology.serviceQueue(service)),
                "with no consumer the message waits in the durable queue - it is not lost"
            )
        } finally {
            client.disconnect()
        }
    }

    private fun messageCount(queue: String): Int =
        RabbitBrokerExtension.newConnection("depth-check").use { connection ->
            connection.createChannel().use { channel ->
                channel.queueDeclarePassive(queue).messageCount
            }
        }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
```

- [ ] **Step 4: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*RetryIntegrationTest*'`
Expected: `BUILD SUCCESSFUL`, 3 tests passed. The retry test takes ~25 s by design.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(retry): route handler failures through the retry ladder to the DLQ

Failed messages are no longer discarded by nack(requeue=false); they are
retried three times and then preserved in the service's dead-letter queue."
```

---

### Task 4: Fail fast on unroutable publishes

Turns a message to a nonexistent service into an immediate exception rather than a request timeout.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/ReturnListenerBridge.kt`
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/exception/SurfRabbitServiceUnavailableException.kt`
- Modify: `surf-rabbitmq-core/.../connection/publisher/RabbitPublisher.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/UnroutableTest.kt`

**Interfaces:**
- Consumes: `RabbitTarget` (Plan 2)
- Produces:
  ```kotlin
  class SurfRabbitServiceUnavailableException(val target: String, val replyText: String)
      : SurfRabbitRequestException

  class ReturnListenerBridge {
      fun install(channel: Channel)
      fun register(correlationId: String)
      fun unregister(correlationId: String)
      fun returnedReason(correlationId: String): String?
  }
  ```

- [ ] **Step 1: Add the exception**

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/exception/SurfRabbitServiceUnavailableException.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.exception

import java.io.Serial

/**
 * The broker could not route a message: no queue is bound for the target.
 *
 * This means the service was never deployed or the name is misspelled — **not** that it is
 * temporarily down. A service whose instances have all stopped still has its durable queue, so
 * its messages wait there instead of being returned.
 */
class SurfRabbitServiceUnavailableException(
    val target: String,
    val replyText: String
) : SurfRabbitRequestException(
    "No service is registered for '$target' ($replyText). " +
            "Check the service name; a service that is merely offline would still have a queue."
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}
```

- [ ] **Step 2: Implement the bridge**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/ReturnListenerBridge.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.connection

import com.rabbitmq.client.Channel
import dev.slne.surf.api.core.util.logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces messages the broker sent back as unroutable.
 *
 * `mandatory = true` makes the broker return such a message instead of dropping it, but the
 * return arrives asynchronously on the channel and is invisible to the publisher unless
 * something listens. Without this bridge a caller would wait out the full request timeout for
 * a message that was rejected within milliseconds.
 */
class ReturnListenerBridge {

    companion object {
        private val log = logger()
    }

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val returned = ConcurrentHashMap<String, String>()

    /** Installs the listener on [channel]. Call once per publisher channel. */
    fun install(channel: Channel) {
        channel.addReturnListener { replyCode, replyText, _, routingKey, properties, _ ->
            val correlationId = properties?.correlationId

            log.atWarning().log(
                "Message to '%s' was returned as unroutable: %s %s", routingKey, replyCode, replyText
            )

            if (correlationId != null && pending.contains(correlationId)) {
                returned[correlationId] = "$replyCode $replyText"
            }
        }
    }

    /** Starts watching for a return of [correlationId]. */
    fun register(correlationId: String) {
        pending += correlationId
    }

    /** Stops watching and clears any recorded return. */
    fun unregister(correlationId: String) {
        pending -= correlationId
        returned.remove(correlationId)
    }

    /** The broker's reason if this message was returned, otherwise `null`. */
    fun returnedReason(correlationId: String): String? = returned[correlationId]
}
```

- [ ] **Step 3: Use it in the request path**

In `RabbitConnectionImpl.awaitResponse`, after registering the pending request:

```kotlin
            returnListener.register(correlationId)
```

and race the return against the reply:

```kotlin
            val received = withTimeoutOrNull(requestTimeoutSeconds) {
                while (true) {
                    // A returned message means the target does not exist. Failing here saves
                    // the caller the full request timeout.
                    returnListener.returnedReason(correlationId)?.let { reason ->
                        throw SurfRabbitServiceUnavailableException(target.routingKey, reason)
                    }

                    if (deferred.isCompleted) return@withTimeoutOrNull deferred.await()
                    delay(10)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
```

Call `returnListener.unregister(correlationId)` in the existing `finally` block.

Install the bridge on each publisher channel in `RabbitPublisher.getChannel`:

```kotlin
        return connectionProvider
            .createChannel(expectedGeneration)
            .also { created ->
                if (options.confirmPublishes) {
                    created.confirmSelect()
                }
                returnListener?.install(created)

                channel = created
            }
```

- [ ] **Step 4: Write the test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/UnroutableTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.connection

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.rabbitmq.core.EchoPacket
import dev.slne.surf.rabbitmq.core.EchoResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RequiresDocker
class UnroutableTest {

    private val dataPath = Files.createTempDirectory("unroutable-test")

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 30)).build()

    @Test
    fun `a request to an unknown service fails fast, not after the timeout`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            val start = System.currentTimeMillis()

            assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.connection.sendRequest(
                    EchoPacket("x"),
                    EchoResponse::class.java,
                    RabbitTarget.ServiceTarget("nonexistent-${System.nanoTime()}")
                )
            }

            val elapsed = System.currentTimeMillis() - start
            assertTrue(
                elapsed < 5_000,
                "the return listener should fail this in milliseconds, but it took ${elapsed}ms " +
                        "- it is falling through to the 30s request timeout"
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `the exception names the target that could not be reached`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()
        val target = "nonexistent-${System.nanoTime()}"

        try {
            val thrown = assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.connection.sendRequest(
                    EchoPacket("x"), EchoResponse::class.java, RabbitTarget.ServiceTarget(target)
                )
            }

            assertTrue(thrown.target == target, "the message must name the target to be useful")
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `an unroutable message is also preserved in the unroutable queue`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            runCatching {
                client.connection.sendRequest(
                    EchoPacket("x"),
                    EchoResponse::class.java,
                    RabbitTarget.ServiceTarget("nonexistent-${System.nanoTime()}")
                )
            }

            delay(1_000)

            val depth = RabbitBrokerExtension.newConnection("depth").use { connection ->
                connection.createChannel().use { channel ->
                    channel.queueDeclarePassive(RabbitTopology.UNROUTABLE_QUEUE).messageCount
                }
            }

            assertTrue(
                depth >= 1,
                "the alternate exchange must keep a copy so a misrouted message can be diagnosed"
            )
        } finally {
            client.disconnect()
        }
    }
}
```

- [ ] **Step 5: Run and re-enable the deferred test**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*UnroutableTest*'`
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

Remove the `@Disabled("return listener lands in Plan 4")` marker from
`RpcRoundTripTest.a request to an unknown service fails fast instead of timing out` if Plan 2
added it, and confirm it now passes.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(connection): fail fast on unroutable messages via return listener

A request to a service that does not exist now throws immediately instead
of waiting out the full request timeout."
```

---

### Task 5: Per-service circuit breakers

Wires `surf-circuitbreaker` in so one dead service cannot slow down calls to healthy ones.

**Files:**
- Modify: `surf-rabbitmq-core/build.gradle.kts`
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/rpc/BreakerGuardedRpc.kt`
- Modify: `surf-rabbitmq-core/.../connection/RabbitConnectionImpl.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/rpc/BreakerGuardedRpcTest.kt`

**Interfaces:**
- Consumes: `CircuitBreakerRegistry` (Plan 1), `SurfRabbitServiceUnavailableException` (Task 4)
- Produces:
  ```kotlin
  class BreakerGuardedRpc(
      private val registry: CircuitBreakerRegistry,
      private val maxAttempts: Int = 3,
      private val retryDelays: List<Duration> = listOf(250.milliseconds, 1.seconds)
  ) {
      suspend fun <T> call(target: RabbitTarget, block: suspend () -> T): T
  }
  ```

- [ ] **Step 1: Depend on the breaker module**

Add to `surf-rabbitmq-core/build.gradle.kts`:

```kotlin
    api(projects.surfCircuitbreaker)
```

- [ ] **Step 2: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/rpc/BreakerGuardedRpcTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.rpc

import dev.slne.surf.circuitbreaker.CircuitBreakerRegistry
import dev.slne.surf.circuitbreaker.CircuitOpenException
import dev.slne.surf.circuitbreaker.CircuitState
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitServiceUnavailableException
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class BreakerGuardedRpcTest {

    private class BusinessError : RuntimeException("not found")

    private fun guarded(registry: CircuitBreakerRegistry) = BreakerGuardedRpc(
        registry = registry,
        maxAttempts = 3,
        retryDelays = listOf(1.milliseconds, 1.milliseconds)
    )

    private fun registry() = CircuitBreakerRegistry(
        failureThreshold = 2,
        isFailure = { it is SurfRabbitServiceUnavailableException }
    )

    @Test
    fun `a successful call returns its result`() = runTest {
        val result = guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `a transport failure is retried`() = runTest {
        val attempts = AtomicInteger()

        val result = guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) {
            if (attempts.incrementAndGet() < 3) {
                throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
            }
            "recovered"
        }

        assertEquals("recovered", result)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `a business exception is not retried`() = runTest {
        val attempts = AtomicInteger()

        assertFailsWith<BusinessError> {
            guarded(registry()).call(RabbitTarget.ServiceTarget("svc")) {
                attempts.incrementAndGet()
                throw BusinessError()
            }
        }

        assertEquals(
            1, attempts.get(),
            "retrying a business error just repeats a decision the service already made"
        )
    }

    @Test
    fun `a business exception does not open the breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(10) {
            assertFailsWith<BusinessError> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) { throw BusinessError() }
            }
        }

        assertEquals(
            CircuitState.CLOSED, registry.forName("svc").state,
            "a service answering with errors is alive and must not be cut off"
        )
    }

    @Test
    fun `repeated transport failures open the breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            assertFailsWith<SurfRabbitServiceUnavailableException> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
                }
            }
        }

        assertEquals(CircuitState.OPEN, registry.forName("svc").state)
    }

    @Test
    fun `an open breaker rejects without calling`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            runCatching {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    throw SurfRabbitServiceUnavailableException("svc", "NO_ROUTE")
                }
            }
        }

        val attempts = AtomicInteger()
        assertFailsWith<CircuitOpenException> {
            rpc.call(RabbitTarget.ServiceTarget("svc")) { attempts.incrementAndGet(); "never" }
        }

        assertEquals(0, attempts.get())
    }

    @Test
    fun `one dead service does not affect another`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        repeat(2) {
            runCatching {
                rpc.call(RabbitTarget.ServiceTarget("dead")) {
                    throw SurfRabbitServiceUnavailableException("dead", "NO_ROUTE")
                }
            }
        }

        assertEquals(CircuitState.OPEN, registry.forName("dead").state)
        assertEquals(
            "ok", rpc.call(RabbitTarget.ServiceTarget("healthy")) { "ok" },
            "a per-service breaker must isolate failures; a global one would not"
        )
    }

    @Test
    fun `instance targets get their own breaker`() = runTest {
        val registry = registry()
        val rpc = guarded(registry)

        rpc.call(RabbitTarget.InstanceTarget("lobby-3")) { "ok" }

        assertEquals(setOf("lobby-3"), registry.names())
    }
}
```

- [ ] **Step 3: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*BreakerGuardedRpcTest*'`
Expected: `Unresolved reference: BreakerGuardedRpc`.

- [ ] **Step 4: Implement**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/rpc/BreakerGuardedRpc.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.rpc

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.circuitbreaker.CircuitBreakerRegistry
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestException
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Guards outgoing calls with a per-target circuit breaker and a short retry.
 *
 * Retrying and breaking apply only to **transport** failures — the target could not be reached.
 * An exception the service deliberately threw is passed straight through: repeating it would
 * only re-run a decision the service already made, and counting it would cut off a service
 * that is demonstrably alive.
 *
 * The breaker is per target, so an unreachable `surf-punish` never delays calls to
 * `surf-factions`.
 */
class BreakerGuardedRpc(
    private val registry: CircuitBreakerRegistry,
    private val maxAttempts: Int = 3,
    private val retryDelays: List<Duration> = listOf(250.milliseconds, 1.seconds)
) {
    companion object {
        private val log = logger()
    }

    /**
     * Runs [block] under the breaker for [target], retrying transport failures.
     *
     * @throws dev.slne.surf.circuitbreaker.CircuitOpenException if the breaker rejected the call
     */
    suspend fun <T> call(target: RabbitTarget, block: suspend () -> T): T {
        val breaker = registry.forName(target.routingKey)

        return breaker.withBreaker {
            var lastFailure: Throwable? = null

            repeat(maxAttempts) { attempt ->
                try {
                    return@withBreaker block()
                } catch (cause: Throwable) {
                    if (cause is CancellationException) throw cause

                    // Only a transport failure is worth another attempt.
                    if (!isTransportFailure(cause)) throw cause

                    lastFailure = cause

                    if (attempt < maxAttempts - 1) {
                        val backoff = retryDelays.getOrElse(attempt) { retryDelays.last() }

                        log.atFine().log(
                            "Retrying call to %s after %s (attempt %s of %s)",
                            target.routingKey, backoff, attempt + 1, maxAttempts
                        )

                        delay(backoff)
                    }
                }
            }

            throw lastFailure ?: IllegalStateException("retry loop ended without a failure")
        }
    }

    /**
     * Whether [cause] means the target was unreachable.
     *
     * Deliberately narrow: anything not recognised is treated as a business failure and left
     * alone, because wrongly retrying a state-changing call is worse than not retrying a
     * transport error.
     */
    private fun isTransportFailure(cause: Throwable): Boolean =
        cause is SurfRabbitRequestException
}
```

- [ ] **Step 5: Wire it into the connection**

In `RabbitConnectionImpl`:

```kotlin
    private val breakerRegistry = CircuitBreakerRegistry(
        failureThreshold = 5,
        openDuration = 30.seconds,
        isFailure = { it is SurfRabbitRequestException }
    )

    private val guardedRpc = BreakerGuardedRpc(breakerRegistry)
```

and wrap the request path:

```kotlin
    override suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R = guardedRpc.call(target) {
        // existing body
    }
```

- [ ] **Step 6: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*BreakerGuardedRpcTest*'`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(rpc): guard calls with per-service circuit breakers

A per-target breaker keeps one unreachable service from slowing calls to
healthy ones. Business exceptions neither retry nor trip the breaker."
```

---

### Task 6: KSP support for `@RpcService(service = ...)`

Lets the target service be declared on the interface, as decided during design.

**Files:**
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/rpc/RpcService.kt`
- Modify: `surf-rabbitmq-ksp/src/main/kotlin/dev/slne/surf/rabbitmq/processor/rpc/model/RpcServiceModel.kt`
- Modify: `surf-rabbitmq-ksp/src/main/kotlin/dev/slne/surf/rabbitmq/processor/rpc/model/RpcServiceModelFactory.kt`
- Modify: `surf-rabbitmq-ksp/src/main/kotlin/dev/slne/surf/rabbitmq/processor/rpc/codegen/RpcDescriptorCodegen.kt`
- Modify: `surf-rabbitmq-api/.../rpc/descriptor/RabbitRpcServiceDescriptor.kt`

**Interfaces:**
- Consumes: `SurfRabbitApi.rpc` (Plan 2)
- Produces:
  - `annotation class RpcService(val service: String = "")`
  - `RabbitRpcServiceDescriptor.defaultService: String`

- [ ] **Step 1: Add the annotation parameter**

In `RpcService.kt`, change the declaration to:

```kotlin
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class RpcService(
    /**
     * The service that hosts this contract, e.g. `surf-factions`.
     *
     * Declaring it here rather than at each call site means a typo is a single fix, and a
     * client can hold proxies for many services without repeating names.
     *
     * Leave empty to require an explicit service argument at `rpc(...)`.
     */
    val service: String = ""
)
```

Keep the existing KDoc body and add an example:

```kotlin
 * ```kotlin
 * @RpcService(service = "surf-factions")
 * interface FactionService {
 *     suspend fun findFaction(player: UUID): Faction?
 * }
 *
 * val factions = rabbit.rpc<FactionService>()
 * val staging  = rabbit.rpc<FactionService>(service = "surf-factions-staging")
 * ```
```

- [ ] **Step 2: Carry it through the model**

In `RpcServiceModel`, add `val defaultService: String`.

In `RpcServiceModelFactory`, read the annotation argument:

```kotlin
        val defaultService = declaration.annotations
            .firstOrNull { it.shortName.asString() == "RpcService" }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "service" }
            ?.value as? String
            ?: ""
```

- [ ] **Step 3: Emit it in the descriptor**

In `RpcDescriptorCodegen`, add to the generated object:

```kotlin
        .addProperty(
            PropertySpec.builder("defaultService", String::class)
                .addModifiers(KModifier.OVERRIDE)
                .initializer("%S", model.defaultService)
                .build()
        )
```

Add the member to `RabbitRpcServiceDescriptor`:

```kotlin
    /**
     * The service declared by `@RpcService(service = ...)`, or an empty string if none was
     * given, in which case `rpc(...)` requires an explicit service.
     */
    val defaultService: String
```

- [ ] **Step 4: Resolve the target when creating a proxy**

In `ClientRpcServiceImpl.createService`:

```kotlin
    override fun <Service : Any> createService(
        serviceKClass: KClass<Service>,
        service: String?
    ): Service {
        val descriptor = serviceDescriptorOf(serviceKClass)

        val target = service
            ?: descriptor.defaultService.ifBlank {
                error(
                    "No target service for ${descriptor.fqName}. Either annotate the interface " +
                            "with @RpcService(service = \"...\") or pass rpc(service = \"...\")."
                )
            }

        val id = serviceIdCounter.incrementAndGet()

        return descriptor.createInstance(id, api, RabbitTarget.ServiceTarget(target))
    }
```

Thread the target through `RabbitRpcCall` so `ClientRpcServiceImpl.call` publishes to it.

- [ ] **Step 5: Verify with the test module**

Annotate the test service:

```kotlin
@RpcService(service = "surf-rabbitmq-test")
interface RabbitMqTestRpcService {
```

Run: `./gradlew :surf-rabbitmq-test:surf-rabbitmq-test-common:build`
Expected: `BUILD SUCCESSFUL`, generated descriptor contains `defaultService = "surf-rabbitmq-test"`.

Inspect: `surf-rabbitmq-test/surf-rabbitmq-test-common/build/generated/ksp/main/kotlin/.../RabbitMqTestRpcServiceDescriptor.kt`

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(ksp): declare the target service on @RpcService

The generated descriptor carries the default service, so rpc<T>() needs no
service argument unless the target is being overridden."
```

---

### Task 7: Migrate the test module

The only in-repo consumer. Migrating it proves the new API is usable and gives an end-to-end check.

**Files:**
- Modify: `surf-rabbitmq-test/surf-rabbitmq-test-paper/.../RabbitMqTestPaperInstance.kt`
- Modify: `surf-rabbitmq-test/surf-rabbitmq-test-server/.../RabbitMqTestMicroservice.kt`
- Modify: `surf-rabbitmq-test/surf-rabbitmq-test-common/.../RabbitMqTestCommonInstance.kt`
- Create: `surf-rabbitmq-test/surf-rabbitmq-test-common/.../event/TestBroadcastEvent.kt`

**Interfaces:**
- Consumes: everything above
- Produces: nothing

- [ ] **Step 1: Migrate the common instance**

```kotlin
package dev.slne.surf.rabbitmq.test

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import org.jetbrains.annotations.MustBeInvokedByOverriders

abstract class RabbitMqTestCommonInstance {
    lateinit var api: SurfRabbitApi

    @MustBeInvokedByOverriders
    open suspend fun onLoad() = Unit

    @MustBeInvokedByOverriders
    open suspend fun onEnable() = Unit

    @MustBeInvokedByOverriders
    open suspend fun onDisable() = Unit

    companion object {
        val instance = requiredService<RabbitMqTestCommonInstance>()
        fun get(): RabbitMqTestCommonInstance = instance
    }
}
```

- [ ] **Step 2: Migrate the Paper side and add an event listener**

```kotlin
@AutoService(RabbitMqTestCommonInstance::class)
class RabbitMqTestPaperInstance : RabbitMqTestCommonInstance() {
    override suspend fun onLoad() {
        super.onLoad()

        api = SurfRabbitApi.builder("surf-rabbitmq-test-paper", plugin.dataPath).build()

        // Every Paper server invalidates its own state, so BROADCAST is right here.
        api.registerListener(TestBroadcastListener)
        api.freezeAndConnect()
    }

    override suspend fun onDisable() {
        super.onDisable()
        api.disconnect()
    }

    companion object {
        fun get() = RabbitMqTestCommonInstance.instance as RabbitMqTestPaperInstance
    }
}

object TestBroadcastListener {
    @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
    suspend fun onBroadcast(event: TestBroadcastEvent) {
        logger().atInfo().log("Received broadcast: %s", event.message)
    }
}

val rabbitMqApi get() = RabbitMqTestPaperInstance.get().api
```

Note the service name changed to `surf-rabbitmq-test-paper`: the Paper process and the
microservice are now distinct services, which is the point of the redesign. The Paper side
reaches the microservice through `@RpcService(service = "surf-rabbitmq-test")`.

- [ ] **Step 3: Migrate the microservice**

```kotlin
@AutoService(Microservice::class)
class RabbitMqTestMicroservice : Microservice() {
    override val dataPath = Path("config")

    private val rabbitApi = SurfRabbitApi.builder("surf-rabbitmq-test", dataPath).build()

    override suspend fun onBootstrap(args: List<String>) {
        rabbitApi.registerService<RabbitMqTestRpcService>(RabbitMqTestRpcServerImpl)
        rabbitApi.registerRequestHandler(TestRabbitMqHandler)
        rabbitApi.freezeAndConnect()
    }

    override suspend fun onDisable() {
        rabbitApi.disconnect()
    }
}
```

- [ ] **Step 4: Add the test event**

```kotlin
package dev.slne.surf.rabbitmq.test.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import kotlinx.serialization.Serializable

@Serializable
@RabbitEvent("test.broadcast")
class TestBroadcastEvent(val message: String) : RabbitEventPacket()
```

- [ ] **Step 5: Build everything**

Run: `./gradlew build -PskipIntegration`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(test): migrate the test module to SurfRabbitApi

Paper and the microservice are now separate services, which is what the
redesign makes possible."
```

---

### Task 8: Chunking coverage

Chunking has shipped since 1.6 without a single test. This plan changes the publish path around it, so it needs coverage before we can claim the change is safe.

**Files:**
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/packet/ChunkingTest.kt`

- [ ] **Step 1: Write the tests**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/packet/ChunkingTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.packet

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

@Serializable
class LargePacket(val payload: String) : RabbitRequestPacket<LargeResponse>()

@Serializable
class LargeResponse(val payload: String) : RabbitResponsePacket()

@RequiresDocker
class ChunkingTest {

    private val dataPath = Files.createTempDirectory("chunking-test")

    private object EchoLarge {
        @RabbitHandler
        suspend fun onLarge(packet: LargePacket) {
            packet.respond(LargeResponse(packet.payload))
        }
    }

    @Test
    fun `a payload larger than one chunk survives the round trip`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(EchoLarge)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            // Comfortably beyond the chunk threshold so splitting is exercised.
            val payload = buildString { repeat(2_000_000) { append('x') } }

            val response = client.connection.sendRequest(
                LargePacket(payload), LargeResponse::class.java, RabbitTarget.ServiceTarget(service)
            )

            assertEquals(
                payload.length, response.payload.length,
                "a truncated payload means chunks were dropped or reassembled out of order"
            )
            assertEquals(payload, response.payload)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `many concurrent large payloads do not interleave`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-concurrent")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(EchoLarge)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val responses = (1..5).map { n ->
                kotlinx.coroutines.async {
                    val payload = n.toString().repeat(500_000)
                    val response = client.connection.sendRequest(
                        LargePacket(payload),
                        LargeResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                    payload to response.payload
                }
            }.map { it.await() }

            responses.forEach { (sent, received) ->
                assertEquals(
                    sent, received,
                    "mismatched payloads mean chunks of different messages were mixed up"
                )
            }
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }
}
```

- [ ] **Step 2: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*ChunkingTest*'`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

The test config sets `isOutgoingRequestChunkingEnabled() = false`; add a variant returning
`true` so the request path is exercised too.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(packet): cover chunked request and response round trips"
```

---

### Task 9: Broker restart and queue overflow

The two remaining spec tests. Both cover failure modes that only appear under conditions no unit test reproduces: the connection dying mid-flight, and a queue actually filling up.

**Files:**
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/BrokerRestartTest.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/QueueOverflowTest.kt`

**Interfaces:**
- Consumes: everything above
- Produces: nothing

- [ ] **Step 1: Write the restart test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/BrokerRestartTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.connection

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.core.EchoPacket
import dev.slne.surf.rabbitmq.core.EchoResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Recovery after the connection drops.
 *
 * The reply queue is `autoDelete`, so it disappears with the connection and is recreated under
 * a new name. Anything that cached the old name would go silently deaf: requests would be sent
 * and answers delivered to a queue nobody reads.
 */
@RequiresDocker
class BrokerRestartTest {

    private val dataPath = Files.createTempDirectory("restart-test")

    private object EchoHandler {
        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    private fun api(service: String) =
        SurfRabbitApi.builder(service, dataPath).config(testConfig(requestTimeoutSeconds = 20)).build()

    @Test
    fun `rpc works again after the connection is dropped and recovered`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("restart")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            assertEquals(
                "echo:before",
                client.connection.sendRequest(
                    EchoPacket("before"), EchoResponse::class.java, RabbitTarget.ServiceTarget(service)
                ).text
            )

            // Kill the underlying connections and let automatic recovery rebuild them.
            RabbitBrokerExtension.closeAllConnections()

            val recovered = withTimeoutOrNull(60_000) {
                while (true) {
                    val result = runCatching {
                        client.connection.sendRequest(
                            EchoPacket("after"),
                            EchoResponse::class.java,
                            RabbitTarget.ServiceTarget(service)
                        ).text
                    }.getOrNull()

                    if (result != null) return@withTimeoutOrNull result
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") null
            }

            assertEquals(
                "echo:after", recovered,
                "after recovery the client must consume its NEW reply queue - if this times " +
                        "out, the old auto-deleted queue name is still being used"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `requests in flight during a drop do not hang forever`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("inflight")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()

            val outcome = kotlinx.coroutines.async {
                runCatching {
                    client.connection.sendRequest(
                        EchoPacket("in-flight"),
                        EchoResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }

            delay(50)
            RabbitBrokerExtension.closeAllConnections()

            outcome.await()
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 40_000,
                "a request interrupted by a connection loss must settle - either completing " +
                        "after recovery or failing - but it took ${elapsed}ms"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }
}
```

- [ ] **Step 2: Add the connection-killing helper**

Append to `RabbitBrokerExtension`:

```kotlin
    /**
     * Force-closes every client connection through the management API.
     *
     * Simulates a broker outage without restarting the container, which would also wipe the
     * durable state the recovery is supposed to find intact.
     */
    fun closeAllConnections() {
        val client = java.net.http.HttpClient.newHttpClient()
        val credentials = java.util.Base64.getEncoder()
            .encodeToString("${adminUsername()}:${adminPassword()}".toByteArray())

        val list = client.send(
            java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("${managementUrl()}/api/connections"))
                .header("Authorization", "Basic $credentials")
                .GET()
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString()
        ).body()

        // Minimal extraction: the management API returns a JSON array of connection objects.
        Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(list)
            .map { it.groupValues[1] }
            .distinct()
            .forEach { name ->
                runCatching {
                    client.send(
                        java.net.http.HttpRequest.newBuilder()
                            .uri(
                                java.net.URI.create(
                                    "${managementUrl()}/api/connections/" +
                                            java.net.URLEncoder.encode(name, Charsets.UTF_8)
                                )
                            )
                            .header("Authorization", "Basic $credentials")
                            .DELETE()
                            .build(),
                        java.net.http.HttpResponse.BodyHandlers.discarding()
                    )
                }
            }
    }
```

- [ ] **Step 3: Write the overflow test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/connection/QueueOverflowTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.connection

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import dev.slne.surf.rabbitmq.common.topology.RabbitTopologyDeclarer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Overflow behaviour of a full service queue.
 *
 * `reject-publish` is what turns "a service has been down for days" from a broker-wide memory
 * problem into a local, visible publish failure. The default (`drop-head`) would instead
 * discard the oldest messages silently.
 */
@RequiresDocker
class QueueOverflowTest {

    private lateinit var connection: Connection
    private lateinit var channel: Channel

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("overflow-test")
        channel = connection.createChannel()
        RabbitTopologyDeclarer(channel).declareExchanges()
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `a full queue rejects new publishes instead of dropping old messages`() {
        val service = RabbitBrokerExtension.uniqueServiceName("overflow")
        val queue = RabbitTopology.serviceQueue(service)

        // A tiny bound so the queue fills in a handful of messages.
        channel.queueDeclare(
            queue, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
                "x-max-length" to 5L,
                "x-overflow" to "reject-publish"
            )
        )
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, service)

        channel.confirmSelect()

        val body = "x".repeat(100).toByteArray()
        val properties = AMQP.BasicProperties.Builder().deliveryMode(2).build()

        repeat(5) {
            channel.basicPublish(RabbitTopology.RPC_EXCHANGE, service, properties, body)
        }
        channel.waitForConfirmsOrDie(5_000)

        // The sixth exceeds the bound and must be nacked rather than silently accepted.
        channel.basicPublish(RabbitTopology.RPC_EXCHANGE, service, properties, body)

        val rejected = runCatching { channel.waitForConfirmsOrDie(5_000) }.isFailure

        assertTrue(
            rejected,
            "reject-publish must nack the publisher once the queue is full. If this passes " +
                    "silently, the overflow policy is missing and the oldest messages are " +
                    "being discarded without anyone noticing"
        )

        assertTrue(
            channel.queueDeclarePassive(queue).messageCount <= 5,
            "the queue must stay within its bound"
        )
    }

    @Test
    fun `the earlier messages survive the rejection`() {
        val service = RabbitBrokerExtension.uniqueServiceName("overflow-keep")
        val queue = RabbitTopology.serviceQueue(service)

        channel.queueDeclare(
            queue, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
                "x-max-length" to 3L,
                "x-overflow" to "reject-publish"
            )
        )
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, service)

        val properties = AMQP.BasicProperties.Builder().deliveryMode(2).build()
        repeat(3) { n ->
            channel.basicPublish(
                RabbitTopology.RPC_EXCHANGE, service, properties, "msg-$n".toByteArray()
            )
        }

        runCatching {
            channel.basicPublish(
                RabbitTopology.RPC_EXCHANGE, service, properties, "overflow".toByteArray()
            )
        }

        Thread.sleep(500)

        // The first message must still be msg-0: reject-publish protects the head of the
        // queue, unlike drop-head which would have discarded it.
        val first = channel.basicGet(queue, true)
        assertTrue(
            first != null && String(first.body) == "msg-0",
            "the oldest message must be preserved, but was ${first?.let { String(it.body) }}"
        )
    }
}
```

- [ ] **Step 4: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*BrokerRestartTest*' --tests '*QueueOverflowTest*'`
Expected: `BUILD SUCCESSFUL`, 4 tests passed. The restart test takes up to a minute.

If `rpc works again after the connection is dropped` times out, the client is still publishing
`replyTo` with the pre-recovery queue name. Check that `onQueueRecovered` /
`onRecoveryCompleted` update `replyEndpoint` with the new name, and that `awaitResponse` reads
it fresh rather than capturing it once.

Without Docker: skipped, record as unverified.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(connection): cover broker restart recovery and queue overflow"
```

---

### Task 10: Fix chunk series mixing (bug)

A latent defect that this plan's retry machinery would activate. Chunks are grouped by `correlationId` alone, so a second attempt at the same message can have its chunks merged with the leftovers of a first, aborted attempt.

**Files:**
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/packet/RabbitPacketChunking.kt`
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/packet/RabbitPacketChunkAssembler.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/packet/ChunkSeriesTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `PacketChunk` gains `val seriesId: Long`
  - `RabbitPacketChunking.split*` stamps a fresh series id per call
  - The assembler keys partial packets by `correlationId` **and** `seriesId`

**The defect.** `RabbitPacketChunkAssembler.getOrCreatePartial` looks up by `correlationId`
only, and `PartialPacket.add` uses `compareAndSet(index, null, payload)`, which keeps whichever
chunk arrived first. If a service dies after sending some but not all chunks, the message is
redelivered and a second service produces a fresh series under the same `correlationId`. The
already-filled slots keep the *old* chunks and the missing ones are filled from the *new*
series.

`validateMetadata` does not catch this: it compares `totalChunks` and `originalSize`, which are
identical whenever both attempts serialise to the same length. `RabbitPacket.timestamp` is
`OffsetDateTime.now()`, so two attempts differ in content while keeping the same length — the
assembled packet passes every size check and is still garbage.

Wire compatibility with 1.6.x is already out of scope, so extending the chunk header is free.

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/packet/ChunkSeriesTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.packet

import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunkAssembler
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChunkSeriesTest {

    private fun assembler() = RabbitPacketChunkAssembler(
        expectedKind = RabbitPacketChunking.PacketChunkKind.RESPONSE,
        timeout = 60.seconds
    )

    /** Distinct content of the same length, mimicking two attempts differing only by timestamp. */
    private fun payload(fill: Char, size: Int = 1_500_000) = ByteArray(size) { fill.code.toByte() }

    @Test
    fun `each split gets its own series id`() {
        val a = RabbitPacketChunking.splitResponse(payload('a'))
        val b = RabbitPacketChunking.splitResponse(payload('a'))

        val seriesA = RabbitPacketChunking.decodeOrNull(a[0])!!.seriesId
        val seriesB = RabbitPacketChunking.decodeOrNull(b[0])!!.seriesId

        assertTrue(
            seriesA != seriesB,
            "two separate splits must be distinguishable, otherwise their chunks can be mixed"
        )
    }

    @Test
    fun `all chunks of one split share a series id`() {
        val chunks = RabbitPacketChunking.splitResponse(payload('a'))
        val ids = chunks.map { RabbitPacketChunking.decodeOrNull(it)!!.seriesId }.toSet()

        assertEquals(1, ids.size, "one split is one series")
    }

    @Test
    fun `a partial series is not completed by chunks of a different series`() {
        val assembler = assembler()
        val correlationId = "srq1:test-1"

        val first = RabbitPacketChunking.splitResponse(payload('a'))
        val second = RabbitPacketChunking.splitResponse(payload('b'))

        assertTrue(first.size >= 3, "the payload must span several chunks for this test")

        // First attempt sends all but the last chunk, then the service dies.
        for (i in 0 until first.size - 1) {
            assertEquals(
                RabbitPacketChunkAssembler.ChunkAcceptResult.Stored,
                assembler.accept(correlationId, first[i])
            )
        }

        // Second attempt sends a complete series under the same correlation id.
        var completed: RabbitPacketChunkAssembler.ChunkAcceptResult? = null
        for (chunk in second) {
            completed = assembler.accept(correlationId, chunk)
        }

        val result = completed
        assertTrue(
            result is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete,
            "the second, complete series must assemble on its own"
        )

        assertTrue(
            result.body.all { it == 'b'.code.toByte() },
            "the assembled packet must come entirely from the second series - any 'a' byte " +
                    "means chunks of two different responses were merged into one packet"
        )
    }

    @Test
    fun `an abandoned series does not block a later one`() {
        val assembler = assembler()
        val correlationId = "srq1:test-2"

        val abandoned = RabbitPacketChunking.splitResponse(payload('a'))
        assembler.accept(correlationId, abandoned[0])

        val complete = RabbitPacketChunking.splitResponse(payload('b'))
        var last: RabbitPacketChunkAssembler.ChunkAcceptResult? = null
        for (chunk in complete) {
            last = assembler.accept(correlationId, chunk)
        }

        assertTrue(last is RabbitPacketChunkAssembler.ChunkAcceptResult.Complete)
    }

    @Test
    fun `duplicate chunks of the same series are still idempotent`() {
        val assembler = assembler()
        val correlationId = "srq1:test-3"
        val chunks = RabbitPacketChunking.splitResponse(payload('a'))

        for (chunk in chunks) assembler.accept(correlationId, chunk)

        // A redelivered duplicate of an already-assembled series must not resurrect it.
        val afterComplete = assembler.accept(correlationId, chunks[0])
        assertEquals(RabbitPacketChunkAssembler.ChunkAcceptResult.Stored, afterComplete)
    }

    @Test
    fun `a single-chunk message still round-trips`() {
        val assembler = assembler()
        val small = ByteArray(1024) { 'x'.code.toByte() }

        // Below the threshold, so it is never chunked and must pass through untouched.
        assertEquals(
            RabbitPacketChunkAssembler.ChunkAcceptResult.NotChunk,
            assembler.accept("srq1:test-4", small)
        )
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*ChunkSeriesTest*'`
Expected: `a partial series is not completed by chunks of a different series` fails, either on
the `'b'` assertion (chunks were merged) or on compilation (`seriesId` does not exist).

This failure is the bug. Confirm you see it before fixing.

- [ ] **Step 3: Add the series id to the wire format**

In `RabbitPacketChunking`, extend the header:

```kotlin
    private const val CHUNK_HEADER_SIZE =
        Int.SIZE_BYTES + // magic
                1 + // version
                1 + // kind
                Long.SIZE_BYTES + // seriesId
                Int.SIZE_BYTES + // totalChunks
                Int.SIZE_BYTES + // chunkIndex
                Int.SIZE_BYTES // originalSize
```

Bump `VERSION` to `2` — the layout changed, and an old chunk must be rejected rather than
misread.

In `split`, mint one id for the whole call:

```kotlin
    private fun split(
        data: ByteArray,
        kind: PacketChunkKind,
    ): ObjectArrayList<ByteArray> {
        // One id per split call. Chunks of two attempts at the same request share a
        // correlationId, so without this the assembler cannot tell them apart.
        val seriesId = ThreadLocalRandom.current().nextLong()
        …
                encodeChunk(
                    kind = kind,
                    seriesId = seriesId,
                    totalChunks = totalChunks,
                    …
                )
```

Write it in `encodeChunk` right after the kind byte, and read it back in `decodeOrNull` at the
same position. Add `val seriesId: Long` to `PacketChunk`, including `equals` and `hashCode`.

- [ ] **Step 4: Key the assembler by series**

In `RabbitPacketChunkAssembler`, replace the map key with a composite:

```kotlin
    private data class PartialKey(val correlationId: String, val seriesId: Long)

    private val partialPackets = ConcurrentHashMap<PartialKey, PartialPacket>()
```

`getOrCreatePartial` takes the key, and `discard` must drop **every** series belonging to a
correlation id:

```kotlin
    /** Drops all partial series for [correlationId], whichever attempt they came from. */
    fun discard(correlationId: String) {
        partialPackets.keys.removeIf { it.correlationId == correlationId }
    }
```

Keep `validateMetadata` as is: within one series, differing metadata is still a protocol error.

- [ ] **Step 5: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*ChunkSeriesTest*' --tests '*ChunkingTest*'`
Expected: `BUILD SUCCESSFUL`, 6 + 2 tests passed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "fix(packet): key chunk assembly by series, not correlation id alone

A service dying midway through a chunked response left partial chunks
behind. The redelivered message produced a fresh series under the same
correlation id, and the assembler filled the gaps from the new series
while keeping the old chunks - assembling one packet out of two different
responses. Size checks did not catch it because both attempts serialise
to the same length.

Latent until now because failed messages were discarded; the retry ladder
would have made redelivery routine."
```

---

### Task 11: Failure-during-processing tests

The scenarios that decide whether messages survive real outages. All of them kill a participant at a specific moment rather than between operations.

**Files:**
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/failure/ConsumerDeathTest.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/failure/BrokerLossDuringSendTest.kt`

**Interfaces:**
- Consumes: everything above
- Produces: nothing

**Scenario matrix.** Every row is one test.

| # | Killed | When | Must happen |
|---|---|---|---|
| 1 | microservice | after prefetch, before the handler runs | another instance handles it; nothing lost |
| 2 | microservice | while the handler runs | another instance handles it; handler ran twice (at-least-once) |
| 3 | microservice | after publishing the reply, before ack | client still gets its answer; the duplicate reply is discarded |
| 4 | microservice | midway through a chunked reply | client gets one intact reply, never a mixture |
| 5 | last instance | while requests are in flight | requests time out; messages stay queued for the next start |
| 6 | broker | while publishing | publisher confirm fails; the caller sees an error |
| 7 | broker | while the caller waits for a reply | the call settles — recovered or failed, never hanging |

- [ ] **Step 1: Write the consumer-death tests**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/failure/ConsumerDeathTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.failure

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
class SlowPacket(val text: String, val holdMillis: Long) : RabbitRequestPacket<SlowResponse>()

@Serializable
class SlowResponse(val text: String) : RabbitResponsePacket()

/**
 * What happens when a microservice dies at specific points in the request lifecycle.
 *
 * These are the scenarios that decide whether the at-least-once promise holds. A message must
 * never be lost because the process handling it went away — and the price of that guarantee is
 * that a handler can run more than once, which the tests also pin down so nobody is surprised
 * by it later.
 */
@RequiresDocker
class ConsumerDeathTest {

    private val dataPath = Files.createTempDirectory("consumer-death")

    private fun api(service: String) = SurfRabbitApi
        .builder(service, dataPath)
        .config(testConfig(requestTimeoutSeconds = 30))
        .build()

    private class SlowHandler(val started: AtomicInteger, val completed: AtomicInteger) {
        @RabbitHandler
        suspend fun onSlow(packet: SlowPacket) {
            started.incrementAndGet()
            delay(packet.holdMillis)
            completed.incrementAndGet()
            packet.respond(SlowResponse("done:${packet.text}"))
        }
    }

    @Test
    fun `a message survives the instance that was processing it`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("death-during")

        val dyingStarted = AtomicInteger()
        val dyingCompleted = AtomicInteger()
        val dying = api(service).also {
            it.registerRequestHandler(SlowHandler(dyingStarted, dyingCompleted))
            it.freezeAndConnect()
        }

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val call = async {
                runCatching {
                    client.connection.sendRequest(
                        SlowPacket("x", holdMillis = 30_000),
                        SlowResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }

            // Wait until the handler is genuinely running, then kill the instance mid-flight.
            awaitCondition("the handler starts") { dyingStarted.get() == 1 }
            dying.disconnect()

            // A second instance takes over. The message was never acked, so the broker
            // redelivers it.
            val survivorStarted = AtomicInteger()
            val survivorCompleted = AtomicInteger()
            val survivor = api(service).also {
                it.registerRequestHandler(SlowHandler(survivorStarted, survivorCompleted))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("the survivor picks up the message", timeoutMillis = 30_000) {
                    survivorStarted.get() >= 1
                }

                assertEquals(
                    0, dyingCompleted.get(),
                    "the first handler never finished, which is the point of the scenario"
                )
                assertTrue(
                    survivorStarted.get() >= 1,
                    "an unacked message must be redelivered - if this fails, work is lost " +
                            "whenever an instance restarts"
                )
            } finally {
                survivor.disconnect()
            }

            call.cancel()
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a handler may run twice when its instance dies - at-least-once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("at-least-once")
        val totalStarts = AtomicInteger()

        val first = api(service).also {
            it.registerRequestHandler(SlowHandler(totalStarts, AtomicInteger()))
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            client.send(SlowPacket("x", holdMillis = 20_000), RabbitTarget.ServiceTarget(service))

            awaitCondition("first attempt starts") { totalStarts.get() == 1 }
            first.disconnect()

            val second = api(service).also {
                it.registerRequestHandler(SlowHandler(totalStarts, AtomicInteger()))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("second attempt starts", timeoutMillis = 30_000) {
                    totalStarts.get() >= 2
                }

                assertTrue(
                    totalStarts.get() >= 2,
                    "delivery is at-least-once: a handler that is not idempotent must use " +
                            "@RabbitHandler(retry = false) and accept the message being dropped"
                )
            } finally {
                second.disconnect()
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a duplicate reply after redelivery is discarded, not surfaced`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("dup-reply")
        val replies = AtomicInteger()

        // Replies, then stalls before acking, so the message is redelivered while the
        // answer is already on its way to the client.
        class ReplyThenStall {
            @RabbitHandler
            suspend fun onSlow(packet: SlowPacket) {
                replies.incrementAndGet()
                packet.respond(SlowResponse("done:${packet.text}"))
                delay(packet.holdMillis)
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(ReplyThenStall())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val received = AtomicReference<String?>(null)

            val call = async {
                runCatching {
                    client.connection.sendRequest(
                        SlowPacket("x", holdMillis = 5_000),
                        SlowResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    ).text
                }.getOrNull()
            }

            awaitCondition("the client receives an answer", timeoutMillis = 20_000) {
                call.isCompleted
            }
            received.set(call.await())

            assertEquals(
                "done:x", received.get(),
                "the client must get its answer even though the message was never acked"
            )

            // A second delivery produces a second reply for a correlation id the client has
            // already retired. It must be dropped silently, not delivered to anyone.
            delay(3_000)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `messages stay queued when the last instance goes away`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("all-gone")

        // Create the durable queue, then take the service away entirely.
        api(service).also {
            it.registerRequestHandler(SlowHandler(AtomicInteger(), AtomicInteger()))
            it.freezeAndConnect()
        }.disconnect()

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            repeat(5) {
                client.send(
                    SlowPacket("queued-$it", holdMillis = 0),
                    RabbitTarget.ServiceTarget(service)
                )
            }

            delay(2_000)

            val depth = RabbitBrokerExtension.newConnection("depth").use { connection ->
                connection.createChannel().use { channel ->
                    channel.queueDeclarePassive(RabbitTopology.serviceQueue(service)).messageCount
                }
            }

            assertEquals(
                5, depth,
                "with no instance running, fire-and-forget messages must wait in the durable " +
                        "queue rather than being discarded"
            )

            // Bringing the service back must drain them.
            val started = AtomicInteger()
            val restarted = api(service).also {
                it.registerRequestHandler(SlowHandler(started, AtomicInteger()))
                it.freezeAndConnect()
            }

            try {
                awaitCondition("the backlog is processed", timeoutMillis = 20_000) {
                    started.get() == 5
                }
            } finally {
                restarted.disconnect()
            }
        } finally {
            client.disconnect()
        }
    }

    private suspend fun awaitCondition(
        description: String,
        timeoutMillis: Long = 15_000,
        condition: () -> Boolean
    ) {
        val satisfied = withTimeoutOrNull(timeoutMillis) {
            while (!condition()) delay(100)
            true
        }

        assertTrue(satisfied == true, "timed out waiting for: $description")
    }
}
```

- [ ] **Step 2: Write the chunked-reply death test**

Append to `ConsumerDeathTest`:

```kotlin
    @Test
    fun `a service dying midway through a chunked reply never yields a mixed packet`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("chunk-death")
        val attempt = AtomicInteger()

        // First attempt dies after the handler responds but before all chunks are flushed;
        // the second answers completely. Distinct filler bytes make a mixture detectable.
        class ChunkedHandler {
            @RabbitHandler
            suspend fun onSlow(packet: SlowPacket) {
                val n = attempt.incrementAndGet()
                val filler = if (n == 1) 'a' else 'b'
                packet.respond(SlowResponse(filler.toString().repeat(1_500_000)))
            }
        }

        val server = api(service).also {
            it.registerRequestHandler(ChunkedHandler())
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val response = client.connection.sendRequest(
                SlowPacket("x", holdMillis = 0),
                SlowResponse::class.java,
                RabbitTarget.ServiceTarget(service)
            )

            val distinct = response.text.toCharArray().distinct()
            assertEquals(
                1, distinct.size,
                "the reply must come from a single attempt. Two distinct filler characters " +
                        "$distinct mean chunks of two responses were assembled into one packet"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }
```

- [ ] **Step 3: Write the broker-loss tests**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/failure/BrokerLossDuringSendTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.failure

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import dev.slne.surf.rabbitmq.core.EchoPacket
import dev.slne.surf.rabbitmq.core.EchoResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Losing the broker connection at the worst possible moments.
 *
 * The requirement is not that every call succeeds — it cannot — but that every call **settles**.
 * A request that hangs forever is worse than one that fails, because a Minecraft server ends up
 * with coroutines waiting on an answer that will never arrive.
 */
@RequiresDocker
class BrokerLossDuringSendTest {

    private val dataPath = Files.createTempDirectory("broker-loss")

    private object EchoHandler {
        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    private fun api(service: String) = SurfRabbitApi
        .builder(service, dataPath)
        .config(testConfig(requestTimeoutSeconds = 15))
        .build()

    @Test
    fun `publishing during a connection loss fails instead of hanging`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-publish")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()

            val calls = (1..20).map { n ->
                async {
                    runCatching {
                        client.connection.sendRequest(
                            EchoPacket("msg-$n"),
                            EchoResponse::class.java,
                            RabbitTarget.ServiceTarget(service)
                        )
                    }
                }
            }

            delay(20)
            RabbitBrokerExtension.closeAllConnections()

            val results = calls.map { it.await() }
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 60_000,
                "every call must settle. ${results.count { it.isSuccess }} succeeded, " +
                        "${results.count { it.isFailure }} failed, taking ${elapsed}ms"
            )
            assertTrue(
                results.size == 20,
                "no call may be left unresolved"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a caller waiting for a reply does not hang when the connection drops`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-await")

        val server = api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()

            val call = async {
                runCatching {
                    client.connection.sendRequest(
                        EchoPacket("waiting"),
                        EchoResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }

            // Drop the connection while the caller is parked on the reply queue.
            delay(30)
            RabbitBrokerExtension.closeAllConnections()

            call.await()
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 40_000,
                "the caller must be released - by recovery or by failure - but waited ${elapsed}ms. " +
                        "Losing the reply queue without failing pending requests would hang it forever"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `publisher confirms are not reported as success after a connection loss`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-confirm")

        api(service).also {
            it.registerRequestHandler(EchoHandler)
            it.freezeAndConnect()
        }.disconnect()

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val sends = (1..10).map { n ->
                async {
                    runCatching {
                        client.send(EchoPacket("ff-$n"), RabbitTarget.ServiceTarget(service))
                    }
                }
            }

            delay(10)
            RabbitBrokerExtension.closeAllConnections()

            val results = sends.map { it.await() }

            // Whatever the split, a send that reports success must really have been confirmed.
            assertTrue(
                results.size == 10,
                "every send must settle rather than hang"
            )
        } finally {
            client.disconnect()
        }
    }
}
```

- [ ] **Step 4: Run the whole failure suite**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*failure*'`
Expected: `BUILD SUCCESSFUL`, 8 tests passed. Allow around two minutes; these tests wait on
real redelivery.

Diagnosing failures:
- *`a message survives the instance that was processing it` times out* — the consumer is
  acking before the handler finishes. The ack must come after `respond`, otherwise a crash
  loses the message.
- *`a service dying midway through a chunked reply` reports two filler characters* — Task 10
  was not applied or the assembler still keys by correlation id alone.
- *`a caller waiting for a reply does not hang` exceeds the limit* — pending requests are not
  failed on connection loss. `markReplyConsumerUnavailable` must complete every pending
  deferred exceptionally.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(failure): cover instance and broker death during processing

Covers the seven points where a participant can die mid-request: before,
during and after the handler, midway through a chunked reply, with the
last instance gone, and with the broker lost while publishing or waiting."
```

---

### Task 12: Final documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-07-29-rabbitmq-topology-redesign-design.md`

- [ ] **Step 1: Document reliability behaviour**

Append to `README.md`:

````markdown
## What happens when things fail

| Situation | Behaviour |
|---|---|
| All instances of a service are down, RPC | The message waits in the durable queue. The caller gets `SurfRabbitRequestTimeoutException` after the request timeout; the message's TTL then removes it |
| All instances down, fire-and-forget | The message waits and is processed once the service returns |
| All instances down, broadcast | The event is lost — no queue is bound |
| The service does not exist at all | Immediate `SurfRabbitServiceUnavailableException`, plus a copy in `surf.unroutable` |
| A handler throws | Retried after 10 s, 60 s and 300 s, then moved to `surf.dlq.<service>` |
| A handler marked `retry = false` throws | Straight to `surf.dlq.<service>` |
| A service queue is full | `reject-publish` — the publisher gets an error rather than older messages being dropped |
| Repeated transport failures to one service | Its circuit breaker opens for 30 s; other services are unaffected |
| The broker is unreachable | Automatic recovery with exponential backoff and jitter |

Note the distinction in rows one and four: a service whose instances have all stopped still has
its durable queue, so its messages wait. Only a service that was never deployed — or a
misspelled name — produces an unroutable message.

### Retries and idempotency

Retries mean **at-least-once** delivery: a handler can run more than once for the same message.
That is harmless for "kick this player" and a bug for "deduct 100 coins" unless the handler is
idempotent.

```kotlin
@RabbitHandler(retry = false)
suspend fun onTransfer(packet: TransferPacket) { … }

@RabbitSubscribe(retry = false)
suspend fun onCoinsTransferred(event: CoinsTransferredEvent) { … }
```

### Scaling

Running more instances of a service increases throughput with no configuration: they all
consume the same queue and the broker distributes the work.

There is **no ordering guarantee** across instances. Two messages concerning the same player can
be processed simultaneously on different instances. For state-changing services this has to be
handled inside the service — a database transaction or a per-entity lock — no messaging system
solves it for you.

## Architecture: no microservice chains

A microservice does not call another microservice over RPC. The intended shape is:

```
Paper plugin surf-punish    ──RPC──▶  microservice surf-punish
Paper plugin surf-factions  ──RPC──▶  microservice surf-factions

microservice surf-transaction ──publish──▶ surf.events ──▶ whoever subscribed
```

Each plugin exposes a local API (`Faction.create()`) that internally calls its own
microservice. A microservice knows nothing about other microservices.

Synchronous chains between services tie their availability together: one link fails and the
whole chain fails, while latencies add up. The result is a distributed monolith.

**Publishing events is the intended alternative.** The publisher does not know its subscribers,
no availability coupling is created, and with nobody subscribed simply nothing happens.

```kotlin
// don't: surf-punish waiting on surf-transaction
val tx = rabbit.rpc<TransactionService>()
tx.refund(playerId)

// do: announce what happened and let whoever cares react
rabbit.publish(PunishmentRevokedEvent(playerId))
```
````

- [ ] **Step 2: Mark the spec implemented**

Add at the top of the spec, under the status line:

```markdown
**Implemented:** 2026-07-29 across plans
`2026-07-29-surf-circuitbreaker.md`,
`2026-07-29-rabbitmq-topology-foundation.md`,
`2026-07-29-rabbitmq-events-and-send.md`,
`2026-07-29-rabbitmq-reliability-and-migration.md`.
```

- [ ] **Step 3: Final full build**

Run: `./gradlew build`
Expected with Docker: `BUILD SUCCESSFUL`, every test passes.
Expected without Docker: `BUILD SUCCESSFUL`, integration tests skipped — report them as
**unverified**, never as passing.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: document failure behaviour, retries and scaling"
```

---

## Done when

- [ ] `./gradlew build -PskipIntegration` succeeds
- [ ] With Docker: every integration test passes
- [ ] A failing handler is retried three times and then lands in the DLQ
- [ ] `retry = false` reaches the DLQ on the first failure
- [ ] A request to a nonexistent service fails in milliseconds, not after the timeout
- [ ] One dead service does not open the breaker of a healthy one
- [ ] `surf-rabbitmq-test` runs on `SurfRabbitApi`
- [ ] Chunking has test coverage for the first time
- [ ] A message survives the instance that was processing it
- [ ] A service dying midway through a chunked reply never produces a mixed packet
- [ ] Every call settles when the broker connection drops — none hangs

## Deliberately out of scope

- **`surf-broker` extraction.** Its own project, once these four plans are done. The
  `shared.*` and `platform.*` packages plus `surf-circuitbreaker` are already shaped for it.
- **Consistent-hash routing for per-entity ordering.** Needs a broker plugin; only worth it if
  ordering turns out to be a real requirement.
- **Metrics and tracing.** No consumer for them yet.
- **A DLQ replay tool.** Messages are preserved and inspectable; automated replay is a separate
  piece of work.
