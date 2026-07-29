# RabbitMQ Reliability and Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Do NOT use subagent-driven-development.** The repository owner's global instructions forbid delegating work to subagents.

**Prerequisites:** Plan 1 (`surf-circuitbreaker`), Plan 2 (topology foundation) and Plan 3 (events) must be complete.

**Goal:** Stop losing messages on failure. Add dead-letter queues, delayed retries with backoff, fail-fast on unroutable messages, and per-service circuit breakers — then migrate the test module and document the result.

**Architecture:** A failed handler republishes the message into one of three globally shared retry tiers. Each tier is a `fanout` exchange plus a queue whose dead-letter exchange is `""` (the default exchange): the republish carries **the origin queue's name as routing key**, the fanout ignores it for insertion, and on TTL expiry the default exchange routes the message straight back into exactly the queue it came from — service queues and shared event queues alike. After three retries the message goes to the service's dead-letter queue. Unroutable publishes are caught by a `ReturnListener` that fails the caller immediately *and* republishes the returned body into the `surf.unroutable` audit queue. Each target service gets its own circuit breaker from `surf-circuitbreaker`.

**Tech Stack:** Kotlin (JVM toolchain 25), amqp-client 5.34.0, `surf-circuitbreaker`, JUnit 5, Testcontainers.

## Global Constraints

- All Global Constraints from Plans 2 and 3 still apply.
- Retry tiers: `surf.retry.10s`, `surf.retry.60s`, `surf.retry.300s` — each a **fanout
  exchange plus a queue of the same name**. Tier TTLs come from
  `CommonRabbitMQConfig.getRetryTtlMillis()` (production default 10 000 / 60 000 / 300 000 ms;
  tests use sub-second values so the full ladder is testable).
- Tier queues dead-letter to `""` — the **default exchange**, never to `surf.rpc`. The
  republish into a tier carries the *origin queue's name* as routing key; on expiry the
  default exchange routes the message back into exactly that queue. This is what lets three
  shared tiers serve every service queue *and* every shared event queue. (Dead-lettering to
  `surf.rpc` — the spec's original design — could never work: a client republish through the
  default exchange stamps the tier queue's own name as routing key, and events' origin is a
  topic binding that `surf.rpc` knows nothing about.)
- **Never set `x-dead-letter-routing-key` on a tier queue.** The preserved per-message key
  (= origin queue name) is the routing mechanism; pinning it would send every retried
  message of the whole fleet to one queue.
- Retry ladder, verbatim from the spec: `n = 0` → `10s`, `n = 1` → `60s`, `n = 2` → `300s`,
  `n = 3` → dead-letter queue. Four deliveries maximum.
- **`basicNack(requeue = true)` is forbidden.** It returns the message to the queue head for
  immediate redelivery, producing a hot loop that also blocks the queue.
- Circuit breaker defaults, verbatim: `failureThreshold = 5`, `openDuration = 30.seconds`.
- Only transport failures count toward the breaker and are retried. **Transport is a closed
  list, not a hierarchy match**: `SurfRabbitServiceUnavailableException` and
  `SurfRabbitConnectionException` (incl. `SurfRabbitPublishException`). A
  `SurfRabbitRequestTimeoutException` is **not** transport — it *extends*
  `SurfRabbitRequestException`, so a naive `is SurfRabbitRequestException` would retry
  timeouts (re-running possibly non-idempotent handlers, tripling worst-case latency) while
  missing genuine publish failures. Business exceptions never count.
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
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/internal/config/CommonRabbitMQConfig.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/retry/RetryPolicyTest.kt`

**Interfaces:**
- Consumes: `RabbitTopology` (Plan 2)
- Produces:
  ```kotlin
  // Tier TTLs live in the config, not the enum, so integration tests can shrink the
  // ladder to sub-second values and actually run it end to end. The queue names keep
  // their production labels regardless.
  enum class RetryTier(val queueName: String) {
      TEN_SECONDS("surf.retry.10s"),
      ONE_MINUTE("surf.retry.60s"),
      FIVE_MINUTES("surf.retry.300s")
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

  // CommonRabbitMQConfig gains a defaulted member (index-aligned with RetryTier.entries):
  fun getRetryTtlMillis(): List<Long> = listOf(10_000L, 60_000L, 300_000L)
  ```
  `testConfig()` from Plan 2 overrides `getRetryTtlMillis()` with
  `listOf(500L, 1_000L, 1_500L)`.

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
    fun `tier queue names match the specification`() {
        assertEquals("surf.retry.10s", RetryTier.TEN_SECONDS.queueName)
        assertEquals("surf.retry.60s", RetryTier.ONE_MINUTE.queueName)
        assertEquals("surf.retry.300s", RetryTier.FIVE_MINUTES.queueName)
    }

    @Test
    fun `the default tier ttls match the specification`() {
        // The config interface default is what production runs on; tests override it.
        val config = object : dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig {
            override fun getHost() = ""
            override fun getPort() = 0
            override fun getUsername() = ""
            override fun getPassword() = ""
            override fun getVhost() = ""
            override fun getTimeout() = 0
            override fun getRequestTimeoutSeconds() = 0
            override fun getPublisherPoolSize() = 0
            override fun getServerPrefetchCount() = 0
            override fun isPersistRequests() = false
            override fun isPersistResponses() = false
            override fun isOutgoingRequestChunkingEnabled() = false
            override fun isOutgoingResponseChunkingEnabled() = false
        }

        assertEquals(listOf(10_000L, 60_000L, 300_000L), config.getRetryTtlMillis())
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
 * The tiers are shared by the entire fleet rather than created per service. That works
 * because a retried message is published into the tier's fanout exchange with **the name of
 * the queue it came from as routing key**: the fanout ignores the key on the way in, the
 * tier queue dead-letters to the default exchange on expiry, and the default exchange routes
 * by the preserved key — straight back into the origin queue, whichever service or shared
 * event queue that was.
 *
 * TTLs are configuration ([CommonRabbitMQConfig.getRetryTtlMillis]), index-aligned with
 * [entries], so tests can run the full ladder in seconds. The names keep their production
 * labels either way.
 */
enum class RetryTier(val queueName: String) {
    TEN_SECONDS("surf.retry.10s"),
    ONE_MINUTE("surf.retry.60s"),
    FIVE_MINUTES("surf.retry.300s")
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

Add the defaulted TTL accessor to `CommonRabbitMQConfig`:

```kotlin
    /**
     * TTL per retry tier in milliseconds, index-aligned with `RetryTier.entries`.
     *
     * A default member rather than an abstract one: only test configs override it, to
     * shrink the ladder to sub-second values. Queue arguments are part of a queue's
     * identity, so all processes sharing a broker must agree on these values.
     */
    fun getRetryTtlMillis(): List<Long> = listOf(10_000L, 60_000L, 300_000L)
```

and override it in `testConfig()` (Plan 2's helper) with `listOf(500L, 1_000L, 1_500L)`.

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*RetryPolicyTest*'`
Expected: `BUILD SUCCESSFUL`, 11 tests passed.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(retry): add retry ladder and x-death attempt counting"
```

---

### Task 2: Retry tiers and republishing

Declares the three shared retry tiers (fanout exchange + queue each) and moves failed
messages into them so that TTL expiry routes them back into exactly the queue they came from.

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
  fun retryQueue(ttlMillis: Long): Map<String, Any>

  // RabbitTopologyDeclarer
  fun declareRetryTiers(ttlMillis: List<Long>)

  class RetryPublisher(private val client: RabbitClient) {
      suspend fun handleFailure(
          body: ByteArray,                     // the ASSEMBLED body, not a raw chunk
          properties: AMQP.BasicProperties,    // original properties; headers carry x-death
          originQueue: String,                 // the queue this delivery was consumed from
          serviceName: String,
          retryEnabled: Boolean,
          rechunkAsRequest: Boolean            // true on the request path, false for events
      ): RetryDecision
  }
  ```

- [ ] **Step 1: Add the retry queue arguments**

Append to `QueueArguments`:

```kotlin
    /**
     * A holding queue whose TTL expiry returns the message to its origin.
     *
     * `x-dead-letter-exchange: ""` is the default exchange, which routes by queue name.
     * The republish into the tier carries the origin queue's name as routing key, so
     * expiry delivers the message straight back into that queue — no per-service tiers,
     * no re-broadcast through a topic exchange.
     *
     * `x-dead-letter-routing-key` is deliberately **absent**: the preserved per-message
     * key IS the routing mechanism. Pinning it would send every retried message of the
     * whole fleet to one queue.
     */
    fun retryQueue(ttlMillis: Long): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-message-ttl" to ttlMillis,
        "x-dead-letter-exchange" to ""
    )
```

- [ ] **Step 2: Declare the tiers**

Append to `RabbitTopologyDeclarer`:

```kotlin
    /**
     * Declares the three shared retry tiers: a fanout exchange and a queue per tier,
     * bound together.
     *
     * The fanout exchange exists because the republish must carry the origin queue's
     * name as routing key *without* that key affecting insertion. Publishing into the
     * tier queue via the default exchange instead would stamp the tier queue's own name
     * as routing key — and expiry would then route the message back into the tier queue
     * itself, looping it forever.
     *
     * Nothing ever consumes these queues; messages leave by TTL expiry only.
     *
     * @param ttlMillis per-tier TTLs, index-aligned with [RetryTier.entries]; from
     *   [CommonRabbitMQConfig.getRetryTtlMillis], so every process on a broker agrees
     */
    fun declareRetryTiers(ttlMillis: List<Long>) {
        require(ttlMillis.size == RetryTier.entries.size) {
            "expected one TTL per retry tier"
        }

        RetryTier.entries.forEachIndexed { index, tier ->
            channel.exchangeDeclare(tier.queueName, BuiltinExchangeType.FANOUT, true)
            channel.queueDeclare(
                tier.queueName,
                /* durable = */ true,
                /* exclusive = */ false,
                /* autoDelete = */ false,
                QueueArguments.retryQueue(ttlMillis[index])
            )
            channel.queueBind(tier.queueName, tier.queueName, "")
        }
    }
```

(Exchange and queue share a name per tier; AMQP keeps the two namespaces separate.)

Call it from `RabbitConnectionImpl.connect()`, right after `declareExchanges()`, passing
`api.config.getRetryTtlMillis()`.

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
import dev.slne.surf.rabbitmq.common.topology.RabbitTopologyDeclarer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RequiresDocker
class RetryQueueTest {

    // Short tiers so the test observes real expiry without waiting ten seconds. Every
    // test in the JVM shares the broker, so they must all use the same values (this is
    // also what testConfig() hands the connection).
    private val testTtls = listOf(500L, 1_000L, 1_500L)

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private lateinit var declarer: RabbitTopologyDeclarer

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("retry-test")
        channel = connection.createChannel()
        declarer = RabbitTopologyDeclarer(channel)
        declarer.declareExchanges()
        declarer.declareRetryTiers(testTtls)
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `a message parked in a retry tier returns to its origin queue`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-return")
        val serviceQueue = declarer.declareServiceQueue(service)

        // Into the tier EXCHANGE, with the origin queue's name as routing key. The fanout
        // ignores the key for insertion; expiry dead-letters to the default exchange,
        // which routes by exactly this key.
        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            serviceQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "retry-me".toByteArray()
        )

        // The message must not be in the service queue yet.
        assertEquals(
            null, channel.basicGet(serviceQueue, true),
            "the message should still be held in the retry tier"
        )

        val returned = awaitMessage(serviceQueue, timeoutMillis = 10_000)
        assertEquals(
            "retry-me", String(returned),
            "expiry must route the message back to its origin queue via the default " +
                    "exchange - if this fails, check the tier's x-dead-letter-exchange " +
                    "and the routing key of the republish"
        )
    }

    @Test
    fun `the same tier serves a shared event queue`() {
        // The reason the tiers dead-letter to the default exchange instead of surf.rpc:
        // event queues are fed by topic bindings surf.rpc knows nothing about.
        val service = RabbitBrokerExtension.uniqueServiceName("retry-event")
        val eventQueue = declarer.declareSharedEventQueue(service, setOf("test.retry.#"))

        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            eventQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "event-retry".toByteArray()
        )

        val returned = awaitMessage(eventQueue, timeoutMillis = 10_000)
        assertEquals("event-retry", String(returned))
    }

    @Test
    fun `a returned message carries an incremented attempt count`() {
        val service = RabbitBrokerExtension.uniqueServiceName("retry-count")
        val serviceQueue = declarer.declareServiceQueue(service)

        channel.basicPublish(
            RetryTier.TEN_SECONDS.queueName,
            serviceQueue,
            AMQP.BasicProperties.Builder().deliveryMode(2).build(),
            "counted".toByteArray()
        )

        val response = awaitDelivery(serviceQueue, timeoutMillis = 10_000)
        val attempts = RetryPolicy.attemptsFrom(response.props.headers)

        assertTrue(
            attempts >= 1,
            "x-death must record the expiry so the ladder can advance, but attempts=$attempts"
        )
    }

    @Test
    fun `declaring the tiers again with the same ttls succeeds`() {
        // Redeclaring with identical arguments succeeds; differing ones fail the channel.
        // This is why every process on a broker must agree on getRetryTtlMillis().
        RetryTier.entries.forEachIndexed { index, tier ->
            val ok = channel.queueDeclare(
                tier.queueName, true, false, false, QueueArguments.retryQueue(testTtls[index])
            )
            assertNotNull(ok)
        }
    }

    @Test
    fun `retry queues do not pin the routing key`() {
        assertEquals(
            null, QueueArguments.retryQueue(500L)["x-dead-letter-routing-key"],
            "the preserved per-message key IS the return routing; pinning it would send " +
                    "every retried message of every service to one queue"
        )
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
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.packet.RabbitPacketChunking
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import it.unimi.dsi.fastutil.objects.ObjectList

/**
 * Moves a message whose handler failed either onto the retry ladder or into the dead-letter
 * queue.
 *
 * Republishing rather than `basicNack(requeue = true)` is deliberate: requeueing returns the
 * message to the head of its own queue for immediate redelivery, which spins at full CPU and
 * blocks every message behind it. Parking the message in a TTL tier delays the retry instead.
 */
class RetryPublisher(private val client: RabbitClient) {

    companion object {
        private val log = logger()
    }

    /**
     * Republishes the failed message and returns what was decided.
     *
     * The caller must `ack` the original delivery afterwards: the message now exists in
     * another queue, and leaving the original unacked would duplicate it.
     *
     * @param body the **assembled** body. For a chunked request the raw delivery body is
     *   only the final chunk — the earlier ones were acked individually — so republishing
     *   a delivery body would park an orphan chunk that can never assemble again.
     * @param properties the original delivery properties; their headers carry `x-death`
     * @param originQueue the queue this delivery was consumed from; becomes the routing key
     *   of the republish, and therefore the destination after TTL expiry
     * @param rechunkAsRequest split oversized bodies into a fresh chunk series before
     *   republishing (request path only). Without it, a reassembled multi-chunk body can
     *   exceed the broker's max message size. Events are never chunked, so the event
     *   consumer passes `false`.
     */
    suspend fun handleFailure(
        body: ByteArray,
        properties: AMQP.BasicProperties,
        originQueue: String,
        serviceName: String,
        retryEnabled: Boolean,
        rechunkAsRequest: Boolean
    ): RetryDecision {
        val attempts = RetryPolicy.attemptsFrom(properties.headers)
        val decision = RetryPolicy.decide(attempts, retryEnabled)

        when (decision) {
            is RetryDecision.Retry -> {
                log.atInfo().log(
                    "Retrying message from %s in %s (attempt %s of %s)",
                    originQueue, decision.tier.queueName, attempts + 1, RetryPolicy.MAX_RETRIES
                )

                // Into the tier's fanout exchange with the origin queue as routing key:
                // insertion ignores the key, expiry routes by it via the default exchange.
                for (piece in bodiesFor(body, rechunkAsRequest)) {
                    client.publish(
                        exchange = decision.tier.queueName,
                        routingKey = originQueue,
                        body = piece,
                        properties = withoutExpiration(properties),
                        mandatory = false
                    )
                }
            }

            RetryDecision.DeadLetter -> {
                log.atWarning().log(
                    "Dead-lettering message from %s after %s attempts (retry enabled: %s)",
                    originQueue, attempts, retryEnabled
                )

                for (piece in bodiesFor(body, rechunkAsRequest)) {
                    client.publish(
                        exchange = RabbitTopology.DLX_EXCHANGE,
                        routingKey = serviceName,
                        body = piece,
                        properties = properties,
                        mandatory = false
                    )
                }
            }
        }

        return decision
    }

    /**
     * Re-chunks a body that only fit through the broker in pieces.
     *
     * Every piece carries the same (x-death bearing) properties, so the attempt count
     * stays consistent across chunks, and a fresh series id keeps the assembler from
     * mixing this attempt with a previous one (Task 10).
     */
    private fun bodiesFor(body: ByteArray, rechunkAsRequest: Boolean): ObjectList<ByteArray> =
        if (rechunkAsRequest && RabbitPacketChunking.shouldChunk(body, enabled = true)) {
            RabbitPacketChunking.splitRequest(body)
        } else {
            ObjectList.of(body)
        }

    /**
     * Copies the properties, dropping `expiration`.
     *
     * A retried RPC request would otherwise expire inside the retry tier before its TTL
     * moved it back, and disappear without reaching the dead-letter queue.
     */
    private fun withoutExpiration(properties: AMQP.BasicProperties): AMQP.BasicProperties =
        properties.builder()
            .expiration(null)
            .build()
}
```

- [ ] **Step 5: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*RetryQueueTest*'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

Without Docker: skipped, record as unverified.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(retry): add fanout retry tiers that return messages to their origin queue"
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

Thread two things the failure path needs through `RabbitListenerHandlerManager.handleRequest`:
the delivery `properties` (their headers carry `x-death`) and the `originQueue` — the queue
`startConsumingRequests` was started on (Plan 2 kept it as a parameter for exactly this).
Remember that a request consumed from the *instance* queue must republish with the instance
queue as origin, not the service queue.

Then every `ack.nack(requeue = false)` that follows a **handler failure** becomes:

```kotlin
                retryPublisher.handleFailure(
                    body = assembledBody,           // what the handler saw, not the raw chunk
                    properties = properties,
                    originQueue = originQueue,
                    serviceName = api.identity.serviceName,
                    retryEnabled = listenerHandler.retryEnabledFor(request.javaClass),
                    rechunkAsRequest = true
                )
                ack.ack()
```

This includes the fire-and-forget failure branch from Plan 3 (its `nack` was explicitly
marked for replacement here).

Leave the `nack(requeue = false)` in place for failures that retrying cannot fix — an
undeserialisable body or a message with no registered handler. Those go straight to the
dead-letter queue via the origin queue's own `x-dead-letter-exchange` (service queues
dead-letter under the service name; shared event queues pin the routing key — both land in
`surf.dlq.<service>`).

If `handleFailure` itself throws (for instance the broker went away mid-republish), fall
back to `ack.nack(requeue = false)` — the origin queue's DLX preserves the message; losing
a retry rung is acceptable, losing the message is not.

Apply the same change in the event consumer from Plan 3, using the subscription's `retry`
flag, the event queue name as `originQueue`, and `rechunkAsRequest = false` (events are
never chunked).

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

@Serializable
class OtherPacket(val text: String) : RabbitRequestPacket<FailingResponse>()

/** Handles only [OtherPacket], so a [FailingPacket] delivery finds no handler. */
class OtherHandler {
    @RabbitHandler
    suspend fun onOther(packet: OtherPacket) = Unit
}

@RequiresDocker
class RetryIntegrationTest {

    private val dataPath = Files.createTempDirectory("retry-integration")

    // Not private: handler registration goes through the hidden-class invoker, which
    // rejects inaccessible members.
    class AlwaysFailing {
        val attempts = AtomicInteger()

        @RabbitHandler
        suspend fun onPacket(packet: FailingPacket) {
            attempts.incrementAndGet()
            throw IllegalStateException("handler always fails")
        }
    }

    class NeverRetried {
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

            // testConfig shrinks the first tier to 500 ms.
            awaitCondition("second attempt after the first tier", timeoutMillis = 10_000) {
                handler.attempts.get() >= 2
            }

            assertTrue(
                handler.attempts.get() >= 2,
                "the message must be redelivered after the retry TTL expires - if it is " +
                        "not, check that the republish targets the tier exchange with the " +
                        "origin queue as routing key"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a failing handler climbs the full ladder and lands in the DLQ`() = runBlocking {
        // Spec test 6, end to end: first delivery plus three retries, then the DLQ.
        // Feasible only because testConfig shrinks the tiers to 500ms/1s/1.5s.
        val service = RabbitBrokerExtension.uniqueServiceName("full-ladder")
        val handler = AlwaysFailing()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(handler)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("doomed"), RabbitTarget.ServiceTarget(service))

            awaitCondition("four deliveries in total", timeoutMillis = 20_000) {
                handler.attempts.get() == 4
            }

            awaitCondition("the message reaches the DLQ", timeoutMillis = 10_000) {
                messageCount(RabbitTopology.deadLetterQueue(service)) == 1
            }

            // Give a runaway ladder time to disprove itself.
            delay(3_000)

            assertEquals(
                4, handler.attempts.get(),
                "exactly four deliveries: the first plus three retries - more means the " +
                        "attempt counting from x-death is broken"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a message with no registered handler is dead-lettered, not lost`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("no-handler")

        // The server stays CONNECTED but only handles a different packet type, so the
        // delivery reaches the no-handler branch, which nacks. The service queue's DLX
        // must then preserve the message in the DLQ.
        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerRequestHandler(OtherHandler())
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            client.send(FailingPacket("orphan"), RabbitTarget.ServiceTarget(service))

            awaitCondition("the message lands in the DLQ") {
                messageCount(RabbitTopology.deadLetterQueue(service)) == 1
            }

            assertEquals(
                0, messageCount(RabbitTopology.serviceQueue(service)),
                "the message must leave the service queue via nack, not linger unacked"
            )
        } finally {
            client.disconnect()
            server.disconnect()
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
Expected: `BUILD SUCCESSFUL`, 4 tests passed. The full-ladder test takes a few seconds —
the test config's sub-second tiers are what make it runnable at all.

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
- Consumes: `RabbitTarget` (Plan 2), `RabbitTopology.UNROUTABLE_QUEUE` (declared at connect
  since Plan 2)
- Produces:
  ```kotlin
  class SurfRabbitServiceUnavailableException(val target: String, val replyText: String)
      : SurfRabbitRequestException

  class ReturnListenerBridge(
      private val scope: CoroutineScope,
      private val client: RabbitClient,
      private val onReturned: (messageId: String, routingKey: String, reason: String) -> Unit
  ) {
      fun install(channel: Channel)
      fun register(messageId: String)
      fun unregister(messageId: String)
      fun returnedReason(messageId: String): String?
  }
  ```
  Keyed by `messageId`, not `correlationId`: fire-and-forget messages have no correlation id
  but must fail fast too. The RPC path sets `messageId = correlationId`; `send()` mints a
  random one. Every mandatory publish sets a `messageId`.

  Two consumers of a return:
  - **RPC** — `onReturned` completes the pending request deferred exceptionally, so the
    caller fails in milliseconds with no polling.
  - **Fire-and-forget** — publishes go through confirms, and the broker sends `basic.return`
    *before* the confirm ack of the same message, on the same channel; by the time
    `client.publish` returns, any return has already been recorded. `send()` checks
    `returnedReason` once after publishing and throws.

  The bridge also republishes every returned body into `surf.unroutable` — that is where
  the audit copy comes from now that `surf.rpc` has no alternate exchange (an AE would have
  suppressed `basic.return` entirely and killed fail-fast).

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

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces messages the broker sent back as unroutable.
 *
 * `mandatory = true` makes the broker return such a message instead of dropping it, but the
 * return arrives asynchronously on the channel and is invisible to the publisher unless
 * something listens. Without this bridge a caller would wait out the full request timeout for
 * a message that was rejected within milliseconds.
 *
 * Ordering guarantee this relies on: the broker sends `basic.return` **before** the confirm
 * ack of the same message, on the same channel. With confirms enabled (the default), a
 * completed `publish()` therefore implies any return has already been processed.
 */
class ReturnListenerBridge(
    private val scope: CoroutineScope,
    private val client: RabbitClient,
    private val onReturned: (messageId: String, routingKey: String, reason: String) -> Unit
) {
    companion object {
        private val log = logger()
    }

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val returned = ConcurrentHashMap<String, String>()

    /** Installs the listener on [channel]. Call once per publisher channel. */
    fun install(channel: Channel) {
        channel.addReturnListener { replyCode, replyText, _, routingKey, properties, body ->
            val messageId = properties?.messageId
            val reason = "$replyCode $replyText"

            log.atWarning().log(
                "Message to '%s' was returned as unroutable: %s", routingKey, reason
            )

            // The audit copy. surf.rpc has no alternate exchange (it would suppress this
            // very basic.return), so the copy is produced here instead. Off the listener
            // thread: publishing suspends.
            scope.launch {
                runCatching {
                    client.publish(
                        exchange = "",
                        routingKey = RabbitTopology.UNROUTABLE_QUEUE,
                        body = body,
                        properties = properties ?: AMQP.BasicProperties.Builder().build(),
                        mandatory = false
                    )
                }.onFailure {
                    log.atWarning().withCause(it)
                        .log("Could not preserve returned message in %s", RabbitTopology.UNROUTABLE_QUEUE)
                }
            }

            if (messageId != null && pending.contains(messageId)) {
                returned[messageId] = reason
                onReturned(messageId, routingKey, reason)
            }
        }
    }

    /** Starts watching for a return of [messageId]. */
    fun register(messageId: String) {
        pending += messageId
    }

    /** Stops watching and clears any recorded return. */
    fun unregister(messageId: String) {
        pending -= messageId
        returned.remove(messageId)
    }

    /** The broker's reason if this message was returned, otherwise `null`. */
    fun returnedReason(messageId: String): String? = returned[messageId]
}
```

- [ ] **Step 3: Use it in both send paths**

Construct the bridge in `RabbitConnectionImpl` with an `onReturned` that fails the pending
request — no polling loop; the deferred completes the moment the return arrives:

```kotlin
    private val returnListener = ReturnListenerBridge(api.scope, client) { messageId, routingKey, reason ->
        // For RPC, messageId == correlationId. Failing the deferred here is what turns a
        // broker-side reject within milliseconds into an immediate caller-side exception.
        val pending = pendingRequests.asMap().remove(messageId) ?: return@ReturnListenerBridge
        pending.second?.completeExceptionally(
            SurfRabbitServiceUnavailableException(routingKey, reason)
        )
    }
```

In `awaitResponse`: set `.messageId(correlationId)` on the request properties, call
`returnListener.register(correlationId)` before publishing, and
`returnListener.unregister(correlationId)` in the existing `finally` block. Nothing else
changes — the deferred the caller is already awaiting now simply completes exceptionally.

In `send()` (fire-and-forget): mint a `messageId`, register it, set it on the properties,
publish, then check once — the confirm ordering makes this race-free:

```kotlin
        val messageId = UUID.randomUUID().toString()
        returnListener.register(messageId)

        try {
            client.publish(/* …, */ properties = properties(MessageKind.FIRE_AND_FORGET, messageId = messageId), mandatory = true)

            returnListener.returnedReason(messageId)?.let { reason ->
                throw SurfRabbitServiceUnavailableException(target.routingKey, reason)
            }
        } finally {
            returnListener.unregister(messageId)
        }
```

(Extend the `properties(...)` helper from Plan 3 with an optional `messageId` parameter.)

Install the bridge on each publisher channel in `RabbitPublisher.getChannel`. The bridge is
constructed *with* the client (it republishes through it), so it cannot be a
`RabbitClient.create` argument — give the client a setter instead and call it from
`RabbitConnectionImpl`'s init, before `connect()`:

```kotlin
    // RabbitClient
    @Volatile
    private var returnListener: ReturnListenerBridge? = null

    fun setReturnListener(listener: ReturnListenerBridge) {
        returnListener = listener
        publisherPool.setReturnListener(listener)   // forwards to each RabbitPublisher
    }
```

Publisher channels are created lazily on first publish — which cannot happen before
`connect()` — and recreated after every reconnect, so a listener set during init reaches
every channel that will ever publish:

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
                "the return listener must republish a copy so a misrouted message can be diagnosed"
            )
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `a fire-and-forget to an unknown service fails fast too`() = runBlocking {
        // Fire-and-forget has no reply to wait for, so without this check a send() to a
        // misspelled service would report nothing at all - the message would just vanish
        // (with only the audit copy as evidence).
        val client = api("caller")
        client.freezeAndConnect()
        val target = "nonexistent-${System.nanoTime()}"

        try {
            val thrown = assertFailsWith<SurfRabbitServiceUnavailableException> {
                client.send(EchoPacket("x"), RabbitTarget.ServiceTarget(target))
            }

            assertTrue(thrown.target == target)
        } finally {
            client.disconnect()
        }
    }
}
```

- [ ] **Step 5: Run and re-enable the deferred test**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*UnroutableTest*'`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

Remove the `@Disabled("return listener lands in Plan 4 Task 4 - re-enable there")` marker
from `RpcRoundTripTest.a request to an unknown service fails fast instead of timing out`
(Plan 2 added it) and confirm it now passes.

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
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/exception/connection.kt` (re-parent `SurfRabbitConnectionLostException`)
- Modify: `surf-rabbitmq-core/.../connection/publisher/RabbitPublisher.kt` (typed confirm-nack)
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
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitPublishException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitRequestTimeoutException
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
        isFailure = {
            it is SurfRabbitServiceUnavailableException || it is SurfRabbitConnectionException
        }
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

    @Test
    fun `a timeout is neither retried nor counted`() = runTest {
        // SurfRabbitRequestTimeoutException EXTENDS SurfRabbitRequestException - this test
        // is the guard against someone "simplifying" the predicate to that supertype.
        // A timed-out request may already have executed; retrying re-runs it, and counting
        // it would open the breaker against a service that is merely slow.
        val registry = registry()
        val rpc = guarded(registry)
        val attempts = AtomicInteger()

        repeat(10) {
            assertFailsWith<SurfRabbitRequestTimeoutException> {
                rpc.call(RabbitTarget.ServiceTarget("svc")) {
                    attempts.incrementAndGet()
                    throw SurfRabbitRequestTimeoutException(null, 1.milliseconds)
                }
            }
        }

        assertEquals(10, attempts.get(), "a timeout must not be retried")
        assertEquals(
            CircuitState.CLOSED, registry.forName("svc").state,
            "timeouts must not open the breaker"
        )
    }

    @Test
    fun `a publish failure is retried and counted`() = runTest {
        // The clearest transport failure of all - the message never left this process.
        // It extends SurfRabbitConnectionException, NOT SurfRabbitRequestException, which
        // is why the predicate must not be a naive hierarchy match.
        val registry = registry()
        val rpc = guarded(registry)
        val attempts = AtomicInteger()

        assertFailsWith<SurfRabbitPublishException> {
            rpc.call(RabbitTarget.ServiceTarget("svc")) {
                attempts.incrementAndGet()
                throw SurfRabbitPublishException("nacked", null)
            }
        }

        assertEquals(3, attempts.get(), "a publish failure must use all retry attempts")
        assertEquals(CircuitState.OPEN, registry.forName("svc").state)
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
     * A closed list, not a hierarchy match, and deliberately narrow: anything not
     * recognised is treated as a business failure and left alone, because wrongly retrying
     * a state-changing call is worse than not retrying a transport error.
     *
     * [SurfRabbitRequestTimeoutException] is deliberately **absent** — and it would slip in
     * through a naive `is SurfRabbitRequestException`, which it extends. A timeout is
     * ambiguous: the service may just be slow, and the handler may already have executed.
     * Retrying it re-runs non-idempotent work and multiplies the caller's wait; counting it
     * would open the breaker against a service that is merely busy.
     */
    private fun isTransportFailure(cause: Throwable): Boolean =
        cause is SurfRabbitServiceUnavailableException ||
                cause is SurfRabbitConnectionException
}
```

`SurfRabbitConnectionException` covers `SurfRabbitPublishException` (publish/confirm failed)
and — after this task — connection loss. Two supporting fixes in the same step:

1. **Re-parent `SurfRabbitConnectionLostException`** (currently nested in the old client
   connection impl, extending `SurfRabbitRequestException`) under
   `SurfRabbitConnectionException`, moving it to
   `surf-rabbitmq-api/.../exception/connection.kt`. Losing the connection *is* a transport
   failure and must count; as a `SurfRabbitRequestException` it would be invisible to the
   predicate above. Breaking change, explicitly permitted.

2. **Wrap the confirm-nack.** `RabbitPublisher.publish` currently rethrows the raw
   `IOException` from `waitForConfirmsOrDie` (e.g. a `reject-publish` overflow nack) —
   untyped, unmatchable. Wrap it:

```kotlin
                } catch (cause: Throwable) {
                    resetChannel()

                    if (cause is CancellationException) throw cause
                    throw SurfRabbitPublishException("RabbitMQ publish was not confirmed", cause)
                }
```

- [ ] **Step 5: Wire it into the connection**

In `RabbitConnectionImpl` — the registry's predicate is the same closed transport list, so
the breaker and the retry agree on what counts:

```kotlin
    private val breakerRegistry = CircuitBreakerRegistry(
        failureThreshold = 5,
        openDuration = 30.seconds,
        isFailure = {
            it is SurfRabbitServiceUnavailableException || it is SurfRabbitConnectionException
        }
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
Expected: `BUILD SUCCESSFUL`, 10 tests passed.

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
- Modify: `surf-rabbitmq-core/build.gradle.kts` (apply KSP to test sources)
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/rpc/RpcProxyRoundTripTest.kt`

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

The plumbing — `createInstance(serviceId, api, target)`, `RabbitRpcCall.target`, the codegen
threading — already exists since Plan 2 Task 6 Step 5b. This step only replaces the
mandatory-override error with the annotation fallback in `ClientRpcServiceImpl.createService`:

```kotlin
        val target = service
            ?: descriptor.defaultService.ifBlank {
                error(
                    "No target service for ${descriptor.fqName}. Either annotate the interface " +
                            "with @RpcService(service = \"...\") or pass rpc(service = \"...\")."
                )
            }
```

- [ ] **Step 4b: Prove the generated proxy end to end**

No automated test anywhere exercises `rabbit.rpc<T>()` against a broker — Plan 2's tests
call `connection.sendRequest` directly, and the migration in Task 7 is manual. The headline
API must not ship untested (spec test 17).

Apply KSP to core's *test* sources (mirroring how `surf-rabbitmq-test-common` applies it,
but with `kspTest` instead of `ksp`) in `surf-rabbitmq-core/build.gradle.kts`, then create
`surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/rpc/RpcProxyRoundTripTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.rpc

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.rpc.RpcService
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

// The service name is unique per run, so the target is passed at rpc(...) call time;
// the defaultService mechanism is asserted separately below.
@RpcService(service = "proxy-default-target")
interface EchoRpcService {
    suspend fun echo(text: String): String
}

object EchoRpcImpl : EchoRpcService {
    override suspend fun echo(text: String): String = "echo:$text"
}

@RequiresDocker
class RpcProxyRoundTripTest {

    private val dataPath = Files.createTempDirectory("proxy-test")

    @Test
    fun `a generated proxy round-trips through a real broker`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("proxy")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<EchoRpcService>(EchoRpcImpl)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<EchoRpcService>(service = service)

            assertEquals(
                "echo:hi", proxy.echo("hi"),
                "this is the full public path: annotation, KSP codegen, proxy, broker, " +
                        "service registration - nothing else in the suite covers it end to end"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `the annotation's service lands in the generated descriptor`() {
        val client = SurfRabbitApi.builder("caller", dataPath)
            .config(
                dev.slne.surf.rabbitmq.common.testing.testConfig()
            )
            .build()

        assertEquals(
            "proxy-default-target",
            client.serviceDescriptorOf<EchoRpcService>().defaultService
        )
    }
}
```

The second test needs no Docker but lives with the first for cohesion; if the broker config
lookup bothers it, give it a plain stub config instead.

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

`testConfig()` defaults to `requestChunking = false`; add a test variant constructed with
`testConfig(requestChunking = true)` so the request-side chunking path is exercised too.

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
 * The reply queue is `autoDelete` with a STABLE name (`surf.reply.<instanceId>`): it dies
 * with the connection and topology recovery re-declares it under the same name. The danger
 * is no longer a stale queue name — it is `replyEndpoint` never being repopulated, because
 * the old signaling was keyed to queue *renames* that stable names never trigger. If the
 * endpoint stays null, every post-recovery RPC waits on a reply path that no longer exists.
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
                "after recovery the client must publish and consume again - if this times " +
                        "out, replyEndpoint was never repopulated by onRecoveryCompleted"
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

If `rpc works again after the connection is dropped` times out, `replyEndpoint` was not
repopulated after recovery. Check Plan 2 Task 7's listener rework: `onRecoveryCompleted`
must set the endpoint unconditionally with the stable `replyQueueName` and the new
generation — the old `onQueueRecovered` rename path never fires for stable names. Also check
that `awaitResponse` reads the endpoint fresh rather than capturing it once.

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

    // Not private: handler registration goes through the hidden-class invoker, which
    // rejects inaccessible members.
    class SlowHandler(val started: AtomicInteger, val completed: AtomicInteger) {
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
| The service does not exist at all (RPC **and** fire-and-forget) | Immediate `SurfRabbitServiceUnavailableException`, plus a copy in `surf.unroutable` |
| A handler throws | Retried after 10 s, 60 s and 300 s, then moved to `surf.dlq.<service>` |
| A handler marked `retry = false` throws | Straight to `surf.dlq.<service>` |
| A service queue is full | `reject-publish` — the publisher gets a `SurfRabbitPublishException` rather than older messages being dropped |
| A service's *event* queue is full | `drop-head` — that service loses its oldest events; publishers and other subscribers are unaffected |
| A request times out | `SurfRabbitRequestTimeoutException` — **not** retried and not counted by the breaker: the handler may already have run |
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
- [ ] A failing handler climbs the **full** ladder — four deliveries — and lands in the DLQ
- [ ] A retried message returns to the exact queue it came from, service and event queues alike
- [ ] `retry = false` reaches the DLQ on the first failure
- [ ] A request **and** a fire-and-forget to a nonexistent service fail in milliseconds, with
      a copy preserved in `surf.unroutable`
- [ ] A timeout is neither retried nor opens the breaker; a publish failure does both
- [ ] One dead service does not open the breaker of a healthy one
- [ ] `rabbit.rpc<T>()` round-trips through a generated proxy against a real broker
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
