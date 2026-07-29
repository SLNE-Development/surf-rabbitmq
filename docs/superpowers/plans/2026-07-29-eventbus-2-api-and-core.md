# surf-eventbus Plan 2: API und Kern des Event-Bus

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein vollständiger, transport-unabhängiger Event-Bus — Registrierung, Validierung,
Capability-Prüfung, Dispatch, Provider-Auswahl und Lebenszyklus — beweisbar korrekt **ohne
jeden Broker**, verifiziert über einen In-Memory-Transport.

**Architecture:** Zwei neue Module. `surf-eventbus-api` enthält die Typen, die sowohl Consumer
als auch Transports brauchen: Event-Basisklasse, Annotationen, Topic-Matcher, Capabilities und
das SPI. `surf-eventbus-core` enthält die Mechanik: Registry, Dispatcher, Capability-Validator,
Provider-Auswahl und die Bus-Implementierung. Transports kennen nur `-api`, sodass keine Zyklen
entstehen. Der Dispatch nutzt dieselben JVM-Hidden-Class-Invoker wie surf-redis heute.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization, `dev.slne.surf.api.core.invoker.InvokerFactory`,
Google AutoService, JUnit 5, kotlinx-coroutines-test.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-surf-eventbus-design.md`
- Voraussetzung: Plan 1 abgeschlossen (`surf-eventbus-common` existiert, Build grün)
- Kein Modul dieses Plans darf `com.rabbitmq`, `org.redisson` oder `Rabbit*`/`Redis*`-Typen
  referenzieren. Der Purity-Test aus Plan 1 wird in Task 1 auf die neuen Module erweitert
- Alle Tests dieses Plans laufen **ohne Docker**. Kein Test dieses Plans trägt
  `@RequiresDocker`
- Fehlermeldungstexte sind Teil der API und werden wortgleich getestet
- Registrierung wird bei der Registrierung validiert, nie erst bei der Zustellung
- Registrierte Muster benutzen AMQP-Semantik: `*` trifft genau ein Segment, `#` null oder mehr
- Alle `suspend`-Grenzen liegen an der Naht zum Transport; die Registry ist synchron

---

### Task 1: Modul surf-eventbus-api mit Event-Basisklasse und Annotationen

**Files:**
- Create: `surf-eventbus-api/build.gradle.kts`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/InternalEventBus.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/event/SurfBusEvent.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/event/BusEvent.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/event/SurfSubscribe.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/event/SubscriptionMode.kt`
- Create: `surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/event/SurfBusEventTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (Opt-in für `InternalEventBus`)

**Interfaces:**
- Consumes: nichts
- Produces:
  - `dev.slne.surf.eventbus.InternalEventBus` — Opt-in-Annotation
  - `dev.slne.surf.eventbus.event.SurfBusEvent` — abstrakte Basisklasse mit
    `val originInstanceId: String`, `val publishedAtEpochMs: Long` und
    `@InternalEventBus fun applyMetadata(originInstanceId: String, publishedAtEpochMs: Long)`
  - `@BusEvent(val topic: String)` — Klassen-Annotation
  - `@SurfSubscribe(topic: String = "", mode: SubscriptionMode = SHARED, retry: Boolean = true, includeSelf: Boolean = false)`
  - `enum class SubscriptionMode { SHARED, BROADCAST }`

- [ ] **Step 1: Modul anlegen**

`surf-eventbus-api/build.gradle.kts`:

```kotlin
@file:OptIn(ExperimentalAbiValidation::class)

import dev.slne.surf.api.gradle.util.slneReleases
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfEventbusCommon)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

kotlin {
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("dev.slne.surf.eventbus.InternalEventBus")
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    repositories {
        slneReleases()
    }
}
```

In `settings.gradle.kts` nach `include("surf-eventbus-common")`:

```kotlin
include("surf-eventbus-api")
```

In `build.gradle.kts` im `optIn`-Block ergänzen:

```kotlin
                optIn.add("dev.slne.surf.eventbus.InternalEventBus")
```

- [ ] **Step 2: Failing test schreiben**

`surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/event/SurfBusEventTest.kt`:

```kotlin
package dev.slne.surf.eventbus.event

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@Serializable
@BusEvent("test.thing.happened")
private class ThingHappenedEvent(val payload: String) : SurfBusEvent()

class SurfBusEventTest {

    @Test
    fun `metadata defaults to empty before it is applied`() {
        val event = ThingHappenedEvent("x")

        assertEquals("", event.originInstanceId)
        assertEquals(0L, event.publishedAtEpochMs)
    }

    @Test
    fun `applyMetadata populates origin and timestamp`() {
        val event = ThingHappenedEvent("x")

        event.applyMetadata("lobby-3", 1234L)

        assertEquals("lobby-3", event.originInstanceId)
        assertEquals(1234L, event.publishedAtEpochMs)
    }

    @Test
    fun `metadata is not part of the serialized payload`() {
        val event = ThingHappenedEvent("x").also { it.applyMetadata("lobby-3", 1234L) }

        val encoded = Json.encodeToString(ThingHappenedEvent.serializer(), event)

        // Metadata travels in the transport envelope, not in the body. Serializing it here
        // would put two sources of truth on the wire and let a publisher forge an origin.
        assertFalse(encoded.contains("lobby-3"), "origin leaked into the payload: $encoded")
        assertFalse(encoded.contains("1234"), "timestamp leaked into the payload: $encoded")
    }

    @Test
    fun `the topic is readable from the annotation`() {
        val topic = ThingHappenedEvent::class.java.getAnnotation(BusEvent::class.java).topic

        assertEquals("test.thing.happened", topic)
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-api:test`
Expected: FAIL — `Unresolved reference: SurfBusEvent`

- [ ] **Step 4: Typen implementieren**

`InternalEventBus.kt`:

```kotlin
package dev.slne.surf.eventbus

/**
 * Marks API that exists for transports and the bus implementation, not for consumers.
 *
 * Anything annotated with this may change or disappear without notice.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal surf-eventbus API - not intended for consumers."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalEventBus
```

`SubscriptionMode.kt`:

```kotlin
package dev.slne.surf.eventbus.event

/**
 * How an event is distributed among the instances of one service.
 *
 * This is the most consequential choice when subscribing, because it decides whether a handler
 * runs once or once per running instance. It is also the one mode a provider can refuse: only
 * a transport with a durable per-service queue can offer [SHARED].
 */
enum class SubscriptionMode {
    /**
     * Exactly **one** instance of the service handles each event.
     *
     * Requires a durable queue shared by all instances of the service, so the event also
     * survives every instance being offline. Correct for anything with a side effect —
     * database writes, statistics, webhooks.
     *
     * The default: handling an event once when all instances were meant to is a delay, while
     * handling it everywhere when one was meant to is duplicated work.
     */
    SHARED,

    /**
     * **Every** instance handles each event.
     *
     * Ephemeral per-process delivery, so events sent while a process is down are lost.
     * Correct for refreshing per-process state — cache invalidation, config reload, kicking
     * a player.
     */
    BROADCAST
}
```

`BusEvent.kt`:

```kotlin
package dev.slne.surf.eventbus.event

/**
 * Declares the topic an event is published under.
 *
 * The topic lives on the type rather than at the call site, so a publisher cannot accidentally
 * send the same event under two different keys.
 *
 * Topics are dot-separated, e.g. `faction.disbanded` or `player.punish.ban`. Wildcards are not
 * allowed here — they belong in [SurfSubscribe] patterns, where they mean something.
 *
 * @property topic the routing key used when publishing
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BusEvent(val topic: String)
```

`SurfSubscribe.kt`:

```kotlin
package dev.slne.surf.eventbus.event

/**
 * Marks a method as an event handler.
 *
 * The method must take exactly one parameter, a subtype of [SurfBusEvent], and may be a
 * `suspend` function.
 *
 * ```kotlin
 * object CacheListener {
 *     // exactly one instance handles it - the default
 *     @SurfSubscribe
 *     suspend fun onDisbanded(event: FactionDisbandedEvent) { … }
 *
 *     // every instance handles it
 *     @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
 *     suspend fun onReload(event: ConfigReloadedEvent) { … }
 *
 *     // wider pattern than the event's own topic
 *     @SurfSubscribe(topic = "faction.#", mode = SubscriptionMode.BROADCAST)
 *     suspend fun onAnyFactionEvent(event: FactionEvent) { … }
 * }
 * ```
 *
 * @property topic binding pattern; defaults to the event type's own [BusEvent.topic].
 *   May contain `*` (exactly one segment) and `#` (zero or more segments).
 * @property mode whether one instance or every instance handles the event. A provider that
 *   cannot offer the requested mode fails the start rather than degrading it silently.
 * @property retry whether a failed handler is retried. Set `false` for handlers that are not
 *   idempotent — a retried handler may run twice for the same event. Providers without retry
 *   support warn about this at startup instead of pretending.
 * @property includeSelf whether events published by this very process reach this handler.
 *   Defaults to `false`, which is what nearly every handler wants and the most common cause
 *   of feedback loops when forgotten. Only valid together with
 *   [SubscriptionMode.BROADCAST] — see the rejection reason in the bus documentation.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SurfSubscribe(
    val topic: String = "",
    val mode: SubscriptionMode = SubscriptionMode.SHARED,
    val retry: Boolean = true,
    val includeSelf: Boolean = false
)
```

`SurfBusEvent.kt`:

```kotlin
package dev.slne.surf.eventbus.event

import dev.slne.surf.eventbus.InternalEventBus
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Base type for events distributed through a [dev.slne.surf.eventbus.SurfEventBus].
 *
 * Unlike a request, an event has no reply and no known recipient. The publisher does not know
 * whether anyone is listening, which is exactly what keeps services decoupled: adding or
 * removing a subscriber never touches the publisher.
 *
 * Subclasses must be `@Serializable` and annotated with [BusEvent].
 *
 * ```kotlin
 * @Serializable
 * @BusEvent("faction.disbanded")
 * class FactionDisbandedEvent(val factionId: UUID) : SurfBusEvent()
 * ```
 */
@Serializable
abstract class SurfBusEvent {

    /**
     * The instance that published this event, or `""` on an event that has not been
     * published yet.
     *
     * Metadata is [Transient] on purpose: it travels in the transport envelope, not in the
     * body. Serializing it would put two sources of truth on the wire.
     */
    @Transient
    var originInstanceId: String = ""
        private set

    /** When the publisher created this event, epoch milliseconds; `0` before publishing. */
    @Transient
    var publishedAtEpochMs: Long = 0L
        private set

    @InternalEventBus
    fun applyMetadata(originInstanceId: String, publishedAtEpochMs: Long) {
        this.originInstanceId = originInstanceId
        this.publishedAtEpochMs = publishedAtEpochMs
    }
}
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-api:test`
Expected: PASS, vier Tests

- [ ] **Step 6: Purity-Test auf die neuen Module ausweiten**

Der Purity-Test aus Plan 1 prüft nur `surf-rabbitmq-core`. Die neuen Module sind per
Konstruktion transportfrei — das wird jetzt maschinell festgehalten. Neue Datei
`surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/TransportNeutralityTest.kt`:

```kotlin
package dev.slne.surf.eventbus

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * The event bus must not know its transports.
 *
 * A single `com.rabbitmq` or `org.redisson` import here would make the provider abstraction a
 * fiction, and nothing else in the build would notice.
 */
class TransportNeutralityTest {

    private val forbidden = listOf(
        "com.rabbitmq",
        "org.redisson",
        "SurfRabbitApi",
        "RedisApi",
        "RabbitPacket",
        "RedisEvent"
    )

    @Test
    fun `the api module references no transport`() {
        val root = Path.of("src", "main", "kotlin")

        if (!Files.exists(root)) fail("Expected sources at ${root.toAbsolutePath()}")

        val offenders = Files.walk(root).asSequence()
            .filter { it.extension == "kt" || it.extension == "java" }
            .mapNotNull { file ->
                val text = file.readText()
                forbidden.firstOrNull { text.contains(it) }?.let { "$file references '$it'" }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("The event bus API must stay transport-neutral:\n" + offenders.joinToString("\n"))
        }
    }
}
```

Run: `./gradlew :surf-eventbus-api:test`
Expected: PASS, fünf Tests

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(eventbus): add the event base type and subscription annotations"
```

---

### Task 2: EventTopics in das API-Modul verschieben

Der Matcher bildet die Broker-Semantik nach und ist bereits unit-getestet. Er wird von beiden
Transports gebraucht — der Redis-Transport benutzt ihn für lokales Matching, weil Redis-Glob
Punkte überquert und AMQPs `*` nicht.

**Files:**
- Move: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopics.kt`
  → `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/topic/EventTopics.kt`
- Move: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsTest.kt`
  → `surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/topic/EventTopicsTest.kt`

**Interfaces:**
- Consumes: `BusEvent`, `SurfBusEvent` aus Task 1
- Produces: `dev.slne.surf.eventbus.topic.EventTopics` mit
  - `fun topicOf(eventClass: Class<out SurfBusEvent>): String`
  - `fun validatePublishTopic(topic: String)`
  - `fun validateBindingPattern(pattern: String)`
  - `fun matches(pattern: String, topic: String): Boolean`

- [ ] **Step 1: Dateien verschieben**

```bash
mkdir -p surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/topic
mkdir -p surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/topic
git mv surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopics.kt \
       surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/topic/EventTopics.kt
git mv surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsTest.kt \
       surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/topic/EventTopicsTest.kt
```

- [ ] **Step 2: Auf die neuen Typen umschreiben**

In `EventTopics.kt` die Kopfzeilen ersetzen — `package`, Importe und die Signatur von
`topicOf`, sowie die Fehlermeldung, die die Annotation nennt:

```kotlin
package dev.slne.surf.eventbus.topic

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
```

`topicOf` wird zu:

```kotlin
    /** The topic declared by [eventClass]'s [BusEvent] annotation. */
    fun topicOf(eventClass: Class<out SurfBusEvent>): String {
        val annotation = eventClass.getAnnotation(BusEvent::class.java)
            ?: error(
                "Event ${eventClass.name} is missing @BusEvent. " +
                        "Add @BusEvent(\"some.topic\") to declare the topic it publishes under."
            )

        validatePublishTopic(annotation.topic)

        return annotation.topic
    }
```

In den KDoc-Kommentaren `RabbitEvent` → `BusEvent` und `@RabbitSubscribe` → `@SurfSubscribe`
ersetzen. Der restliche Rumpf (`validatePublishTopic`, `validateBindingPattern`, `matches`,
`segmentPattern`) bleibt unverändert — er kennt keine Transport-Typen.

In `EventTopicsTest.kt` das `package` auf `dev.slne.surf.eventbus.topic` setzen und Importe
sowie Testfixtures auf `SurfBusEvent`/`@BusEvent` umstellen.

- [ ] **Step 3: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-api:test`
Expected: PASS. Die bestehenden Matcher-Tests laufen unverändert durch; sie prüfen
Zeichenketten, keine Transport-Typen.

- [ ] **Step 4: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: FAIL in `surf-rabbitmq-core` — `RabbitConnectionImpl` und
`EventSubscriptionRegistry` importieren `EventTopics` noch aus dem alten Package. Das ist
erwartet und wird in Plan 3 aufgelöst, wenn der Rabbit-Transport entsteht. Für diesen Plan
genügt die Zwischenlösung des nächsten Schritts.

- [ ] **Step 5: Rabbit-Seite überbrücken**

`surf-rabbitmq-core` bekommt vorübergehend die Abhängigkeit auf das API-Modul und einen
Alias, damit der Build während Plan 2 grün bleibt. In `surf-rabbitmq-core/build.gradle.kts`:

```kotlin
    api(projects.surfEventbusApi)
```

Neue Datei
`surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsBridge.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core.event

/**
 * Transitional alias while the rabbit event path still speaks `RabbitEventPacket`.
 *
 * Plan 3 moves that path behind `EventTransport` and deletes this file. Until then the
 * matcher lives in surf-eventbus-api and the rabbit code reaches it through here.
 */
internal typealias EventTopics = dev.slne.surf.eventbus.topic.EventTopics
```

Der bisherige Aufruf `EventTopics.topicOf(eventClass)` in `EventSubscriptionRegistry` und
`RabbitConnectionImpl` übergibt eine `Class<out RabbitEventPacket>`, die neue Signatur
erwartet `Class<out SurfBusEvent>`. Beide Aufrufstellen werden deshalb auf die
Annotation-freie Variante umgestellt, indem der Topic dort gelesen wird, wo er schon bekannt
ist: In `EventSubscriptionRegistry.register` ersetzt

```kotlin
            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
```

durch

```kotlin
            val pattern = annotation.topic.ifBlank { rabbitTopicOf(eventClass) }
```

und in derselben Datei am Ende ergänzen:

```kotlin
/**
 * Reads `@RabbitEvent` for the legacy rabbit event path.
 *
 * Plan 3 removes `@RabbitEvent` entirely; until then this keeps the legacy path compiling
 * without weakening the new `EventTopics.topicOf` signature to `Class<*>`.
 */
private fun rabbitTopicOf(eventClass: Class<out RabbitEventPacket>): String {
    val annotation = eventClass.getAnnotation(RabbitEvent::class.java)
        ?: error("Event ${eventClass.name} is missing @RabbitEvent")

    EventTopics.validatePublishTopic(annotation.topic)
    return annotation.topic
}
```

Dieselbe Ersetzung in `RabbitConnectionImpl.publishEvent` (`EventTopics.topicOf(event.javaClass)`
→ eine lokale Kopie derselben privaten Funktion oder ein Aufruf der oben angelegten, dann
`internal` statt `private`). Empfohlen: die Funktion `internal` machen und aus beiden Stellen
aufrufen.

- [ ] **Step 6: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(eventbus): move the topic matcher into the bus api"
```

---

### Task 3: Capabilities und Transport-SPI

**Files:**
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/Provider.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/TransportCapability.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/TopicBinding.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventMessage.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventSink.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventTypeResolver.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventTransport.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventTransportContext.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/transport/EventTransportFactory.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/exception/exceptions.kt`

**Interfaces:**
- Consumes: `SurfBusEvent`, `SubscriptionMode` aus Task 1
- Produces:
  - `enum class Provider(val id: String) { RABBIT("rabbit"), REDIS("redis") }`
  - `enum class TransportCapability { BROADCAST, SHARED, RETRY, DEAD_LETTER }`
  - `data class TopicBinding(val pattern: String, val mode: SubscriptionMode)`
  - `class OutgoingEvent(val event: SurfBusEvent, val topic: String, val typeName: String, val originInstanceId: String, val publishedAtEpochMs: Long)`
  - `class IncomingEvent(val event: SurfBusEvent, val topic: String, val originInstanceId: String, val publishedAtEpochMs: Long)`
  - `fun interface EventSink { suspend fun accept(incoming: IncomingEvent) }`
  - `fun interface EventTypeResolver { fun resolve(typeName: String): Class<out SurfBusEvent>? }`
  - `interface EventTransport` mit `id`, `instanceId`, `capabilities`, `transportApi`,
    `suspend fun start(bindings: Set<TopicBinding>, sink: EventSink)`,
    `suspend fun publish(outgoing: OutgoingEvent)`, `suspend fun stop()`
  - `class EventTransportContext(serviceName, instanceName, dataPath, serializers, typeResolver, scope)`
  - `interface EventTransportFactory { val provider: Provider; fun create(context: EventTransportContext): EventTransport }`
  - Exceptions: `SurfEventBusException`, `UnsupportedSubscriptionModeException`,
    `IllegalSubscriptionException`, `NoTransportAvailableException`,
    `AmbiguousTransportException`, `WrongTransportTypeException`

- [ ] **Step 1: Provider und Capabilities**

`Provider.kt`:

```kotlin
package dev.slne.surf.eventbus

/**
 * Which transport delivers events.
 *
 * A fleet-wide decision, not a per-process one: two providers in one fleet are two disjoint
 * event universes, and an event published on one never reaches the other.
 */
enum class Provider(val id: String, val moduleName: String) {
    /** RabbitMQ. Offers every capability, including durable `SHARED` delivery. */
    RABBIT("rabbit", "surf-rabbitmq-core"),

    /** Redis Pub/Sub. Broadcast only — no durability, no retry, no dead-lettering. */
    REDIS("redis", "surf-redis-core");

    companion object {
        /** Parses [id] case-insensitively, or `null` if no provider carries it. */
        fun byIdOrNull(id: String): Provider? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
```

`transport/TransportCapability.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

/**
 * What a transport can actually guarantee.
 *
 * A transport declares its set once; the bus checks every subscription against it at
 * `freeze()`. This turns a delivery guarantee from a line of documentation into a checked
 * property — the whole reason a provider is selectable at all.
 */
enum class TransportCapability {
    /** Every instance receives its own copy of an event. Every transport can do this. */
    BROADCAST,

    /**
     * Exactly one instance of a service receives each event, durably.
     *
     * Requires a queue shared by all instances of the service that outlives them.
     */
    SHARED,

    /** A failing handler gets the event redelivered on a delay ladder. */
    RETRY,

    /** An event that keeps failing ends up somewhere inspectable instead of vanishing. */
    DEAD_LETTER
}
```

- [ ] **Step 2: Nachrichten- und Bindungstypen**

`transport/TopicBinding.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.event.SubscriptionMode

/**
 * One pattern this process wants delivered, and how.
 *
 * The bus hands the full set to the transport once, at `connect()`. Handlers must therefore be
 * registered before then — otherwise a message could arrive for a binding that does not exist
 * yet, or a binding could be missing for a handler that does.
 */
data class TopicBinding(val pattern: String, val mode: SubscriptionMode)
```

`transport/EventMessage.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.event.SurfBusEvent

/**
 * An event on its way out, with the metadata the transport puts into its envelope.
 *
 * @property typeName fully qualified class name, so the receiver can find the serializer
 */
class OutgoingEvent(
    val event: SurfBusEvent,
    val topic: String,
    val typeName: String,
    val originInstanceId: String,
    val publishedAtEpochMs: Long
)

/**
 * An event a transport decoded and is handing to the bus.
 *
 * The transport has already resolved the type and deserialized the body; the bus decides
 * which handlers see it.
 */
class IncomingEvent(
    val event: SurfBusEvent,
    val topic: String,
    val originInstanceId: String,
    val publishedAtEpochMs: Long
)
```

`transport/EventSink.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

/**
 * Where a transport delivers what it received.
 *
 * Throwing from [accept] tells the transport that handling failed, which is what drives its
 * retry decision. A transport without `TransportCapability.RETRY` can only log it.
 */
fun interface EventSink {
    suspend fun accept(incoming: IncomingEvent)
}
```

`transport/EventTypeResolver.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.event.SurfBusEvent

/**
 * Maps a wire type name to the class to deserialize into.
 *
 * Returning `null` means this process has no use for the event; the transport then drops it
 * loudly rather than requeuing it forever.
 */
fun interface EventTypeResolver {
    fun resolve(typeName: String): Class<out SurfBusEvent>?
}
```

- [ ] **Step 3: Transport-SPI**

`transport/EventTransport.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider

/**
 * A concrete way of getting events from one process to another.
 *
 * Implemented once per provider. The transport owns its connection, its wire encoding and its
 * delivery guarantees; the bus owns registration, matching and dispatch. That split is what
 * lets the same handler code run on either provider — and [capabilities] is what keeps the
 * split honest where it cannot.
 */
@InternalEventBus
interface EventTransport {

    val provider: Provider

    /**
     * This process's identity as the transport sees it.
     *
     * The bus stamps it onto every published event, and compares it against an incoming
     * event's origin to honour `@SurfSubscribe(includeSelf = false)`. It comes from the
     * transport rather than the bus so that both sides agree on one id.
     */
    val instanceId: String

    val capabilities: Set<TransportCapability>

    /**
     * The transport's own API, for the things no provider abstraction should hide — RPC on
     * RabbitMQ, sync structures on Redis.
     *
     * Reached through `SurfEventBus.transport<T>()`, which is deliberately typed and
     * deliberately explicit: using it means leaving portability behind.
     */
    val transportApi: Any

    /** Subscribes to [bindings] and delivers everything matching to [sink]. */
    suspend fun start(bindings: Set<TopicBinding>, sink: EventSink)

    suspend fun publish(outgoing: OutgoingEvent)

    suspend fun stop()
}
```

`transport/EventTransportContext.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.InternalEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

/**
 * Everything a transport needs to build itself.
 *
 * @property serviceName the logical service, shared by every instance
 * @property instanceName a stable instance id, or `null` for a generated one
 * @property dataPath where this process's own configuration lives
 * @property serializers additional serializers for event payloads
 * @property typeResolver which wire type names this process can decode
 * @property scope the bus's scope; a transport must not outlive it
 */
@InternalEventBus
class EventTransportContext(
    val serviceName: String,
    val instanceName: String?,
    val dataPath: Path,
    val serializers: SerializersModule,
    val typeResolver: EventTypeResolver,
    val scope: CoroutineScope
)
```

`transport/EventTransportFactory.kt`:

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider

/**
 * Discovered via `ServiceLoader`. One implementation per transport module on the classpath.
 *
 * Enumerated rather than resolved to exactly one: having both transports available is a
 * legitimate build, and which one runs is a configuration decision.
 */
@InternalEventBus
interface EventTransportFactory {
    val provider: Provider
    fun create(context: EventTransportContext): EventTransport
}
```

- [ ] **Step 4: Exceptions**

`exception/exceptions.kt`:

```kotlin
package dev.slne.surf.eventbus.exception

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.event.SubscriptionMode

open class SurfEventBusException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * A handler asked for a delivery mode its provider cannot offer.
 *
 * Deliberately fatal rather than a warning: the mode decides how *often* a handler runs. A
 * `SHARED` handler silently degraded to `BROADCAST` would run once per instance and multiply
 * every side effect; silently dropped, the event would vanish without trace.
 */
class UnsupportedSubscriptionModeException(
    val handler: String,
    val mode: SubscriptionMode,
    val provider: Provider,
    val supported: Set<SubscriptionMode>
) : SurfEventBusException(
    buildString {
        append("Handler $handler uses mode $mode, but provider ${provider.name} supports ")
        append("only ${supported.joinToString(", ")}. ")
        append("SHARED requires a durable per-service queue. ")
        append("-> use provider RABBIT, or change the handler to BROADCAST if duplicate ")
        append("execution on every instance is safe.")
    }
)

/** A handler is shaped or configured in a way that cannot work. */
class IllegalSubscriptionException(message: String) : SurfEventBusException(message)

/**
 * A handler failed, carrying the retry intent the transport needs.
 *
 * The bus knows each subscription's `retry` flag; the transport knows how to redeliver. This
 * exception is how the first travels to the second. A non-idempotent subscription among the
 * matches vetoes retry for all of them: they arrive as one message, and re-running the
 * idempotent handler alongside the non-idempotent one is not an option.
 */
class EventHandlingFailure(
    val retryable: Boolean,
    override val cause: Throwable
) : SurfEventBusException(
    "Event handling failed (retryable=$retryable): ${cause.message}",
    cause
)

class NoTransportAvailableException : SurfEventBusException(
    "No EventTransportFactory found on the classpath. Add " +
            Provider.entries.joinToString(" or ") { it.moduleName } +
            " as a runtime dependency."
)

class AmbiguousTransportException(available: Set<Provider>) : SurfEventBusException(
    "Found ${available.size} event transports on the classpath " +
            "(${available.joinToString(", ") { it.id }}) and no provider configured. " +
            "Set eventbus.provider or SURF_EVENTBUS_PROVIDER: the choice decides the " +
            "delivery guarantee, so it must not be guessed."
)

class WrongTransportTypeException(requested: String, actual: String, provider: Provider) :
    SurfEventBusException(
        "transport<$requested>() was called, but provider ${provider.name} exposes " +
                "$actual. Transport-specific APIs are not portable between providers."
    )
```

- [ ] **Step 5: Build und Neutralitätstest**

Run: `./gradlew :surf-eventbus-api:test`
Expected: PASS. Insbesondere `TransportNeutralityTest` bleibt grün: `Provider` nennt RabbitMQ
und Redis nur in Kommentaren und Enum-Namen, nicht als Typen. Sollte der Test wegen des
Wortes `RedisApi` in der KDoc von `EventTransport.transportApi` anschlagen, wird der
Kommentar umformuliert (`sync structures on the Redis provider`), **nicht** die verbotene
Liste gekürzt.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(eventbus): add capabilities and the transport SPI"
```

---

### Task 4: Modul surf-eventbus-core mit Subscription-Registry

**Files:**
- Create: `surf-eventbus-core/build.gradle.kts`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscription.kt`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscriptionRegistry.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscriptionRegistryTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `SurfSubscribe`, `SubscriptionMode`, `SurfBusEvent`, `EventTopics`,
  `TopicBinding`, `EventTypeResolver`, `IllegalSubscriptionException`
- Produces:
  - `data class EventSubscription(eventClass: Class<out SurfBusEvent>, pattern: String, mode: SubscriptionMode, retry: Boolean, includeSelf: Boolean, listener: Any, method: Method)`
    mit `val handlerName: String` = `"${listener.javaClass.simpleName}#${method.name}"`
  - `class EventSubscriptionRegistry : EventTypeResolver` mit
    `fun register(listener: Any)`, `fun subscriptions(): List<EventSubscription>`,
    `fun bindings(): Set<TopicBinding>`, `fun isEmpty(): Boolean`,
    `fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription>`,
    `override fun resolve(typeName: String): Class<out SurfBusEvent>?`

- [ ] **Step 1: Modul anlegen**

`surf-eventbus-core/build.gradle.kts`:

```kotlin
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfEventbusApi)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    repositories {
        slneReleases()
    }
}
```

In `settings.gradle.kts`:

```kotlin
include("surf-eventbus-core")
```

- [ ] **Step 2: Failing test schreiben**

`surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscriptionRegistryTest.kt`:

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.exception.IllegalSubscriptionException
import dev.slne.surf.eventbus.transport.TopicBinding
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
@BusEvent("faction.disbanded")
class FactionDisbandedEvent(val id: String) : SurfBusEvent()

@Serializable
@BusEvent("faction.created")
class FactionCreatedEvent(val id: String) : SurfBusEvent()

class EventSubscriptionRegistryTest {

    class Valid {
        @SurfSubscribe
        fun onDisbanded(event: FactionDisbandedEvent) = Unit

        @SurfSubscribe(topic = "faction.#", mode = SubscriptionMode.BROADCAST)
        fun onAny(event: SurfBusEvent) = Unit
    }

    class TooManyParameters {
        @SurfSubscribe
        fun onDisbanded(event: FactionDisbandedEvent, extra: String) = Unit
    }

    class WrongParameterType {
        @SurfSubscribe
        fun onSomething(value: String) = Unit
    }

    class BadPattern {
        @SurfSubscribe(topic = "faction..disbanded")
        fun onDisbanded(event: FactionDisbandedEvent) = Unit
    }

    class SelfInclusionOnShared {
        @SurfSubscribe(mode = SubscriptionMode.SHARED, includeSelf = true)
        fun onDisbanded(event: FactionDisbandedEvent) = Unit
    }

    @Test
    fun `registers a handler with the event's own topic as its pattern`() {
        val registry = EventSubscriptionRegistry()

        registry.register(Valid())

        val patterns = registry.subscriptions().map { it.pattern }.toSet()
        assertTrue("faction.disbanded" in patterns)
        assertTrue("faction.#" in patterns)
    }

    @Test
    fun `reports one binding per pattern and mode`() {
        val registry = EventSubscriptionRegistry()

        registry.register(Valid())

        assertEquals(
            setOf(
                TopicBinding("faction.disbanded", SubscriptionMode.SHARED),
                TopicBinding("faction.#", SubscriptionMode.BROADCAST)
            ),
            registry.bindings()
        )
    }

    @Test
    fun `rejects a handler with more than one parameter`() {
        val failure = assertFailsWith<IllegalSubscriptionException> {
            EventSubscriptionRegistry().register(TooManyParameters())
        }

        assertTrue(failure.message!!.contains("exactly one parameter"), failure.message!!)
    }

    @Test
    fun `rejects a handler whose parameter is not an event`() {
        val failure = assertFailsWith<IllegalSubscriptionException> {
            EventSubscriptionRegistry().register(WrongParameterType())
        }

        assertTrue(failure.message!!.contains("SurfBusEvent"), failure.message!!)
    }

    @Test
    fun `rejects a malformed pattern at registration`() {
        // A malformed pattern binds without complaint on a broker and then matches nothing.
        // The only symptom would be an event that never arrives.
        assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(BadPattern())
        }
    }

    @Test
    fun `rejects includeSelf together with SHARED`() {
        val failure = assertFailsWith<IllegalSubscriptionException> {
            EventSubscriptionRegistry().register(SelfInclusionOnShared())
        }

        assertTrue(failure.message!!.contains("includeSelf"), failure.message!!)
        assertTrue(failure.message!!.contains("BROADCAST"), failure.message!!)
    }

    @Test
    fun `matches a subscription on a supertype parameter`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Valid())

        val matching = registry.subscriptionsFor(
            FactionCreatedEvent::class.java,
            "faction.created"
        )

        assertEquals(1, matching.size, "the faction.# handler on SurfBusEvent must match")
        assertEquals("faction.#", matching.single().pattern)
    }

    @Test
    fun `resolves a registered type by its wire name`() {
        val registry = EventSubscriptionRegistry()
        registry.register(Valid())

        assertEquals(
            FactionDisbandedEvent::class.java,
            registry.resolve(FactionDisbandedEvent::class.java.name)
        )
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: FAIL — `Unresolved reference: EventSubscriptionRegistry`

- [ ] **Step 4: EventSubscription implementieren**

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import java.lang.reflect.Method

/** One `@SurfSubscribe` method and the binding it requires. */
data class EventSubscription(
    val eventClass: Class<out SurfBusEvent>,
    val pattern: String,
    val mode: SubscriptionMode,
    val retry: Boolean,
    val includeSelf: Boolean,
    val listener: Any,
    val method: Method
) {
    /** How this handler is named in diagnostics and in capability failures. */
    val handlerName: String = "${listener.javaClass.simpleName}#${method.name}"
}
```

- [ ] **Step 5: EventSubscriptionRegistry implementieren**

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.exception.IllegalSubscriptionException
import dev.slne.surf.eventbus.topic.EventTopics
import dev.slne.surf.eventbus.transport.EventTypeResolver
import dev.slne.surf.eventbus.transport.TopicBinding
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Discovers `@SurfSubscribe` methods and reports the bindings they need.
 *
 * Everything is validated here rather than on delivery. A malformed pattern binds without
 * complaint on a broker and then matches nothing, so the only visible symptom would be an
 * event that never arrives — which is nearly impossible to diagnose in production.
 *
 * Doubles as the [EventTypeResolver]: a process can only decode events some handler asked for.
 */
class EventSubscriptionRegistry : EventTypeResolver {

    private val entries = CopyOnWriteArrayList<EventSubscription>()

    /**
     * Registers every annotated method on [listener].
     *
     * @throws IllegalSubscriptionException if a method has the wrong shape or an impossible
     *   combination of options
     * @throws IllegalArgumentException if a pattern is not valid binding syntax
     */
    fun register(listener: Any) {
        for (method in listener.javaClass.declaredMethods) {
            val annotation = method.getAnnotation(SurfSubscribe::class.java) ?: continue

            // A suspend function carries a hidden trailing Continuation parameter.
            val isSuspend = method.parameterTypes.lastOrNull()?.name ==
                    "kotlin.coroutines.Continuation"
            val declaredCount =
                if (isSuspend) method.parameterCount - 1 else method.parameterCount

            val name = "${listener.javaClass.name}#${method.name}"

            if (declaredCount != 1) {
                throw IllegalSubscriptionException(
                    "@SurfSubscribe method $name must take exactly one parameter, " +
                            "but takes $declaredCount"
                )
            }

            val parameterType = method.parameterTypes[0]
            if (!SurfBusEvent::class.java.isAssignableFrom(parameterType)) {
                throw IllegalSubscriptionException(
                    "@SurfSubscribe method $name must take a SurfBusEvent subtype, " +
                            "but takes ${parameterType.name}"
                )
            }

            if (annotation.includeSelf && annotation.mode == SubscriptionMode.SHARED) {
                throw IllegalSubscriptionException(
                    "@SurfSubscribe method $name combines includeSelf with SHARED. " +
                            "A SHARED event was taken from a queue shared by the whole " +
                            "service; dropping it locally would ack it unprocessed and lose " +
                            "it for every instance. Use BROADCAST, or drop includeSelf."
                )
            }

            @Suppress("UNCHECKED_CAST")
            val eventClass = parameterType as Class<out SurfBusEvent>

            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
            EventTopics.validateBindingPattern(pattern)

            method.isAccessible = true

            entries += EventSubscription(
                eventClass = eventClass,
                pattern = pattern,
                mode = annotation.mode,
                retry = annotation.retry,
                includeSelf = annotation.includeSelf,
                listener = listener,
                method = method
            )
        }
    }

    fun subscriptions(): List<EventSubscription> = entries.toList()

    /** The bindings the transport has to establish for this process. */
    fun bindings(): Set<TopicBinding> =
        entries.map { TopicBinding(it.pattern, it.mode) }.toSet()

    fun isEmpty(): Boolean = entries.isEmpty()

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

    override fun resolve(typeName: String): Class<out SurfBusEvent>? =
        entries.firstOrNull { it.eventClass.name == typeName }?.eventClass
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, neun Tests

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(eventbus): add the subscription registry with registration-time validation"
```

---

### Task 5: Typauflösung für Handler auf Basistypen

`EventSubscriptionRegistry.resolve` findet nur Typen, die ein Handler wörtlich deklariert hat.
Ein Handler auf `SurfBusEvent` mit `topic = "faction.#"` erhält damit **nichts**: auf dem Draht
steht `FactionDisbandedEvent`, registriert ist `SurfBusEvent`. Genau diese Lücke hat der
heutige Rabbit-Pfad auch (`KotlinSerializerNameCache.register(subscription.eventClass)` gegen
`deserializeEvent`-Lookup nach Wire-Namen) — das polymorphe Abonnement ist dort dokumentiert,
aber nicht funktionsfähig. Diese Task schließt sie.

**Files:**
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/registry/ClassLoadingTypeResolver.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/registry/ClassLoadingTypeResolverTest.kt`

**Interfaces:**
- Consumes: `EventTypeResolver`, `EventSubscriptionRegistry`
- Produces: `class ClassLoadingTypeResolver(private val registry: EventSubscriptionRegistry) : EventTypeResolver`
  mit `override fun resolve(typeName: String): Class<out SurfBusEvent>?`

- [ ] **Step 1: Failing test schreiben**

`ClassLoadingTypeResolverTest.kt`:

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
@BusEvent("resolver.concrete")
class ConcreteResolvableEvent(val value: String) : SurfBusEvent()

class ClassLoadingTypeResolverTest {

    class BaseTypeListener {
        @SurfSubscribe(topic = "resolver.#", mode = SubscriptionMode.BROADCAST)
        fun onAny(event: SurfBusEvent) = Unit
    }

    private fun resolver(): ClassLoadingTypeResolver {
        val registry = EventSubscriptionRegistry()
        registry.register(BaseTypeListener())
        return ClassLoadingTypeResolver(registry)
    }

    @Test
    fun `resolves a concrete type no handler declared but the classpath provides`() {
        assertEquals(
            ConcreteResolvableEvent::class.java,
            resolver().resolve(ConcreteResolvableEvent::class.java.name)
        )
    }

    @Test
    fun `returns null for a type that is not on the classpath`() {
        assertNull(resolver().resolve("dev.slne.surf.nowhere.GhostEvent"))
    }

    @Test
    fun `returns null for a class that is not an event`() {
        // Loadable, but decoding it as an event would be nonsense.
        assertNull(resolver().resolve("java.lang.String"))
    }

    @Test
    fun `caches a negative result`() {
        val resolver = resolver()

        assertNull(resolver.resolve("dev.slne.surf.nowhere.GhostEvent"))
        assertNull(resolver.resolve("dev.slne.surf.nowhere.GhostEvent"))
    }

    @Test
    fun `prefers a type a handler declared literally`() {
        val registry = EventSubscriptionRegistry()
        registry.register(object {
            @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
            fun on(event: ConcreteResolvableEvent) = Unit
        })

        assertEquals(
            ConcreteResolvableEvent::class.java,
            ClassLoadingTypeResolver(registry).resolve(ConcreteResolvableEvent::class.java.name)
        )
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test --tests '*ClassLoadingTypeResolverTest*'`
Expected: FAIL — `Unresolved reference: ClassLoadingTypeResolver`

- [ ] **Step 3: Implementieren**

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventTypeResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a wire type name, falling back to the classpath for types no handler declared.
 *
 * A handler may subscribe to a pattern with a base type — `@SurfSubscribe(topic =
 * "faction.#") fun onAny(event: FactionEvent)`. The wire then carries a concrete subtype the
 * registry has never seen, and a registry-only lookup would drop the event: the documented
 * polymorphic subscription would not work at all.
 *
 * So: registry first (cheap, exact, and the common case), then the classloaders of the
 * registered listeners. Loading is restricted to `SurfBusEvent` subtypes and both outcomes
 * are cached, so a stream of unknown types cannot turn into a stream of failed class lookups.
 */
class ClassLoadingTypeResolver(
    private val registry: EventSubscriptionRegistry
) : EventTypeResolver {

    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val value: Class<out SurfBusEvent>?)

    override fun resolve(typeName: String): Class<out SurfBusEvent>? {
        registry.resolve(typeName)?.let { return it }

        return cache.computeIfAbsent(typeName) { Optional(load(it)) }.value
    }

    private fun load(typeName: String): Class<out SurfBusEvent>? {
        // The listeners' loaders, not this class's: on Paper and Velocity the event types
        // live in the subscribing plugin, which the bus's own loader cannot see.
        val loaders = registry.subscriptions()
            .map { it.listener.javaClass.classLoader }
            .plus(javaClass.classLoader)
            .distinct()

        for (loader in loaders) {
            val candidate = try {
                Class.forName(typeName, false, loader)
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: LinkageError) {
                // A half-loadable class is not usable and not worth retrying.
                continue
            }

            if (!SurfBusEvent::class.java.isAssignableFrom(candidate)) return null

            @Suppress("UNCHECKED_CAST")
            return candidate as Class<out SurfBusEvent>
        }

        return null
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, vierzehn Tests

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(eventbus): resolve concrete event types for base-type subscriptions

A handler bound to a wide pattern with a base-type parameter received nothing,
because only literally declared types were resolvable. The rabbit event path
has the same hole today."
```

---

### Task 6: Capability-Validierung

**Files:**
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/capability/CapabilityValidator.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/capability/CapabilityValidatorTest.kt`

**Interfaces:**
- Consumes: `EventSubscription`, `TransportCapability`, `Provider`,
  `UnsupportedSubscriptionModeException`
- Produces: `class CapabilityValidator(private val provider: Provider, private val capabilities: Set<TransportCapability>)`
  mit `fun validate(subscriptions: List<EventSubscription>): List<String>` — wirft bei
  unmöglichem Modus, gibt Warntexte zurück

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.capability

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.exception.UnsupportedSubscriptionModeException
import dev.slne.surf.eventbus.transport.TransportCapability
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
@BusEvent("player.banned")
class PlayerBannedEvent(val id: String) : SurfBusEvent()

class CapabilityValidatorTest {

    class StatsListener {
        @SurfSubscribe(mode = SubscriptionMode.SHARED)
        fun onBanned(event: PlayerBannedEvent) = Unit
    }

    class TabListener {
        @SurfSubscribe(mode = SubscriptionMode.BROADCAST, retry = true)
        fun onQuit(event: PlayerBannedEvent) = Unit
    }

    class NoRetryListener {
        @SurfSubscribe(mode = SubscriptionMode.BROADCAST, retry = false)
        fun onQuit(event: PlayerBannedEvent) = Unit
    }

    private val redisCapabilities = setOf(TransportCapability.BROADCAST)
    private val rabbitCapabilities = TransportCapability.entries.toSet()

    private fun subscriptionsOf(vararg listeners: Any) = EventSubscriptionRegistry()
        .also { registry -> listeners.forEach(registry::register) }
        .subscriptions()

    @Test
    fun `a SHARED handler on a broadcast-only provider fails the start`() {
        val validator = CapabilityValidator(Provider.REDIS, redisCapabilities)

        val failure = assertFailsWith<UnsupportedSubscriptionModeException> {
            validator.validate(subscriptionsOf(StatsListener()))
        }

        val message = failure.message!!
        assertTrue(message.contains("StatsListener#onBanned"), message)
        assertTrue(message.contains("mode SHARED"), message)
        assertTrue(message.contains("provider REDIS"), message)
        assertTrue(message.contains("durable per-service queue"), message)
        assertTrue(message.contains("use provider RABBIT"), message)
    }

    @Test
    fun `a retrying handler on a provider without retry produces a warning, not a failure`() {
        val validator = CapabilityValidator(Provider.REDIS, redisCapabilities)

        val warnings = validator.validate(subscriptionsOf(TabListener()))

        assertEquals(1, warnings.size)
        val warning = warnings.single()
        assertTrue(warning.contains("REDIS"), warning)
        assertTrue(warning.contains("RETRY"), warning)
        assertTrue(warning.contains("TabListener#onQuit"), warning)
    }

    @Test
    fun `a handler that opted out of retry produces no warning`() {
        val validator = CapabilityValidator(Provider.REDIS, redisCapabilities)

        assertEquals(emptyList(), validator.validate(subscriptionsOf(NoRetryListener())))
    }

    @Test
    fun `a fully capable provider accepts everything silently`() {
        val validator = CapabilityValidator(Provider.RABBIT, rabbitCapabilities)

        val warnings = validator.validate(
            subscriptionsOf(StatsListener(), TabListener(), NoRetryListener())
        )

        assertEquals(emptyList(), warnings)
    }

    @Test
    fun `all affected handlers are named in one warning`() {
        val validator = CapabilityValidator(Provider.REDIS, redisCapabilities)

        val warning = validator.validate(subscriptionsOf(TabListener(), TabListener())).single()

        // Two handlers, one line: a warning per handler would drown the startup log.
        assertEquals(2, Regex("TabListener#onQuit").findAll(warning).count(), warning)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test --tests '*CapabilityValidatorTest*'`
Expected: FAIL — `Unresolved reference: CapabilityValidator`

- [ ] **Step 3: Implementieren**

```kotlin
package dev.slne.surf.eventbus.core.capability

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.core.registry.EventSubscription
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.exception.UnsupportedSubscriptionModeException
import dev.slne.surf.eventbus.transport.TransportCapability

/**
 * Checks every subscription against what the selected transport can actually do.
 *
 * Two reactions, separated by the kind of damage a mismatch does:
 *
 * - **A missing delivery mode is fatal.** The mode decides how *often* a handler runs.
 *   Degrading `SHARED` to `BROADCAST` would multiply every side effect by the instance count;
 *   dropping the event would lose it silently. Neither may happen behind the operator's back.
 * - **Missing retry is a warning.** `retry = true` is the default and would therefore sit on
 *   nearly every handler, so failing would make the provider unusable rather than safer. The
 *   damage is also smaller: not how often a handler runs, only what happens after it failed.
 */
class CapabilityValidator(
    private val provider: Provider,
    private val capabilities: Set<TransportCapability>
) {
    private val supportedModes: Set<SubscriptionMode> = buildSet {
        if (TransportCapability.BROADCAST in capabilities) add(SubscriptionMode.BROADCAST)
        if (TransportCapability.SHARED in capabilities) add(SubscriptionMode.SHARED)
    }

    /**
     * @return one warning line per unsupported-but-tolerable capability, empty if all is well
     * @throws UnsupportedSubscriptionModeException on the first handler whose mode is
     *   impossible
     */
    fun validate(subscriptions: List<EventSubscription>): List<String> {
        for (subscription in subscriptions) {
            if (subscription.mode !in supportedModes) {
                throw UnsupportedSubscriptionModeException(
                    handler = subscription.handlerName,
                    mode = subscription.mode,
                    provider = provider,
                    supported = supportedModes
                )
            }
        }

        val retrying = subscriptions.filter { it.retry }

        if (retrying.isEmpty() || TransportCapability.RETRY in capabilities) return emptyList()

        return listOf(
            buildString {
                append("provider ${provider.name} does not support RETRY")
                if (TransportCapability.DEAD_LETTER !in capabilities) append(" or DEAD_LETTER")
                append(". A failing handler is logged and the event is gone. ")
                append("Affected handlers: ")
                append(retrying.joinToString(", ") { it.handlerName })
            }
        )
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, neunzehn Tests

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(eventbus): validate subscriptions against transport capabilities"
```

---

### Task 7: Dispatch über Hidden-Class-Invoker

**Files:**
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/dispatch/BusEventInvoker.kt`
- Create: `surf-eventbus-core/src/main/java/dev/slne/surf/eventbus/core/dispatch/BusEventInvokerTemplate.java`
- Create: `surf-eventbus-core/src/main/java/dev/slne/surf/eventbus/core/dispatch/BusInvokerLookupProvider.java`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/dispatch/EventDispatcher.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/dispatch/EventDispatcherTest.kt`

**Interfaces:**
- Consumes: `EventSubscriptionRegistry`, `EventSubscription`, `IncomingEvent`
- Produces:
  - `fun interface BusEventInvoker { suspend fun invoke(event: SurfBusEvent) }`
  - `class EventDispatcher(registry: EventSubscriptionRegistry, localInstanceId: String)` mit
    `fun bind(subscription: EventSubscription)` und
    `suspend fun dispatch(incoming: IncomingEvent)`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.exception.EventHandlingFailure
import dev.slne.surf.eventbus.transport.IncomingEvent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@Serializable
@BusEvent("dispatch.thing")
open class ThingEvent(val value: String) : SurfBusEvent()

@Serializable
@BusEvent("dispatch.thing.special")
class SpecialThingEvent(value: String) : ThingEvent(value)

@OptIn(InternalEventBus::class)
class EventDispatcherTest {

    class Counting {
        val plain = AtomicInteger()
        val suspending = AtomicInteger()

        @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
        fun onPlain(event: ThingEvent) {
            plain.incrementAndGet()
        }

        @SurfSubscribe(topic = "dispatch.#", mode = SubscriptionMode.BROADCAST)
        suspend fun onSuspending(event: ThingEvent) {
            suspending.incrementAndGet()
        }
    }

    class Failing {
        val ran = AtomicInteger()

        @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
        fun boom(event: ThingEvent): Unit = throw IllegalStateException("handler exploded")

        @SurfSubscribe(topic = "dispatch.thing", mode = SubscriptionMode.BROADCAST)
        fun neighbour(event: ThingEvent) {
            ran.incrementAndGet()
        }
    }

    class SelfExcluding {
        val count = AtomicInteger()

        @SurfSubscribe(mode = SubscriptionMode.BROADCAST, includeSelf = false)
        fun on(event: ThingEvent) {
            count.incrementAndGet()
        }
    }

    class SelfIncluding {
        val count = AtomicInteger()

        @SurfSubscribe(mode = SubscriptionMode.BROADCAST, includeSelf = true)
        fun on(event: ThingEvent) {
            count.incrementAndGet()
        }
    }

    private fun dispatcher(listener: Any, localInstanceId: String = "me"): EventDispatcher {
        val registry = EventSubscriptionRegistry()
        registry.register(listener)

        val dispatcher = EventDispatcher(registry, localInstanceId)
        registry.subscriptions().forEach(dispatcher::bind)
        return dispatcher
    }

    private fun incoming(
        event: SurfBusEvent,
        topic: String,
        origin: String = "other"
    ) = IncomingEvent(event, topic, origin, 1L)

    @Test
    fun `both a plain and a suspend handler run`() = runTest {
        val listener = Counting()

        dispatcher(listener).dispatch(incoming(ThingEvent("x"), "dispatch.thing"))

        assertEquals(1, listener.plain.get())
        assertEquals(1, listener.suspending.get())
    }

    @Test
    fun `a handler on a supertype receives a subtype`() = runTest {
        val listener = Counting()

        dispatcher(listener).dispatch(
            incoming(SpecialThingEvent("x"), "dispatch.thing.special")
        )

        // Only the wide pattern matches this topic, and it is declared on the supertype.
        assertEquals(0, listener.plain.get())
        assertEquals(1, listener.suspending.get())
    }

    @Test
    fun `a failing handler does not stop its neighbour and the failure surfaces`() = runTest {
        val listener = Failing()

        val failure = assertFailsWith<EventHandlingFailure> {
            dispatcher(listener).dispatch(incoming(ThingEvent("x"), "dispatch.thing"))
        }

        assertEquals("handler exploded", failure.cause.message)
        assertEquals(1, listener.ran.get(), "the unrelated handler must still have run")
    }

    @Test
    fun `the failure reports retry as vetoed when one handler opted out`() = runTest {
        val listener = object {
            @SurfSubscribe(mode = SubscriptionMode.BROADCAST, retry = true)
            fun retrying(event: ThingEvent): Unit = error("boom")

            @SurfSubscribe(
                topic = "dispatch.thing",
                mode = SubscriptionMode.BROADCAST,
                retry = false
            )
            fun notRetrying(event: ThingEvent) = Unit
        }

        val failure = assertFailsWith<EventHandlingFailure> {
            dispatcher(listener).dispatch(incoming(ThingEvent("x"), "dispatch.thing"))
        }

        // Both handlers received one message. Retrying it would re-run the non-idempotent one.
        assertEquals(false, failure.retryable)
    }

    @Test
    fun `an event from this instance is withheld by default`() = runTest {
        val listener = SelfExcluding()

        dispatcher(listener, localInstanceId = "me")
            .dispatch(incoming(ThingEvent("x"), "dispatch.thing", origin = "me"))

        assertEquals(0, listener.count.get())
    }

    @Test
    fun `an event from another instance is delivered`() = runTest {
        val listener = SelfExcluding()

        dispatcher(listener, localInstanceId = "me")
            .dispatch(incoming(ThingEvent("x"), "dispatch.thing", origin = "other"))

        assertEquals(1, listener.count.get())
    }

    @Test
    fun `includeSelf delivers this instance's own event`() = runTest {
        val listener = SelfIncluding()

        dispatcher(listener, localInstanceId = "me")
            .dispatch(incoming(ThingEvent("x"), "dispatch.thing", origin = "me"))

        assertEquals(1, listener.count.get())
    }

    @Test
    fun `metadata is applied to the event before handlers see it`() = runTest {
        var seenOrigin: String? = null

        val listener = object {
            @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
            fun on(event: ThingEvent) {
                seenOrigin = event.originInstanceId
            }
        }

        dispatcher(listener).dispatch(
            incoming(ThingEvent("x"), "dispatch.thing", origin = "publisher-7")
        )

        assertEquals("publisher-7", seenOrigin)
    }

    @Test
    fun `an event nobody matches is not an error`() = runTest {
        dispatcher(Counting()).dispatch(incoming(ThingEvent("x"), "unrelated.topic"))
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test --tests '*EventDispatcherTest*'`
Expected: FAIL — `Unresolved reference: EventDispatcher`

- [ ] **Step 3: Invoker-Trio anlegen**

`BusEventInvoker.kt`:

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.event.SurfBusEvent

/**
 * Invokes one registered handler method.
 *
 * Implementations are generated at runtime as JVM hidden classes, which wrap the handler's
 * `MethodHandle` as a `static final` constant so the JIT can inline and constant-fold the
 * dispatch target. Both regular and `suspend` handler methods are supported.
 */
fun interface BusEventInvoker {
    suspend fun invoke(event: SurfBusEvent)
}
```

`BusInvokerLookupProvider.java`:

```java
package dev.slne.surf.eventbus.core.dispatch;

import java.lang.invoke.MethodHandles;

public final class BusInvokerLookupProvider {

    public static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private BusInvokerLookupProvider() {
        throw new AssertionError("No instances");
    }
}
```

`BusEventInvokerTemplate.java`:

```java
package dev.slne.surf.eventbus.core.dispatch;

import dev.slne.surf.api.core.invoker.HiddenInvokerUtil;
import dev.slne.surf.api.core.invoker.InvokerClassData;
import dev.slne.surf.eventbus.event.SurfBusEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hidden class template for event handler invocation.
 *
 * The instance check is what makes a subscription on a supertype safe: an invoker bound to
 * `FactionEvent` accepts every subtype and skips anything else.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BusEventInvokerTemplate implements BusEventInvoker {

    private static final Method METHOD;
    private static final MethodHandle HANDLE;
    private static final Class<?> EVENT_CLASS;
    private static final boolean IS_SUSPEND;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final InvokerClassData classData = HiddenInvokerUtil.loadClassDataWithAutoSuspend(
                lookup, MethodType.methodType(void.class, SurfBusEvent.class));

            METHOD = classData.method();
            HANDLE = classData.methodHandle();
            EVENT_CLASS = classData.payloadClass();
            IS_SUSPEND = classData.isSuspend();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to initialize BusEventInvokerTemplate", e);
        }
    }

    @Override
    public @Nullable Object invoke(@NotNull SurfBusEvent event,
        @NotNull Continuation<? super @NotNull Unit> $completion) {
        if (!EVENT_CLASS.isInstance(event)) {
            return Unit.INSTANCE;
        }

        if (IS_SUSPEND) {
            try {
                return HANDLE.invoke(event, $completion);
            } catch (Throwable t) {
                HiddenInvokerUtil.sneakyThrow(t);
            }
        } else {
            try {
                HANDLE.invokeExact(event);
            } catch (Throwable t) {
                HiddenInvokerUtil.sneakyThrow(t);
            }
            return Unit.INSTANCE;
        }

        return null;
    }

    @Override
    public String toString() {
        return "BusEventInvokerTemplate{" + METHOD + "}";
    }
}
```

- [ ] **Step 4: EventDispatcher implementieren**

```kotlin
@file:Suppress("InternalApiUsage", "UnstableApiUsage")

package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.api.core.invoker.InvokerFactory
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.api.shared.api.util.InternalInvokerApi
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.core.registry.EventSubscription
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.exception.IllegalSubscriptionException
import dev.slne.surf.eventbus.transport.IncomingEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs the handlers matching a delivered event.
 *
 * Every matching subscription runs, including overlapping patterns: a handler bound to
 * `faction.disbanded` and one bound to `faction.#` both fire for the same event.
 *
 * One broken handler must not stop its unrelated neighbours, so all of them are attempted and
 * the first failure is rethrown afterwards — the transport needs to see it to decide about
 * retry.
 */
@OptIn(InternalInvokerApi::class, InternalEventBus::class)
class EventDispatcher(
    private val registry: EventSubscriptionRegistry,
    private val localInstanceId: String
) {
    private val invokers = ConcurrentHashMap<EventSubscription, BusEventInvoker>()

    companion object {
        private val log = logger()

        private val INVOKER_FACTORY = InvokerFactory(
            /* templateClass = */ BusEventInvokerTemplate::class.java,
            /* invokerInterface = */ BusEventInvoker::class.java,
            /* lookup = */ BusInvokerLookupProvider.LOOKUP
        )
    }

    /**
     * Prepares [subscription] for dispatch.
     *
     * Called once per subscription before the transport starts. Failing here rather than on
     * first delivery keeps an inaccessible handler from becoming a runtime mystery.
     */
    fun bind(subscription: EventSubscription) {
        if (!INVOKER_FACTORY.canAccess(subscription.listener, subscription.method)) {
            throw IllegalSubscriptionException(
                "@SurfSubscribe method ${subscription.handlerName} is not accessible via " +
                        "privateLookupIn - open the package " +
                        "'${subscription.listener.javaClass.packageName}' to the surf-eventbus " +
                        "module, or make the listener class public."
            )
        }

        invokers[subscription] = INVOKER_FACTORY.create(
            subscription.listener,
            subscription.method,
            subscription.eventClass
        )
    }

    /**
     * Runs every subscription matching [incoming].
     *
     * @throws Throwable the first handler failure, after all handlers have been attempted
     */
    suspend fun dispatch(incoming: IncomingEvent) {
        val event = incoming.event
        event.applyMetadata(incoming.originInstanceId, incoming.publishedAtEpochMs)

        val matching = registry.subscriptionsFor(event.javaClass, incoming.topic)
            .filter { it.includeSelf || incoming.originInstanceId != localInstanceId }

        if (matching.isEmpty()) return

        var firstFailure: Throwable? = null

        for (subscription in matching) {
            val invoker = invokers[subscription] ?: continue

            try {
                invoker.invoke(event)
            } catch (cause: Throwable) {
                log.atSevere()
                    .withCause(cause)
                    .log(
                        "Handler %s failed for event %s on topic %s",
                        subscription.handlerName,
                        event.javaClass.name,
                        incoming.topic
                    )

                if (firstFailure == null) firstFailure = cause
            }
        }

        firstFailure?.let { cause ->
            // The transport decides about redelivery but does not know the handlers' retry
            // flags. One non-idempotent subscription among the matches vetoes retry for all
            // of them: they arrive as one message.
            throw EventHandlingFailure(
                retryable = matching.all { it.retry },
                cause = cause
            )
        }
    }
}
```

Falls `kotlinx.coroutines.CancellationException` in einem Handler auftritt, muss sie
weitergeworfen werden statt geloggt. Ergänze im `catch`-Block als erste Zeile:

```kotlin
                if (cause is kotlinx.coroutines.CancellationException) throw cause
```

Der Import `dev.slne.surf.eventbus.exception.EventHandlingFailure` kommt hinzu.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, achtundzwanzig Tests

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(eventbus): dispatch events through hidden-class invokers"
```

---

### Task 8: Provider-Auswahl

**Files:**
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/transport/TransportSelector.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/transport/TransportSelectorTest.kt`

**Interfaces:**
- Consumes: `Provider`, `EventTransportFactory`, `NoTransportAvailableException`,
  `AmbiguousTransportException`
- Produces: `object TransportSelector` mit
  `fun select(available: List<EventTransportFactory>, requested: Provider?, configured: String?): EventTransportFactory`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.transport

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.exception.AmbiguousTransportException
import dev.slne.surf.eventbus.exception.NoTransportAvailableException
import dev.slne.surf.eventbus.exception.SurfEventBusException
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.EventTransportFactory
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(InternalEventBus::class)
class TransportSelectorTest {

    private class StubFactory(override val provider: Provider) : EventTransportFactory {
        override fun create(context: EventTransportContext): EventTransport =
            throw UnsupportedOperationException("not needed for selection")
    }

    private val rabbit = StubFactory(Provider.RABBIT)
    private val redis = StubFactory(Provider.REDIS)

    @Test
    fun `a single transport on the classpath needs no configuration`() {
        assertEquals(
            rabbit,
            TransportSelector.select(listOf(rabbit), requested = null, configured = null)
        )
    }

    @Test
    fun `an explicit request wins over the configuration`() {
        assertEquals(
            redis,
            TransportSelector.select(
                listOf(rabbit, redis),
                requested = Provider.REDIS,
                configured = "rabbit"
            )
        )
    }

    @Test
    fun `the configuration decides when nothing was requested`() {
        assertEquals(
            redis,
            TransportSelector.select(
                listOf(rabbit, redis),
                requested = null,
                configured = "redis"
            )
        )
    }

    @Test
    fun `the configured id is case insensitive`() {
        assertEquals(
            rabbit,
            TransportSelector.select(listOf(rabbit, redis), null, configured = "RABBIT")
        )
    }

    @Test
    fun `no transport at all is a clear failure`() {
        val failure = assertFailsWith<NoTransportAvailableException> {
            TransportSelector.select(emptyList(), null, null)
        }

        assertTrue(failure.message!!.contains("surf-rabbitmq-core"), failure.message!!)
    }

    @Test
    fun `two transports and no configuration is refused rather than guessed`() {
        val failure = assertFailsWith<AmbiguousTransportException> {
            TransportSelector.select(listOf(rabbit, redis), null, null)
        }

        val message = failure.message!!
        assertTrue(message.contains("rabbit"), message)
        assertTrue(message.contains("redis"), message)
        assertTrue(message.contains("eventbus.provider"), message)
    }

    @Test
    fun `a requested provider that is not on the classpath fails naming both sides`() {
        val failure = assertFailsWith<SurfEventBusException> {
            TransportSelector.select(listOf(rabbit), requested = Provider.REDIS, configured = null)
        }

        val message = failure.message!!
        assertTrue(message.contains("REDIS"), message)
        assertTrue(message.contains("rabbit"), message)
    }

    @Test
    fun `an unknown configured id fails naming the valid ones`() {
        val failure = assertFailsWith<SurfEventBusException> {
            TransportSelector.select(listOf(rabbit, redis), null, configured = "kafka")
        }

        val message = failure.message!!
        assertTrue(message.contains("kafka"), message)
        assertTrue(message.contains("rabbit"), message)
        assertTrue(message.contains("redis"), message)
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test --tests '*TransportSelectorTest*'`
Expected: FAIL — `Unresolved reference: TransportSelector`

- [ ] **Step 3: Implementieren**

```kotlin
package dev.slne.surf.eventbus.core.transport

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.exception.AmbiguousTransportException
import dev.slne.surf.eventbus.exception.NoTransportAvailableException
import dev.slne.surf.eventbus.exception.SurfEventBusException
import dev.slne.surf.eventbus.transport.EventTransportFactory

/**
 * Picks the transport a process runs on.
 *
 * Precedence is explicit request, then configuration, then the single transport on the
 * classpath. With several available and nothing said, the selection **fails**: the choice
 * decides the delivery guarantee, and guessing it would mean guessing whether side effects
 * happen once or once per instance.
 */
@OptIn(InternalEventBus::class)
object TransportSelector {

    fun select(
        available: List<EventTransportFactory>,
        requested: Provider?,
        configured: String?
    ): EventTransportFactory {
        if (available.isEmpty()) throw NoTransportAvailableException()

        val byProvider = available.associateBy { it.provider }

        if (requested != null) {
            return byProvider[requested] ?: throw SurfEventBusException(
                "Provider ${requested.name} was requested, but no matching transport is on " +
                        "the classpath. Available: " +
                        byProvider.keys.joinToString(", ") { it.id } +
                        ". Add the ${requested.moduleName} module."
            )
        }

        if (!configured.isNullOrBlank()) {
            val provider = Provider.byIdOrNull(configured) ?: throw SurfEventBusException(
                "eventbus.provider is '$configured', which is not a known provider. " +
                        "Valid values: " + Provider.entries.joinToString(", ") { it.id }
            )

            return byProvider[provider] ?: throw SurfEventBusException(
                "eventbus.provider is '$configured', but no matching transport is on the " +
                        "classpath. Available: " +
                        byProvider.keys.joinToString(", ") { it.id }
            )
        }

        return available.singleOrNull() ?: throw AmbiguousTransportException(byProvider.keys)
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, sechsunddreißig Tests

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(eventbus): select the transport from request, config or classpath"
```

---

### Task 9: SurfEventBus, Builder und ein In-Memory-Transport als Beweis

Die letzte Task dieses Plans setzt alles zusammen und beweist am In-Memory-Transport, dass
Registrierung, Capability-Prüfung, Lebenszyklus, Publish und Dispatch zusammenspielen — ohne
Broker, in Millisekunden.

**Files:**
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/SurfEventBus.kt`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/SurfEventBusFactory.kt`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusImpl.kt`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusBuilderImpl.kt`
- Create: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusFactoryImpl.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/testing/InMemoryTransport.kt`
- Create: `surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/SurfEventBusImplTest.kt`
- Modify: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/SurfEventBus.kt` (Builder-Interface)

**Interfaces:**
- Consumes: alles aus Tasks 1–8
- Produces:
  - `interface SurfEventBus` mit `provider`, `instanceId`, `registerListener(Any)`,
    `freeze()`, `isFrozen()`, `suspend connect()`, `suspend freezeAndConnect()`,
    `suspend disconnect()`, `suspend publish(SurfBusEvent)`, `<T : Any> transport(Class<T>): T`,
    `companion object { fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder }`
  - `inline fun <reified T : Any> SurfEventBus.transport(): T`
  - `interface SurfEventBusBuilder` mit `provider(Provider)`, `instanceName(String)`,
    `serializers(SerializersModule)`, `configuredProvider(String?)`,
    `transportFactories(List<EventTransportFactory>)`, `build(): SurfEventBus`

- [ ] **Step 1: Öffentliche Schnittstelle anlegen**

`surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/SurfEventBus.kt`:

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.exception.WrongTransportTypeException
import dev.slne.surf.eventbus.transport.EventTransportFactory
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

/**
 * The entry point for distributed events.
 *
 * One bus per process. It owns the transport's lifecycle — a single [freeze] and [connect] —
 * so that a process has one connection and one moment at which its subscriptions become
 * final.
 *
 * ```kotlin
 * val bus = SurfEventBus.builder("surf-factions", dataPath)
 *     .provider(Provider.RABBIT)
 *     .build()
 *
 * bus.registerListener(FactionCacheListener)
 * bus.freezeAndConnect()
 *
 * bus.publish(FactionDisbandedEvent(id))
 * ```
 *
 * Which provider delivers is a configuration decision, not a code decision. What differs
 * between providers is not hidden: a subscription a provider cannot serve fails the start
 * instead of quietly changing meaning.
 */
interface SurfEventBus {

    val provider: Provider

    /** This process's id, as the transport sees it. Stamped onto every published event. */
    val instanceId: String

    /**
     * Registers `@SurfSubscribe` methods on [listener].
     *
     * @throws dev.slne.surf.eventbus.exception.IllegalSubscriptionException on a malformed
     *   handler
     * @throws IllegalStateException if the bus is already frozen
     */
    fun registerListener(listener: Any)

    /**
     * Locks registration and validates every subscription against the transport.
     *
     * Handlers must be known before the transport subscribes, otherwise an event could arrive
     * for a handler that is still being registered — or a binding could be missing for one
     * that already is.
     *
     * @throws dev.slne.surf.eventbus.exception.UnsupportedSubscriptionModeException if a
     *   handler asks for a delivery mode this provider cannot offer
     */
    fun freeze()

    fun isFrozen(): Boolean

    suspend fun connect()

    suspend fun freezeAndConnect()

    suspend fun disconnect()

    /**
     * Publishes [event] to every matching subscriber.
     *
     * The publisher does not know who listens, and an event with no subscriber is discarded
     * without error. That is the point: adding or removing a subscriber never touches the
     * publisher.
     */
    suspend fun publish(event: SurfBusEvent)

    /**
     * The transport's own API — RPC on RabbitMQ, sync structures and caches on Redis.
     *
     * Deliberately explicit and typed: using it leaves the portable subset behind, and that
     * should be visible at the call site.
     *
     * @throws WrongTransportTypeException if the active provider does not expose [type]
     */
    fun <T : Any> transport(type: Class<T>): T

    companion object {
        fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder =
            SurfEventBusFactory.INSTANCE.builder(serviceName, dataPath)
    }
}

/** @see SurfEventBus.transport */
inline fun <reified T : Any> SurfEventBus.transport(): T = transport(T::class.java)

/** Builds a [SurfEventBus]. */
interface SurfEventBusBuilder {

    /** Forces a provider, overriding configuration. Prefer configuring it. */
    fun provider(provider: Provider): SurfEventBusBuilder

    /**
     * Gives this process a stable instance id instead of a generated one.
     *
     * Required wherever another process must be able to address this one by name.
     */
    fun instanceName(name: String): SurfEventBusBuilder

    /** Additional serializers for event payload types. */
    fun serializers(module: SerializersModule): SurfEventBusBuilder

    /**
     * The provider id from configuration, or `null`.
     *
     * Supplied by the platform layer, which owns config resolution. Tests pass it directly.
     */
    @InternalEventBus
    fun configuredProvider(id: String?): SurfEventBusBuilder

    /**
     * Overrides `ServiceLoader` discovery of transports. Intended for tests.
     */
    @InternalEventBus
    fun transportFactories(factories: List<EventTransportFactory>): SurfEventBusBuilder

    fun build(): SurfEventBus
}
```

`surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/SurfEventBusFactory.kt`:

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.api.core.util.requiredService
import java.nio.file.Path

/**
 * Bridges the API to the implementation in surf-eventbus-core, discovered via `ServiceLoader`.
 *
 * Mirrors how the rest of this codebase separates api from core
 * (`RabbitMQConnectionFactory`, `RedisComponentProvider`).
 */
@InternalEventBus
interface SurfEventBusFactory {
    fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder

    @InternalEventBus
    companion object {
        val INSTANCE: SurfEventBusFactory get() = instance
    }
}

private val instance = requiredService<SurfEventBusFactory>()
```

- [ ] **Step 2: In-Memory-Transport für Tests schreiben**

`surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/testing/InMemoryTransport.kt`:

```kotlin
package dev.slne.surf.eventbus.core.testing

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.transport.EventSink
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.EventTransportFactory
import dev.slne.surf.eventbus.transport.IncomingEvent
import dev.slne.surf.eventbus.transport.OutgoingEvent
import dev.slne.surf.eventbus.transport.TopicBinding
import dev.slne.surf.eventbus.transport.TransportCapability

/** The transport-specific API an [InMemoryTransport] exposes, standing in for a real one. */
class InMemoryTransportApi(val instanceId: String)

/**
 * A transport that delivers to itself, in process, without encoding anything.
 *
 * Lets the bus's own logic — registration, capability checks, lifecycle, matching, dispatch —
 * be tested exhaustively and instantly. Everything a real transport adds (a wire format,
 * durability, retry) is verified against real brokers in the parity suite instead.
 */
@OptIn(InternalEventBus::class)
class InMemoryTransport(
    override val provider: Provider,
    override val capabilities: Set<TransportCapability>,
    override val instanceId: String
) : EventTransport {

    override val transportApi: Any = InMemoryTransportApi(instanceId)

    var startedBindings: Set<TopicBinding>? = null
        private set

    var stopped = false
        private set

    val published = mutableListOf<OutgoingEvent>()

    private var sink: EventSink? = null

    override suspend fun start(bindings: Set<TopicBinding>, sink: EventSink) {
        startedBindings = bindings
        this.sink = sink
    }

    override suspend fun publish(outgoing: OutgoingEvent) {
        published += outgoing

        // A real transport round-trips through a broker; this one hands the event straight
        // back, which is exactly the delivery the bus has to handle correctly.
        sink?.accept(
            IncomingEvent(
                event = outgoing.event,
                topic = outgoing.topic,
                originInstanceId = outgoing.originInstanceId,
                publishedAtEpochMs = outgoing.publishedAtEpochMs
            )
        )
    }

    override suspend fun stop() {
        stopped = true
    }

    class Factory(
        override val provider: Provider,
        private val capabilities: Set<TransportCapability>,
        private val instanceId: String = "in-memory-1"
    ) : EventTransportFactory {
        lateinit var created: InMemoryTransport
            private set

        override fun create(context: EventTransportContext): EventTransport =
            InMemoryTransport(provider, capabilities, instanceId).also { created = it }
    }
}
```

- [ ] **Step 3: Failing test für den Bus schreiben**

`surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/core/SurfEventBusImplTest.kt`:

```kotlin
package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.core.testing.InMemoryTransport
import dev.slne.surf.eventbus.core.testing.InMemoryTransportApi
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import dev.slne.surf.eventbus.exception.UnsupportedSubscriptionModeException
import dev.slne.surf.eventbus.exception.WrongTransportTypeException
import dev.slne.surf.eventbus.transport.TopicBinding
import dev.slne.surf.eventbus.transport.TransportCapability
import dev.slne.surf.eventbus.transport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
@BusEvent("bus.thing.happened")
class BusThingHappenedEvent(val value: String) : SurfBusEvent()

@OptIn(InternalEventBus::class)
class SurfEventBusImplTest {

    private val dataPath = Files.createTempDirectory("eventbus-test")

    class Listener {
        val count = AtomicInteger()

        @SurfSubscribe(mode = SubscriptionMode.BROADCAST, includeSelf = true)
        suspend fun on(event: BusThingHappenedEvent) {
            count.incrementAndGet()
        }
    }

    class SharedListener {
        @SurfSubscribe(mode = SubscriptionMode.SHARED)
        fun on(event: BusThingHappenedEvent) = Unit
    }

    private fun bus(
        factory: InMemoryTransport.Factory,
        configured: String? = null
    ): SurfEventBus = SurfEventBus.builder("surf-test", dataPath)
        .transportFactories(listOf(factory))
        .configuredProvider(configured)
        .build()

    private fun broadcastOnly() = InMemoryTransport.Factory(
        Provider.REDIS,
        setOf(TransportCapability.BROADCAST)
    )

    private fun fullyCapable() = InMemoryTransport.Factory(
        Provider.RABBIT,
        TransportCapability.entries.toSet()
    )

    @Test
    fun `a published event reaches a registered handler`() = runTest {
        val listener = Listener()
        val factory = fullyCapable()
        val bus = bus(factory)

        bus.registerListener(listener)
        bus.freezeAndConnect()
        bus.publish(BusThingHappenedEvent("x"))

        assertEquals(1, listener.count.get())
        assertEquals(1, factory.created.published.size)
        assertEquals("bus.thing.happened", factory.created.published.single().topic)
        assertEquals(
            BusThingHappenedEvent::class.java.name,
            factory.created.published.single().typeName
        )

        bus.disconnect()
        assertTrue(factory.created.stopped)
    }

    @Test
    fun `connect passes every binding to the transport`() = runTest {
        val factory = fullyCapable()
        val bus = bus(factory)

        bus.registerListener(Listener())
        bus.freezeAndConnect()

        assertEquals(
            setOf(TopicBinding("bus.thing.happened", SubscriptionMode.BROADCAST)),
            factory.created.startedBindings
        )

        bus.disconnect()
    }

    @Test
    fun `freeze rejects a mode the transport cannot serve`() {
        val bus = bus(broadcastOnly())
        bus.registerListener(SharedListener())

        val failure = assertFailsWith<UnsupportedSubscriptionModeException> { bus.freeze() }

        assertTrue(failure.message!!.contains("SharedListener#on"), failure.message!!)
        assertTrue(failure.message!!.contains("provider REDIS"), failure.message!!)
    }

    @Test
    fun `registration after freeze is refused`() {
        val bus = bus(fullyCapable())
        bus.freeze()

        assertFailsWith<IllegalStateException> { bus.registerListener(Listener()) }
    }

    @Test
    fun `connect before freeze is refused`() = runTest {
        val bus = bus(fullyCapable())

        assertFailsWith<IllegalStateException> { bus.connect() }
    }

    @Test
    fun `freezing twice is refused`() {
        val bus = bus(fullyCapable())
        bus.freeze()

        assertFailsWith<IllegalStateException> { bus.freeze() }
    }

    @Test
    fun `the transport api is reachable by type`() {
        val bus = bus(fullyCapable())

        assertEquals("in-memory-1", bus.transport<InMemoryTransportApi>().instanceId)
    }

    @Test
    fun `asking for the wrong transport api fails naming both types`() {
        val bus = bus(fullyCapable())

        val failure = assertFailsWith<WrongTransportTypeException> { bus.transport<String>() }

        assertTrue(failure.message!!.contains("String"), failure.message!!)
        assertTrue(failure.message!!.contains("InMemoryTransportApi"), failure.message!!)
    }

    @Test
    fun `the bus exposes the transport's instance id`() {
        assertEquals("in-memory-1", bus(fullyCapable()).instanceId)
    }

    @Test
    fun `the configured provider selects the transport`() {
        assertEquals(Provider.REDIS, bus(broadcastOnly(), configured = "redis").provider)
    }
}
```

- [ ] **Step 4: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-core:test --tests '*SurfEventBusImplTest*'`
Expected: FAIL — `requiredService<SurfEventBusFactory>` findet keine Implementierung

- [ ] **Step 5: Bus implementieren**

`surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusImpl.kt`:

```kotlin
package dev.slne.surf.eventbus.core

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.core.capability.CapabilityValidator
import dev.slne.surf.eventbus.core.dispatch.EventDispatcher
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.exception.WrongTransportTypeException
import dev.slne.surf.eventbus.topic.EventTopics
import dev.slne.surf.eventbus.transport.EventSink
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.OutgoingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

@OptIn(InternalEventBus::class)
class SurfEventBusImpl(
    private val transport: EventTransport,
    private val registry: EventSubscriptionRegistry,
    private val scope: CoroutineScope
) : SurfEventBus {

    companion object {
        private val log = logger()
    }

    override val provider: Provider get() = transport.provider
    override val instanceId: String get() = transport.instanceId

    private val dispatcher = EventDispatcher(registry, transport.instanceId)
    private var frozen = false

    override fun registerListener(listener: Any) {
        check(!frozen) {
            "Cannot register a listener after the event bus has been frozen. Register every " +
                    "listener before freeze(), so that no event can arrive for a handler " +
                    "that is still being registered."
        }

        registry.register(listener)
    }

    override fun freeze() {
        check(!frozen) { "The event bus is already frozen." }

        val warnings = CapabilityValidator(transport.provider, transport.capabilities)
            .validate(registry.subscriptions())

        warnings.forEach { warning -> log.atWarning().log("%s", warning) }

        registry.subscriptions().forEach(dispatcher::bind)

        frozen = true
    }

    override fun isFrozen(): Boolean = frozen

    override suspend fun connect() {
        check(frozen) {
            "The event bus must be frozen before connecting, so that the transport knows " +
                    "every binding it has to establish."
        }

        transport.start(registry.bindings(), EventSink { dispatcher.dispatch(it) })
    }

    override suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    override suspend fun disconnect() {
        transport.stop()
        scope.cancel("SurfEventBus disconnected")
    }

    override suspend fun publish(event: SurfBusEvent) {
        val topic = EventTopics.topicOf(event.javaClass)
        val publishedAt = System.currentTimeMillis()

        event.applyMetadata(transport.instanceId, publishedAt)

        transport.publish(
            OutgoingEvent(
                event = event,
                topic = topic,
                typeName = event.javaClass.name,
                originInstanceId = transport.instanceId,
                publishedAtEpochMs = publishedAt
            )
        )
    }

    override fun <T : Any> transport(type: Class<T>): T {
        val api = transport.transportApi

        if (!type.isInstance(api)) {
            throw WrongTransportTypeException(
                requested = type.simpleName,
                actual = api.javaClass.simpleName,
                provider = transport.provider
            )
        }

        @Suppress("UNCHECKED_CAST")
        return api as T
    }
}
```

`surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusBuilderImpl.kt`:

```kotlin
package dev.slne.surf.eventbus.core

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.SurfEventBusBuilder
import dev.slne.surf.eventbus.core.registry.ClassLoadingTypeResolver
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.core.transport.TransportSelector
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.EventTransportFactory
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.util.ServiceLoader

@OptIn(InternalEventBus::class)
class SurfEventBusBuilderImpl(
    private val serviceName: String,
    private val dataPath: Path
) : SurfEventBusBuilder {

    companion object {
        private val log = logger()
    }

    private var provider: Provider? = null
    private var instanceName: String? = null
    private var serializers: SerializersModule = EmptySerializersModule()
    private var configuredProvider: String? = null
    private var factories: List<EventTransportFactory>? = null

    override fun provider(provider: Provider): SurfEventBusBuilder =
        apply { this.provider = provider }

    override fun instanceName(name: String): SurfEventBusBuilder = apply {
        require(name.isNotBlank()) { "instanceName must not be blank" }
        instanceName = name
    }

    override fun serializers(module: SerializersModule): SurfEventBusBuilder =
        apply { serializers = module }

    override fun configuredProvider(id: String?): SurfEventBusBuilder =
        apply { configuredProvider = id }

    override fun transportFactories(
        factories: List<EventTransportFactory>
    ): SurfEventBusBuilder = apply { this.factories = factories }

    override fun build(): SurfEventBus {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val available = factories
            ?: ServiceLoader.load(EventTransportFactory::class.java).toList()

        val factory = TransportSelector.select(available, provider, configuredProvider)

        if (provider != null && configuredProvider != null &&
            !configuredProvider.equals(provider!!.id, ignoreCase = true)
        ) {
            log.atWarning().log(
                "Provider %s was requested in code while the configuration says '%s'. " +
                        "The code wins. Two providers in one fleet are two disjoint event " +
                        "universes, so this is worth a second look.",
                provider!!.id,
                configuredProvider
            )
        }

        val registry = EventSubscriptionRegistry()

        val scope = CoroutineScope(
            Dispatchers.Default +
                    CoroutineName("SurfEventBus-$serviceName") +
                    SupervisorJob() +
                    CoroutineExceptionHandler { context, throwable ->
                        log.atSevere()
                            .withCause(throwable)
                            .log(
                                "Unhandled exception in SurfEventBus coroutine %s",
                                context[CoroutineName]
                            )
                    }
        )

        val transport = factory.create(
            EventTransportContext(
                serviceName = serviceName,
                instanceName = instanceName,
                dataPath = dataPath,
                serializers = serializers,
                typeResolver = ClassLoadingTypeResolver(registry),
                scope = scope
            )
        )

        return SurfEventBusImpl(transport, registry, scope)
    }
}
```

`surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusFactoryImpl.kt`:

```kotlin
package dev.slne.surf.eventbus.core

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.SurfEventBusBuilder
import dev.slne.surf.eventbus.SurfEventBusFactory
import java.nio.file.Path

@OptIn(InternalEventBus::class)
@AutoService(SurfEventBusFactory::class)
class SurfEventBusFactoryImpl : SurfEventBusFactory {
    override fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder =
        SurfEventBusBuilderImpl(serviceName, dataPath)
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test`
Expected: PASS, sechsundvierzig Tests

Sollte `requiredService<SurfEventBusFactory>` fehlschlagen, weil der AutoService-Prozessor im
Testlauf nicht greift: prüfen, dass `surf-eventbus-core/build/classes/.../META-INF/services/`
den Eintrag enthält. Ist der AutoService-Prozessor in der Konvention
`dev.slne.surf.api.gradle.core` nicht aktiv, wird stattdessen die Datei
`surf-eventbus-core/src/main/resources/META-INF/services/dev.slne.surf.eventbus.SurfEventBusFactory`
mit dem Inhalt `dev.slne.surf.eventbus.core.SurfEventBusFactoryImpl` angelegt und die
`@AutoService`-Annotation entfernt.

- [ ] **Step 7: Gesamtbuild ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(eventbus): assemble the bus and prove it against an in-memory transport"
```

---

## Definition of Done

- `surf-eventbus-api` und `surf-eventbus-core` existieren, `./gradlew build -PskipIntegration`
  grün
- 46 Tests in `surf-eventbus-core` und 5 in `surf-eventbus-api`, alle ohne Docker, kein `@RequiresDocker` in diesem Plan
- `TransportNeutralityTest` verhindert jeden Transport-Import in der Bus-API
- Registrierungsfehler, unmögliche Modi und Provider-Konflikte scheitern beim Start mit
  Meldungen, die Handler, Modus und Provider namentlich nennen — wortgleich getestet
- Ein Handler auf einem Basistyp mit weitem Muster erhält konkrete Subtypen (Lücke, die der
  heutige Rabbit-Pfad offen hat)
- Der Rabbit-Event-Pfad läuft unverändert weiter; er wird in Plan 3 umgezogen
