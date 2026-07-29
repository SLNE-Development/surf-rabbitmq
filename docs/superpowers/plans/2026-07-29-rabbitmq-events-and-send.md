# RabbitMQ Events and Fire-and-Forget Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Do NOT use subagent-driven-development.** The repository owner's global instructions forbid delegating work to subagents.

**Prerequisite:** Plan 2 (`2026-07-29-rabbitmq-topology-foundation.md`) must be complete. This plan builds on `SurfRabbitApi`, `RabbitTopology` and `RabbitTopologyDeclarer`.

**Goal:** Add publish/subscribe over a topic exchange and fire-and-forget sending, so a microservice can announce what happened without waiting for anyone, and so events reach either every instance or exactly one, as the handler chooses.

**Architecture:** Events are published to the `surf.events` topic exchange with a routing key declared on the event type. Subscribers bind either a durable queue shared by all instances of a service (`SHARED`) or an ephemeral queue per process (`BROADCAST`). Message TTL moves from a global setting to a per-message-kind decision, because a fire-and-forget message must outlive a request timeout while an RPC request must not.

**Tech Stack:** Kotlin (JVM toolchain 25), amqp-client 5.34.0, kotlinx.serialization CBOR, JUnit 5, Testcontainers.

## Global Constraints

- Everything from Plan 2's Global Constraints still applies.
- Events exchange, verbatim from the spec: `surf.events`, type `topic`, durable.
- `SHARED` is the **default** subscription mode. `BROADCAST` must be requested explicitly.
- Message TTL by kind, verbatim from the spec:

  | Kind | `expiration` |
  |---|---|
  | RPC request | `requestTimeoutSeconds` |
  | RPC response | `requestTimeoutSeconds` |
  | Fire-and-forget | none |
  | Event | none |

- Integration tests are tagged `integration` and skipped without Docker. Never report a
  skipped test as passing.
- Commit after every task.

## File Structure

| File | Responsibility |
|---|---|
| `surf-rabbitmq-api/.../event/RabbitEventPacket.kt` | Base type for events |
| `surf-rabbitmq-api/.../event/RabbitEvent.kt` | Annotation carrying the topic |
| `surf-rabbitmq-api/.../event/RabbitSubscribe.kt` | Annotation marking a handler method |
| `surf-rabbitmq-api/.../event/SubscriptionMode.kt` | `SHARED` / `BROADCAST` |
| `surf-rabbitmq-core/.../event/EventTopics.kt` | Reading and validating topics |
| `surf-rabbitmq-core/.../event/EventSubscriptionRegistry.kt` | Discovery of subscribe methods |
| `surf-rabbitmq-core/.../event/EventDispatcher.kt` | Delivery to handlers |
| `surf-rabbitmq-core/.../publish/MessageKind.kt` | TTL and delivery mode per kind |

---

### Task 1: Event types and the topic annotation

Pure declarations plus topic validation. All unit-testable without a broker, and topic validation is worth testing because a malformed pattern binds silently and simply never matches.

**Files:**
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitEventPacket.kt`
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitEvent.kt`
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitSubscribe.kt`
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/SubscriptionMode.kt`
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopics.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsTest.kt`

**Interfaces:**
- Consumes: `RabbitPacket` (existing)
- Produces:
  ```kotlin
  abstract class RabbitEventPacket : RabbitPacket()

  annotation class RabbitEvent(val topic: String)
  annotation class RabbitSubscribe(
      val topic: String = "",
      val mode: SubscriptionMode = SubscriptionMode.SHARED,
      val retry: Boolean = true
  )
  enum class SubscriptionMode { SHARED, BROADCAST }

  object EventTopics {
      fun topicOf(eventClass: Class<out RabbitEventPacket>): String
      fun validatePublishTopic(topic: String)
      fun validateBindingPattern(pattern: String)
      fun matches(pattern: String, topic: String): Boolean
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable
@RabbitEvent("faction.disbanded")
class FactionDisbandedTestEvent(val id: String) : RabbitEventPacket()

@Serializable
class UnannotatedTestEvent : RabbitEventPacket()

class EventTopicsTest {

    @Test
    fun `the topic comes from the annotation`() {
        assertEquals("faction.disbanded", EventTopics.topicOf(FactionDisbandedTestEvent::class.java))
    }

    @Test
    fun `an unannotated event is rejected with a helpful message`() {
        val thrown = assertFailsWith<IllegalStateException> {
            EventTopics.topicOf(UnannotatedTestEvent::class.java)
        }

        assertTrue(
            thrown.message!!.contains("@RabbitEvent"),
            "the message must name the missing annotation, but was: ${thrown.message}"
        )
    }

    @Test
    fun `publish topics may not contain wildcards`() {
        // A wildcard in a publish key is not an error on the broker - it is simply
        // treated as a literal and matches nothing, which is far harder to debug.
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.*") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.#") }
    }

    @Test
    fun `publish topics reject empty segments`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction..x") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic(".faction") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.") }
    }

    @Test
    fun `a blank publish topic is rejected`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("   ") }
    }

    @Test
    fun `valid publish topics are accepted`() {
        EventTopics.validatePublishTopic("faction.disbanded")
        EventTopics.validatePublishTopic("player.punish.ban")
        EventTopics.validatePublishTopic("single")
    }

    @Test
    fun `binding patterns may contain wildcards`() {
        EventTopics.validateBindingPattern("faction.*")
        EventTopics.validateBindingPattern("faction.#")
        EventTopics.validateBindingPattern("#")
        EventTopics.validateBindingPattern("faction.*.disbanded")
    }

    @Test
    fun `star matches exactly one segment`() {
        assertTrue(EventTopics.matches("faction.*", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*", "faction.a.b"))
        assertFalse(EventTopics.matches("faction.*", "faction"))
    }

    @Test
    fun `hash matches zero or more segments`() {
        assertTrue(EventTopics.matches("faction.#", "faction.a.b"))
        assertTrue(EventTopics.matches("faction.#", "faction.a"))
        assertTrue(EventTopics.matches("faction.#", "faction"))
        assertTrue(EventTopics.matches("#", "anything.at.all"))
    }

    @Test
    fun `a wildcard in the middle matches one segment`() {
        assertTrue(EventTopics.matches("faction.*.disbanded", "faction.abc.disbanded"))
        assertFalse(EventTopics.matches("faction.*.disbanded", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*.disbanded", "faction.a.b.disbanded"))
    }

    @Test
    fun `an exact pattern matches only itself`() {
        assertTrue(EventTopics.matches("faction.disbanded", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.disbanded", "faction.created"))
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*EventTopicsTest*'`
Expected: `Unresolved reference: RabbitEventPacket`.

- [ ] **Step 3: Create the API types**

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitEventPacket.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.event

import dev.slne.surf.rabbitmq.api.packet.RabbitPacket
import kotlinx.serialization.Serializable

/**
 * Base type for events published to the `surf.events` topic exchange.
 *
 * Unlike a request, an event has no reply and no known recipient. The publisher does not know
 * whether anyone is listening, which is exactly what keeps services decoupled: adding or
 * removing a subscriber never touches the publisher.
 *
 * Subclasses must be `@Serializable` and annotated with [RabbitEvent].
 *
 * ```kotlin
 * @Serializable
 * @RabbitEvent("faction.disbanded")
 * class FactionDisbandedEvent(val factionId: String) : RabbitEventPacket()
 * ```
 */
@Serializable
abstract class RabbitEventPacket : RabbitPacket()
```

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitEvent.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.event

/**
 * Declares the topic an event is published under.
 *
 * The topic lives on the type rather than at the call site, so a publisher cannot accidentally
 * send the same event under two different keys.
 *
 * Topics are dot-separated, e.g. `faction.disbanded` or `player.punish.ban`. Wildcards are not
 * allowed here — they belong in [RabbitSubscribe] patterns.
 *
 * @property topic the routing key used when publishing
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitEvent(val topic: String)
```

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/SubscriptionMode.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.event

/**
 * How an event is distributed among the instances of one service.
 *
 * This is the single most consequential choice when subscribing, because it decides whether a
 * handler runs once or once per running instance.
 */
enum class SubscriptionMode {
    /**
     * Exactly **one** instance of the service handles each event.
     *
     * All instances consume one durable queue, so the event also survives every instance being
     * offline. Correct for anything with a side effect — database writes, statistics, webhooks.
     *
     * This is the default: handling an event once when you wanted all instances is a delay,
     * while handling it on all instances when you wanted one is duplicated work.
     */
    SHARED,

    /**
     * **Every** instance handles each event.
     *
     * Each process owns an auto-deleting queue, so events sent while a process is down are
     * lost. Correct for refreshing per-process state — cache invalidation, config reload,
     * kicking a player.
     */
    BROADCAST
}
```

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/RabbitSubscribe.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.event

/**
 * Marks a method as an event handler.
 *
 * The method must take exactly one parameter, a subtype of [RabbitEventPacket], and may be a
 * `suspend` function.
 *
 * ```kotlin
 * object CacheListener {
 *     // one instance handles it - the default
 *     @RabbitSubscribe
 *     suspend fun onDisbanded(event: FactionDisbandedEvent) { … }
 *
 *     // every instance handles it
 *     @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
 *     suspend fun onReload(event: ConfigReloadedEvent) { … }
 *
 *     // wider pattern than the event's own topic
 *     @RabbitSubscribe(topic = "faction.#")
 *     suspend fun onAnyFactionEvent(event: FactionEvent) { … }
 * }
 * ```
 *
 * @property topic binding pattern; defaults to the event type's own [RabbitEvent.topic].
 *   May contain `*` (exactly one segment) and `#` (zero or more segments).
 * @property mode whether one instance or every instance handles the event
 * @property retry whether a failed handler is retried. Set `false` for handlers that are not
 *   idempotent — a retried handler may run twice for the same event.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitSubscribe(
    val topic: String = "",
    val mode: SubscriptionMode = SubscriptionMode.SHARED,
    val retry: Boolean = true
)
```

- [ ] **Step 4: Implement topic handling**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopics.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket

/**
 * Reads, validates and matches event topics.
 *
 * Validation matters more here than elsewhere: the broker accepts almost any routing key, so a
 * malformed topic produces no error at all — the binding simply never matches and the event
 * disappears. Rejecting it at registration turns a silent runtime loss into a startup failure.
 */
object EventTopics {

    private val segmentPattern = "[a-zA-Z0-9_-]+".toRegex()

    /** The topic declared by [eventClass]'s [RabbitEvent] annotation. */
    fun topicOf(eventClass: Class<out RabbitEventPacket>): String {
        val annotation = eventClass.getAnnotation(RabbitEvent::class.java)
            ?: error(
                "Event ${eventClass.name} is missing @RabbitEvent. " +
                        "Add @RabbitEvent(\"some.topic\") to declare the topic it publishes under."
            )

        validatePublishTopic(annotation.topic)

        return annotation.topic
    }

    /**
     * Validates a topic used for **publishing**.
     *
     * Wildcards are rejected: the broker would treat them as literal characters, and the event
     * would match no binding at all.
     */
    fun validatePublishTopic(topic: String) {
        require(topic.isNotBlank()) { "Event topic must not be blank" }

        require(!topic.contains('*') && !topic.contains('#')) {
            "Event topic '$topic' must not contain wildcards. Wildcards belong in " +
                    "@RabbitSubscribe patterns; in a publish key they match nothing."
        }

        val segments = topic.split('.')
        require(segments.all { it.matches(segmentPattern) }) {
            "Event topic '$topic' must consist of dot-separated segments of letters, " +
                    "digits, '_' or '-'"
        }
    }

    /** Validates a pattern used for **binding**. Wildcards are permitted. */
    fun validateBindingPattern(pattern: String) {
        require(pattern.isNotBlank()) { "Subscription pattern must not be blank" }

        val segments = pattern.split('.')
        require(segments.all { it == "*" || it == "#" || it.matches(segmentPattern) }) {
            "Subscription pattern '$pattern' must consist of dot-separated segments, " +
                    "each either '*', '#', or letters, digits, '_' or '-'"
        }
    }

    /**
     * Whether [topic] matches [pattern] under AMQP topic rules.
     *
     * Mirrors the broker's own matching so that subscriptions can be unit-tested without one.
     * `*` matches exactly one segment, `#` matches zero or more.
     */
    fun matches(pattern: String, topic: String): Boolean =
        matches(pattern.split('.'), topic.split('.'))

    private fun matches(pattern: List<String>, topic: List<String>): Boolean {
        if (pattern.isEmpty()) return topic.isEmpty()

        return when (val head = pattern.first()) {
            "#" -> {
                // '#' consumes any number of segments, so try every split point.
                val rest = pattern.drop(1)
                if (rest.isEmpty()) return true

                (0..topic.size).any { skipped -> matches(rest, topic.drop(skipped)) }
            }

            "*" -> topic.isNotEmpty() && matches(pattern.drop(1), topic.drop(1))

            else -> topic.isNotEmpty() &&
                    topic.first() == head &&
                    matches(pattern.drop(1), topic.drop(1))
        }
    }
}
```

- [ ] **Step 5: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*EventTopicsTest*'`
Expected: `BUILD SUCCESSFUL`, 11 tests passed.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(events): add event base type, annotations and topic validation"
```

---

### Task 2: Message kinds and per-kind TTL

Fixes the bug where `ClientRabbitMQConnectionImpl.kt:312` applies the request timeout as `expiration` to every outgoing message. Correct for RPC, wrong for fire-and-forget, which must survive until the service returns.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/publish/MessageKind.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/publish/MessageKindTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  ```kotlin
  enum class MessageKind {
      RPC_REQUEST, RPC_RESPONSE, FIRE_AND_FORGET, EVENT;

      fun expirationMillis(requestTimeout: Duration): String?
      fun deliveryMode(persistRequests: Boolean, persistResponses: Boolean): Int
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/publish/MessageKindTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.publish

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MessageKindTest {

    private val timeout = 60.seconds

    @Test
    fun `an rpc request expires with the request timeout`() {
        // Once the caller has given up, the request must not be executed minutes later.
        assertEquals("60000", MessageKind.RPC_REQUEST.expirationMillis(timeout))
    }

    @Test
    fun `an rpc response expires with the request timeout`() {
        assertEquals("60000", MessageKind.RPC_RESPONSE.expirationMillis(timeout))
    }

    @Test
    fun `a fire-and-forget message never expires`() {
        // It must wait in the durable queue until the service returns, however long that takes.
        assertNull(
            MessageKind.FIRE_AND_FORGET.expirationMillis(timeout),
            "expiring fire-and-forget would silently discard work while a service is down"
        )
    }

    @Test
    fun `an event never expires`() {
        assertNull(MessageKind.EVENT.expirationMillis(timeout))
    }

    @Test
    fun `requests and fire-and-forget follow the persistence setting`() {
        assertEquals(2, MessageKind.RPC_REQUEST.deliveryMode(true, false))
        assertEquals(1, MessageKind.RPC_REQUEST.deliveryMode(false, false))
        assertEquals(2, MessageKind.FIRE_AND_FORGET.deliveryMode(true, false))
    }

    @Test
    fun `responses follow the response persistence setting`() {
        assertEquals(2, MessageKind.RPC_RESPONSE.deliveryMode(true, true))
        assertEquals(1, MessageKind.RPC_RESPONSE.deliveryMode(true, false))
    }

    @Test
    fun `events are always persistent`() {
        // A SHARED subscription targets a durable queue; a transient message there would be
        // lost on broker restart for no benefit.
        assertEquals(2, MessageKind.EVENT.deliveryMode(false, false))
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*MessageKindTest*'`
Expected: `Unresolved reference: MessageKind`.

- [ ] **Step 3: Implement**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/publish/MessageKind.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.publish

import kotlin.time.Duration

/**
 * What kind of message is being published, which determines its expiry and persistence.
 *
 * Before this existed, the request timeout was applied as `expiration` to every outgoing
 * message. That is right for a request nobody is waiting for any more, and wrong for a
 * fire-and-forget message, which has to survive in the queue until its service comes back.
 */
enum class MessageKind {
    /** A request whose caller is waiting for a reply. */
    RPC_REQUEST,

    /** A reply to an [RPC_REQUEST]. */
    RPC_RESPONSE,

    /** A message to one service instance with no reply expected. */
    FIRE_AND_FORGET,

    /** An event published to the topic exchange. */
    EVENT;

    /**
     * The AMQP `expiration` for this kind, or `null` for no expiry.
     *
     * @param requestTimeout how long a caller waits for a reply
     */
    fun expirationMillis(requestTimeout: Duration): String? = when (this) {
        // Nobody is waiting once the timeout has passed; executing it then would apply a
        // stale decision.
        RPC_REQUEST, RPC_RESPONSE -> requestTimeout.inWholeMilliseconds.toString()

        // No caller is waiting, so there is nothing to go stale. Expiring these would throw
        // away work whenever a service was down longer than a request timeout.
        FIRE_AND_FORGET, EVENT -> null
    }

    /**
     * The AMQP delivery mode: `2` persistent, `1` transient.
     */
    fun deliveryMode(persistRequests: Boolean, persistResponses: Boolean): Int = when (this) {
        RPC_REQUEST, FIRE_AND_FORGET -> if (persistRequests) 2 else 1
        RPC_RESPONSE -> if (persistResponses) 2 else 1

        // Events may target a durable shared queue, where a transient message would be
        // dropped on broker restart without any upside.
        EVENT -> 2
    }
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*MessageKindTest*'`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 5: Apply it in the connection**

In `RabbitConnectionImpl`, replace every hand-built `BasicProperties` with:

```kotlin
    private fun properties(
        kind: MessageKind,
        correlationId: String? = null,
        replyTo: String? = null
    ): AMQP.BasicProperties = AMQP.BasicProperties.Builder()
        .deliveryMode(
            kind.deliveryMode(
                persistRequests = api.config.isPersistRequests(),
                persistResponses = api.config.isPersistResponses()
            )
        )
        .also { builder ->
            correlationId?.let(builder::correlationId)
            replyTo?.let(builder::replyTo)
            kind.expirationMillis(requestTimeoutSeconds)?.let(builder::expiration)
        }
        .headers(mapOf(RabbitMqVersion.AMQP_HEADER to RabbitMqVersion.CURRENT.toString()))
        .build()
```

Call it with `MessageKind.RPC_REQUEST` in the request path and `MessageKind.RPC_RESPONSE` in
the reply path.

- [ ] **Step 6: Compile and commit**

Run: `./gradlew build -PskipIntegration`
Expected: `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "fix(publish): set message TTL per kind instead of globally

Fire-and-forget and events no longer inherit the request timeout as their
expiration, which would have discarded them while a service was down."
```

---

### Task 3: Subscription discovery

Finds `@RabbitSubscribe` methods and turns them into binding descriptions. Pure reflection, no broker needed.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscription.kt`
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistry.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistryTest.kt`

**Interfaces:**
- Consumes: `EventTopics` (Task 1)
- Produces:
  ```kotlin
  data class EventSubscription(
      val eventClass: Class<out RabbitEventPacket>,
      val pattern: String,
      val mode: SubscriptionMode,
      val retry: Boolean,
      val listener: Any,
      val method: Method
  )

  class EventSubscriptionRegistry {
      fun register(listener: Any)
      fun subscriptions(): List<EventSubscription>
      fun patternsFor(mode: SubscriptionMode): Set<String>
      fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription>
      fun isEmpty(): Boolean
  }
  ```

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistryTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
@RabbitEvent("faction.disbanded")
class DisbandedEvent(val id: String) : RabbitEventPacket()

@Serializable
@RabbitEvent("config.reloaded")
class ReloadedEvent : RabbitEventPacket()

class EventSubscriptionRegistryTest {

    object Listener {
        @RabbitSubscribe
        suspend fun onDisbanded(event: DisbandedEvent) = Unit

        @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
        suspend fun onReloaded(event: ReloadedEvent) = Unit

        @RabbitSubscribe(topic = "faction.#", retry = false)
        suspend fun onAnyFaction(event: DisbandedEvent) = Unit
    }

    object BadArity {
        @RabbitSubscribe
        fun tooMany(a: DisbandedEvent, b: String) = Unit
    }

    object BadType {
        @RabbitSubscribe
        fun notAnEvent(a: String) = Unit
    }

    object BadPattern {
        @RabbitSubscribe(topic = "faction..x")
        fun bad(a: DisbandedEvent) = Unit
    }

    @Test
    fun `subscriptions are discovered`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertEquals(3, registry.subscriptions().size)
    }

    @Test
    fun `the pattern defaults to the event's own topic`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onDisbanded" }
        assertEquals("faction.disbanded", sub.pattern)
    }

    @Test
    fun `an explicit topic overrides the event's own`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onAnyFaction" }
        assertEquals("faction.#", sub.pattern)
    }

    @Test
    fun `the default mode is SHARED`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onDisbanded" }
        assertEquals(
            SubscriptionMode.SHARED, sub.mode,
            "SHARED must be the default: defaulting to BROADCAST would silently run every " +
                    "side-effecting handler once per instance"
        )
    }

    @Test
    fun `an explicit mode is honoured`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val sub = registry.subscriptions().first { it.method.name == "onReloaded" }
        assertEquals(SubscriptionMode.BROADCAST, sub.mode)
    }

    @Test
    fun `retry defaults to true and can be disabled`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertTrue(registry.subscriptions().first { it.method.name == "onDisbanded" }.retry)
        assertTrue(!registry.subscriptions().first { it.method.name == "onAnyFaction" }.retry)
    }

    @Test
    fun `patterns are grouped by mode`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        assertEquals(
            setOf("faction.disbanded", "faction.#"),
            registry.patternsFor(SubscriptionMode.SHARED)
        )
        assertEquals(setOf("config.reloaded"), registry.patternsFor(SubscriptionMode.BROADCAST))
    }

    @Test
    fun `a wrong parameter count is rejected at registration`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadArity)
        }
        assertTrue(thrown.message!!.contains("exactly one parameter"))
    }

    @Test
    fun `a non-event parameter is rejected at registration`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadType)
        }
        assertTrue(thrown.message!!.contains("RabbitEventPacket"))
    }

    @Test
    fun `a malformed pattern is rejected at registration`() {
        // Better a startup failure than a binding that silently never matches.
        assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadPattern)
        }
    }

    @Test
    fun `an event is routed to every matching subscription`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Listener)

        val matching = registry.subscriptionsFor(DisbandedEvent::class.java, "faction.disbanded")

        assertEquals(
            2, matching.size,
            "both the exact subscription and the faction.# subscription must match"
        )
    }

    @Test
    fun `a fresh registry is empty`() {
        assertTrue(EventSubscriptionRegistry().isEmpty())
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*EventSubscriptionRegistryTest*'`
Expected: `Unresolved reference: EventSubscriptionRegistry`.

- [ ] **Step 3: Implement**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscription.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import java.lang.reflect.Method

/** One `@RabbitSubscribe` method and the binding it requires. */
data class EventSubscription(
    val eventClass: Class<out RabbitEventPacket>,
    val pattern: String,
    val mode: SubscriptionMode,
    val retry: Boolean,
    val listener: Any,
    val method: Method
)
```

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistry.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Discovers `@RabbitSubscribe` methods and reports the bindings they need.
 *
 * Everything is validated at registration rather than on delivery. A malformed pattern binds
 * without complaint on the broker and then matches nothing, so the only visible symptom would
 * be an event that never arrives — which is nearly impossible to diagnose in production.
 */
class EventSubscriptionRegistry {

    private val entries = CopyOnWriteArrayList<EventSubscription>()

    /**
     * Registers every annotated method on [listener].
     *
     * @throws IllegalArgumentException if a method has the wrong shape or an invalid pattern
     */
    fun register(listener: Any) {
        for (method in listener.javaClass.declaredMethods) {
            val annotation = method.getAnnotation(RabbitSubscribe::class.java) ?: continue

            // A suspend function carries a hidden trailing Continuation parameter.
            val isSuspend = method.parameterTypes.lastOrNull()?.name ==
                    "kotlin.coroutines.Continuation"
            val declaredCount = if (isSuspend) method.parameterCount - 1 else method.parameterCount

            require(declaredCount == 1) {
                "@RabbitSubscribe method ${listener.javaClass.name}#${method.name} must take " +
                        "exactly one parameter, but takes $declaredCount"
            }

            val parameterType = method.parameterTypes[0]
            require(RabbitEventPacket::class.java.isAssignableFrom(parameterType)) {
                "@RabbitSubscribe method ${listener.javaClass.name}#${method.name} must take a " +
                        "RabbitEventPacket subtype, but takes ${parameterType.name}"
            }

            @Suppress("UNCHECKED_CAST")
            val eventClass = parameterType as Class<out RabbitEventPacket>

            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
            EventTopics.validateBindingPattern(pattern)

            method.isAccessible = true

            entries += EventSubscription(
                eventClass = eventClass,
                pattern = pattern,
                mode = annotation.mode,
                retry = annotation.retry,
                listener = listener,
                method = method
            )
        }
    }

    fun subscriptions(): List<EventSubscription> = entries.toList()

    /** The binding patterns needed for [mode]'s queue. */
    fun patternsFor(mode: SubscriptionMode): Set<String> =
        entries.filter { it.mode == mode }.map { it.pattern }.toSet()

    /**
     * Every subscription that should receive an event of [eventClass] published under [topic].
     *
     * More than one may match: a handler bound to `faction.disbanded` and another bound to
     * `faction.#` both receive the same event, and both must run.
     */
    fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription> =
        entries.filter {
            it.eventClass.isAssignableFrom(eventClass) && EventTopics.matches(it.pattern, topic)
        }

    fun isEmpty(): Boolean = entries.isEmpty()
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*EventSubscriptionRegistryTest*'`
Expected: `BUILD SUCCESSFUL`, 12 tests passed.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(events): discover and validate @RabbitSubscribe methods"
```

---

### Task 4: Publishing and consuming events

Wires subscriptions to the broker and adds `publish` and `send` to the API.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventDispatcher.kt`
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/RabbitConnectionImpl.kt`
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyDeclarer.kt`
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApi.kt`
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/connection/RabbitMQConnection.kt`

**Interfaces:**
- Consumes: Tasks 1–3
- Produces:
  ```kotlin
  // SurfRabbitApi
  suspend fun publish(event: RabbitEventPacket)
  suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget? = null)
  fun registerListener(listener: Any)

  // RabbitTopologyDeclarer
  fun declareSharedEventQueue(serviceName: String, patterns: Set<String>): String
  fun declareInstanceEventQueue(instanceId: String, patterns: Set<String>): String
  ```

- [ ] **Step 1: Add the event queue declarations**

Append to `RabbitTopologyDeclarer`:

```kotlin
    /**
     * Declares the durable queue shared by all instances of [serviceName] and binds it to
     * every pattern in [patterns].
     *
     * Because all instances consume this one queue, exactly one of them handles each event.
     *
     * @return the queue name
     */
    fun declareSharedEventQueue(serviceName: String, patterns: Set<String>): String {
        val queue = RabbitTopology.sharedEventQueue(serviceName)
        channel.queueDeclare(queue, true, false, false, QueueArguments.sharedEventQueue())

        for (pattern in patterns) {
            channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, pattern)
        }

        return queue
    }

    /**
     * Declares this process's private event queue and binds it to every pattern in [patterns].
     *
     * Each instance owns one, so every instance receives its own copy of a matching event.
     * Exclusive and auto-deleting: events sent while the process is down are not retained.
     *
     * @return the queue name
     */
    fun declareInstanceEventQueue(instanceId: String, patterns: Set<String>): String {
        val queue = RabbitTopology.instanceEventQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())

        for (pattern in patterns) {
            channel.queueBind(queue, RabbitTopology.EVENTS_EXCHANGE, pattern)
        }

        return queue
    }
```

- [ ] **Step 2: Implement the dispatcher**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventDispatcher.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Invokes the handler methods matching a delivered event.
 *
 * Every matching subscription runs, including overlapping patterns: a handler bound to
 * `faction.disbanded` and one bound to `faction.#` both fire for the same event.
 *
 * If any handler throws, the exception propagates so the consumer can nack the delivery and
 * let the retry machinery in Plan 4 take over.
 */
class EventDispatcher(
    private val registry: EventSubscriptionRegistry,
    private val scope: CoroutineScope
) {
    companion object {
        private val log = logger()
    }

    /**
     * Runs every subscription matching [event] and [topic].
     *
     * @throws Throwable the first handler failure, after all handlers have been attempted
     */
    suspend fun dispatch(event: RabbitEventPacket, topic: String) {
        val matching = registry.subscriptionsFor(event.javaClass, topic)

        if (matching.isEmpty()) {
            log.atFine().log("No subscription matched event %s on topic %s", event.javaClass.name, topic)
            return
        }

        var firstFailure: Throwable? = null

        for (subscription in matching) {
            try {
                invoke(subscription, event)
            } catch (cause: Throwable) {
                // Keep going: one broken handler must not stop its unrelated neighbours.
                log.atSevere()
                    .withCause(cause)
                    .log(
                        "Handler %s#%s failed for event %s",
                        subscription.listener.javaClass.name,
                        subscription.method.name,
                        event.javaClass.name
                    )

                if (firstFailure == null) firstFailure = cause
            }
        }

        firstFailure?.let { throw it }
    }

    private suspend fun invoke(subscription: EventSubscription, event: RabbitEventPacket) {
        val method = subscription.method
        val isSuspend = method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"

        if (isSuspend) {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                method.invoke(subscription.listener, event, continuation)
            }
        } else {
            method.invoke(subscription.listener, event)
        }
    }
}
```

- [ ] **Step 3: Wire publishing and consuming into the connection**

In `RabbitConnectionImpl`, add:

```kotlin
    private val subscriptions = EventSubscriptionRegistry()
    private val eventDispatcher by lazy { EventDispatcher(subscriptions, api.scope) }

    override fun registerListener(listener: Any) {
        subscriptions.register(listener)
    }

    override suspend fun publishEvent(event: RabbitEventPacket) {
        val topic = EventTopics.topicOf(event.javaClass)
        val serializer = eventSerializerCache.get(event.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(event.javaClass.name)

        val body = RabbitPacketSerializer.serializeEvent(api, serializer, event)

        client.publish(
            exchange = RabbitTopology.EVENTS_EXCHANGE,
            routingKey = topic,
            body = body,
            properties = properties(MessageKind.EVENT),
            // An event with no subscriber is normal, not an error. Requesting a return
            // would make every unobserved event look like a failure.
            mandatory = false
        )
    }
```

In `connect()`, after the reply queue is set up:

```kotlin
        if (!subscriptions.isEmpty()) {
            eventConsumer = client.newConsumer("events")

            val sharedPatterns = subscriptions.patternsFor(SubscriptionMode.SHARED)
            if (sharedPatterns.isNotEmpty()) {
                val queue = eventConsumer.withChannel { channel ->
                    RabbitTopologyDeclarer(channel)
                        .declareSharedEventQueue(api.identity.serviceName, sharedPatterns)
                }
                startConsumingEvents(queue)
            }

            val instancePatterns = subscriptions.patternsFor(SubscriptionMode.BROADCAST)
            if (instancePatterns.isNotEmpty()) {
                val queue = eventConsumer.withChannel { channel ->
                    RabbitTopologyDeclarer(channel)
                        .declareInstanceEventQueue(api.identity.instanceId, instancePatterns)
                }
                startConsumingEvents(queue)
            }
        }
```

And the consumer itself:

```kotlin
    private suspend fun startConsumingEvents(queue: String) {
        eventConsumer.consume(
            queue = queue,
            autoAck = false,
            prefetchCount = api.config.getServerPrefetchCount(),
            // Requeueing would spin: the message returns to the queue head and fails again
            // immediately. Plan 4 replaces this with delayed retry queues.
            requeueOnHandlerError = false
        ) { _, message, ack ->
            val topic = message.envelope.routingKey

            try {
                val event = RabbitPacketSerializer.deserializeEvent(
                    api, message.body, eventNameCache
                )
                eventDispatcher.dispatch(event, topic)
                ack.ack()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                log.atWarning()
                    .withCause(t)
                    .log("Failed to handle event on topic %s", topic)

                ack.nack(requeue = false)
            }
        }
    }
```

- [ ] **Step 4: Add fire-and-forget**

In `RabbitConnectionImpl`:

```kotlin
    override suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget) {
        val serializer = requestSerializerCache.get(packet.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(packet.javaClass.name)

        val body = RabbitPacketSerializer.serializeRequest(api, serializer, packet)

        client.publish(
            exchange = RabbitTopology.RPC_EXCHANGE,
            routingKey = target.routingKey,
            body = body,
            // No correlationId and no replyTo: the receiver must not attempt to answer.
            properties = properties(MessageKind.FIRE_AND_FORGET),
            mandatory = true
        )
    }
```

In the server request path, treat a delivery without `replyTo` as fire-and-forget rather than
rejecting it. Replace the current early nack:

```kotlin
            if (correlationId == null || replyTo == null) {
                ack.nack(requeue = false)
                return@consume
            }
```

with:

```kotlin
            // Fire-and-forget messages deliberately carry neither. Handle them and ack,
            // but never try to reply.
            val fireAndForget = correlationId == null || replyTo == null
```

and pass `replyTo = null` into the handler so `replyToRequest` is skipped.

- [ ] **Step 5: Expose it on the API**

Add to `SurfRabbitApi`:

```kotlin
    /**
     * Publishes [event] to every matching subscriber.
     *
     * The publisher does not know who listens, and an event with no subscriber is discarded
     * without error. That is the point: adding or removing a subscriber never touches the
     * publisher.
     */
    suspend fun publish(event: RabbitEventPacket) {
        connection.publishEvent(event)
    }

    /**
     * Sends [packet] to one instance of [target] without waiting for a reply.
     *
     * Unlike an event, this is delivered to exactly one instance and waits in a durable queue
     * if none is running, so the work is done once the service returns.
     *
     * Defaults to this process's own service when [target] is omitted.
     */
    suspend fun send(packet: RabbitRequestPacket<*>, target: RabbitTarget? = null) {
        connection.send(packet, target ?: RabbitTarget.ServiceTarget(identity.serviceName))
    }

    /** Registers `@RabbitSubscribe` methods on [listener]. */
    fun registerListener(listener: Any) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        connection.registerListener(listener)
    }
```

Add the matching members to `RabbitMQConnection`.

- [ ] **Step 6: Add the event serializer helpers**

In `RabbitPacketSerializer`, add `serializeEvent` and `deserializeEvent` mirroring the existing
request methods, using `RabbitEventPacket` in place of `RabbitRequestPacket`.

- [ ] **Step 7: Compile**

Run: `./gradlew build -PskipIntegration`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(events): publish and consume events, add fire-and-forget send"
```

---

### Task 5: Integration tests for both subscription modes

The one property that must be proven against a real broker: `BROADCAST` reaches every instance, `SHARED` reaches exactly one. Getting this backwards is the failure mode that multiplies a database write by the instance count.

**Files:**
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventDeliveryTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4
- Produces: nothing

- [ ] **Step 1: Write the tests**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventDeliveryTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
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
```

- [ ] **Step 2: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test --tests '*EventDeliveryTest*'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

If `a shared event reaches exactly one instance` reports 3, each instance declared its own
queue: check that `declareSharedEventQueue` uses `serviceName`, not `instanceId`.

If `a broadcast event reaches every instance` reports 1, all instances shared one queue:
check that `declareInstanceEventQueue` uses `instanceId`, not `serviceName`.

Without Docker: skipped, record as unverified.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(events): verify SHARED delivers once and BROADCAST delivers to all"
```

---

### Task 6: Document the event API

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Write the documentation**

Append to `README.md`:

````markdown
## Events

Events go to the `surf.events` topic exchange. The publisher does not know who listens, so
adding or removing a subscriber never touches the publisher.

```kotlin
@Serializable
@RabbitEvent("faction.disbanded")
class FactionDisbandedEvent(val factionId: UUID) : RabbitEventPacket()

rabbit.publish(FactionDisbandedEvent(id))
```

### Choosing a subscription mode

This is the decision that matters. It controls whether a handler runs once or once per running
instance.

| Mode | Runs on | Survives downtime | Use for |
|---|---|---|---|
| `SHARED` (default) | exactly one instance | yes, durable queue | database writes, statistics, webhooks |
| `BROADCAST` | every instance | no, ephemeral queue | cache invalidation, config reload, kicking a player |

With eight instances of `surf-transaction` running:

```kotlin
// wrong - writes to the database eight times
@RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
suspend fun onBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}

// right - exactly one instance writes
@RabbitSubscribe
suspend fun onBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}
```

On Paper and Velocity, `BROADCAST` is usually what you want: every server has to invalidate its
own local state.

### Patterns

`*` matches exactly one segment, `#` matches zero or more.

```kotlin
@RabbitSubscribe(topic = "faction.*.disbanded")   // faction.abc.disbanded
@RabbitSubscribe(topic = "player.#")              // player.punish.ban, player.join, player
```

## Fire-and-forget

Delivered to exactly one instance, with no reply awaited:

```kotlin
rabbit.send(PlayerKilledPacket(killer, victim))
rabbit.send(TransferPlayerPacket(uuid), target = InstanceTarget("lobby-3"))
```

Unlike an event, this waits in a durable queue when no instance is running, so the work happens
once the service comes back. Choose it over a broadcast whenever the message must not be lost.
````

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document events, subscription modes and fire-and-forget"
```

---

## Done when

- [ ] `./gradlew build -PskipIntegration` succeeds
- [ ] With Docker: all `EventDeliveryTest` cases pass
- [ ] `BROADCAST` reaches all three instances, `SHARED` reaches exactly one
- [ ] A `SHARED` event published while every instance was down is delivered after restart
- [ ] Fire-and-forget carries no `expiration`; RPC requests still do

## Deliberately out of scope

- **Retry and DLQ for failed event handlers** — Plan 4. Until then `retry = false` is recorded
  but not acted on, and a failed handler nacks straight to nowhere.
- **Circuit breaker** — Plan 4.
- **KSP `@RpcService(service = ...)`** — Plan 4.
- **Ordering guarantees across instances.** Not provided, by design; enforce per-entity
  consistency inside the service.
