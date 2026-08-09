# surf-eventbus 2: Bus-API und Bus-Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** use
> `superpowers:subagent-driven-development` — this repository's owner has forbidden subagents.

**Goal:** Die Bus-Fläche und ihr Innenleben bauen — `SurfEventBus`, `SurfBusEvent`, `@BusEvent`,
`@SurfSubscribe`, `@QueryService`, `EventTopics`, Registry, Dispatcher, Envelope, Lebenszyklus —
vollständig ohne Broker, abgesichert über Test-Doubles hinter zwei Nähten.

**Architecture:** Zwei Module. `surf-eventbus-bus-api` enthält nur Typen, die Anwendungscode
anfasst, plus die zwei Transport-Nähte `EventTransport` und `QueryTransport`.
`surf-eventbus-bus-core` enthält Registry, Dispatcher, Envelope und den Lebenszyklus und wird
gegen Fake-Transports getestet. Kein Redisson, kein amqp-client in beiden Modulen — der
Purity-Test aus Plan 1 wird darauf erweitert.

**Tech Stack:** Kotlin/JVM, `kotlinx.serialization` (JSON) für den Envelope, Netty `ByteBuf` für
`BusEventCodec`, surf-api-core `InvokerFactory` für Hidden-Class-Dispatch, JUnit 5.

## Global Constraints

- **Voraussetzung:** Plan 1 ist abgeschlossen; `docs/superpowers/notes/2026-07-31-fundament-verification.md`
  existiert und der Build ist grün.
- **Gruppe** `dev.slne.surf.eventbus`, **Version** `2.0.0`, Packages unter
  `dev.slne.surf.eventbus.*`. `PackageNamingTest` bleibt grün.
- **`surf-eventbus-bus-api` und `-bus-core` sind brokerfrei.** Kein `com.rabbitmq`, kein
  `org.redisson`. Erweiterung von `CommonPurityTest`.
- **Events sind Broadcast.** Es gibt kein `SubscriptionMode`, kein `retry` an einem Abonnement,
  kein Ziel an `publish`.
- **`@SurfSubscribe` hat genau zwei Parameter:** `topic` und `includeSelf`. `includeSelf`
  ist standardmäßig `false`.
- **Topic-Semantik ist AMQP-Semantik:** `*` trifft genau ein Segment, `#` null oder mehr.
  Punktgetrennt, Publish-Topics wildcardfrei.
- **Validiert wird bei der Registrierung**, nicht bei der Zustellung.
- **Kein fremdes Repository wird verändert.**

---

## File Structure

| Datei | Verantwortung |
|---|---|
| `surf-eventbus-bus/surf-eventbus-bus-api/build.gradle.kts` | Modul, hängt nur an `surf-eventbus-common` |
| `…-bus-api/src/main/kotlin/dev/slne/surf/eventbus/event/SurfBusEvent.kt` | Basistyp, Herkunft, Zeitstempel |
| `…-bus-api/…/event/BusEvent.kt` | `@BusEvent(topic)` |
| `…-bus-api/…/event/SurfSubscribe.kt` | `@SurfSubscribe(topic, includeSelf)` |
| `…-bus-api/…/event/BusEventCodec.kt` | Opt-in-Binärkodierung eines Event-Typs |
| `…-bus-api/…/event/EventTopics.kt` | Muster-Validierung und Matching (aus rabbitmq-core) |
| `…-bus-api/…/query/QueryService.kt` | `@QueryService(timeoutMillis)` |
| `…-bus-api/…/audit/AuditReport.kt`, `AuditKind.kt`, `AuditSink.kt` | Audit-Datentyp und Naht |
| `…-bus-api/…/transport/EventTransport.kt`, `QueryTransport.kt` | die zwei Nähte |
| `…-bus-api/…/SurfEventBus.kt`, `SurfEventBusBuilder.kt` | die öffentliche Fläche |
| `surf-eventbus-bus/surf-eventbus-bus-core/…/registry/EventSubscription.kt`, `EventSubscriptionRegistry.kt` | Abonnements und ihre Validierung |
| `…-bus-core/…/registry/QueryServiceRegistry.kt` | angebotene Query-Verträge |
| `…-bus-core/…/envelope/EventEnvelope.kt` | Draht-Form eines Events |
| `…-bus-core/…/dispatch/EventDispatcher.kt` | Zustellung an alle Treffer |
| `…-bus-core/…/dispatch/EventTypeResolver.kt` | Wire-Typ → Klasse, zweistufig, gecacht |
| `…-bus-core/…/audit/LoggingAuditSink.kt` | Fallback, wenn kein Rabbit freigeschaltet ist |
| `…-bus-core/…/SurfEventBusImpl.kt` | Lebenszyklus und Verdrahtung |
| `…-bus-core/src/test/…/FakeEventTransport.kt`, `FakeQueryTransport.kt` | Test-Doubles |

**Gelöscht (in rabbitmq-core, weil ihre Nachfolger hier entstehen):** nichts. Plan 3 löscht
`EventTopics`, `EventSubscription`, `EventSubscriptionRegistry` und `EventDispatcher` dort, wenn
der Redis-Transport steht. Bis dahin existieren beide Fassungen parallel; das ist der Preis dafür,
dass jede Etappe übersetzbar endet.

---

## Task 1: Module und `EventTopics`

**Files:**
- Create: `surf-eventbus-bus/surf-eventbus-bus-api/build.gradle.kts`
- Create: `surf-eventbus-bus/surf-eventbus-bus-core/build.gradle.kts`
- Create: `…-bus-api/src/main/kotlin/dev/slne/surf/eventbus/event/EventTopics.kt`
- Create: `…-bus-api/src/test/kotlin/dev/slne/surf/eventbus/event/EventTopicsTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/CommonPurityTest.kt`

**Interfaces:**
- Consumes: `surf-eventbus-common` aus Plan 1.
- Produces:
  - `EventTopics.topicOf(eventClass: Class<out SurfBusEvent>): String`
  - `EventTopics.validatePublishTopic(topic: String)`
  - `EventTopics.validateBindingPattern(pattern: String)`
  - `EventTopics.matches(pattern: String, topic: String): Boolean`
  Jeder folgende Task benutzt genau diese vier.

- [ ] **Step 1: Module anlegen**

`surf-eventbus-bus/surf-eventbus-bus-api/build.gradle.kts`:

```kotlin
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    api(projects.surfEventbusCommon)
    api(platform(libs.netty.bom))
    compileOnlyApi("io.netty:netty-buffer")

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
    testImplementation("io.netty:netty-buffer")
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

publishing {
    repositories { slneReleases() }
}
```

`surf-eventbus-bus/surf-eventbus-bus-core/build.gradle.kts`: identisch, aber
`api(projects.surfEventbusBus.surfEventbusBusApi)` statt `api(projects.surfEventbusCommon)`.

`settings.gradle.kts` ergänzen, direkt hinter `surf-eventbus-common`:

```kotlin
include("surf-eventbus-bus:surf-eventbus-bus-api")
include("surf-eventbus-bus:surf-eventbus-bus-core")
```

- [ ] **Step 2: `EventTopicsTest` aus rabbitmq-core kopieren und failing machen**

Die bestehende Testklasse liegt in
`surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/core/event/EventTopicsTest.kt`.
Kopieren nach `…-bus-api/src/test/kotlin/dev/slne/surf/eventbus/event/EventTopicsTest.kt`,
`package` auf `dev.slne.surf.eventbus.event` setzen, den Import von `RabbitEventPacket` entfernen
und die Testfälle, die `topicOf` benutzen, vorläufig auf einen lokalen Stub zeigen lassen:

```kotlin
package dev.slne.surf.eventbus.event

import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventTopicsTest {

    @Test
    fun `a star matches exactly one segment`() {
        assertTrue(EventTopics.matches("faction.*", "faction.disbanded"))
        assertFalse(EventTopics.matches("faction.*", "faction.member.joined"))
        assertFalse(EventTopics.matches("faction.*", "faction"))
    }

    @Test
    fun `a hash matches zero or more segments`() {
        assertTrue(EventTopics.matches("faction.#", "faction"))
        assertTrue(EventTopics.matches("faction.#", "faction.disbanded"))
        assertTrue(EventTopics.matches("faction.#", "faction.member.joined"))
        assertFalse(EventTopics.matches("faction.#", "guild.disbanded"))
    }

    @Test
    fun `a publish topic must not contain a wildcard`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.*") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction.#") }
        EventTopics.validatePublishTopic("faction.disbanded")
    }

    @Test
    fun `an empty segment is rejected in topics and patterns`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.validatePublishTopic("faction..disbanded") }
        assertFailsWith<IllegalArgumentException> { EventTopics.validateBindingPattern("faction..#") }
    }

    @Test
    fun `topicOf reads the annotation of the event type`() {
        assertTrue(EventTopics.topicOf(AnnotatedTestEvent::class.java) == "test.annotated")
    }

    @Test
    fun `topicOf rejects an event type without an annotation`() {
        assertFailsWith<IllegalArgumentException> { EventTopics.topicOf(UnannotatedTestEvent::class.java) }
    }
}

@BusEvent("test.annotated")
private class AnnotatedTestEvent : SurfBusEvent()

private class UnannotatedTestEvent : SurfBusEvent()
```

- [ ] **Step 3: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-api:test --tests '*EventTopicsTest*'`
Expected: FAIL, „Unresolved reference: EventTopics" (und `SurfBusEvent`, `BusEvent` — die kommen
in Task 2; bis dahin scheitert die Klasse an drei Referenzen, das ist der Punkt).

- [ ] **Step 4: `EventTopics` übernehmen**

Die bestehende Implementierung aus
`…rabbitmq-core/…/core/event/EventTopics.kt` kopieren, Package auf
`dev.slne.surf.eventbus.event` setzen und eine Zeile ändern: `topicOf` liest `@BusEvent` statt
`@RabbitEvent` und nimmt `Class<out SurfBusEvent>`:

```kotlin
    /**
     * The topic declared by [eventClass] via [BusEvent].
     *
     * The topic belongs to the type, not to the call site: a publisher cannot send the same
     * event under two keys.
     */
    fun topicOf(eventClass: Class<out SurfBusEvent>): String {
        val annotation = eventClass.getAnnotation(BusEvent::class.java)
        require(annotation != null) {
            "${eventClass.name} is missing @BusEvent(\"<topic>\")"
        }

        validatePublishTopic(annotation.topic)
        return annotation.topic
    }
```

Der restliche Inhalt — `validatePublishTopic`, `validateBindingPattern`, beide `matches` — wird
wörtlich übernommen. Er ist unit-getestet und die Semantik soll sich nicht ändern.

- [ ] **Step 5: Test läuft nach Task 2**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-api:test --tests '*EventTopicsTest*'`
Expected: FAIL, jetzt nur noch an `SurfBusEvent` und `BusEvent`. Task 2 schließt das; kein
Commit vor grünem Test.

- [ ] **Step 6: `CommonPurityTest` auf die neuen Module erweitern**

Der Test prüft heute nur sein eigenes Modul (`Path.of("src", "main", "kotlin")`). Er bekommt eine
Liste von Modulwurzeln, damit auch die Bus-Module brokerfrei bleiben:

```kotlin
    private val brokerFreeModules = listOf(
        Path.of("src", "main", "kotlin"),
        Path.of("..", "surf-eventbus-bus", "surf-eventbus-bus-api", "src", "main", "kotlin"),
        Path.of("..", "surf-eventbus-bus", "surf-eventbus-bus-core", "src", "main", "kotlin")
    )
```

und iteriert darüber statt über eine einzelne Wurzel. Nicht existierende Wurzeln werden
übersprungen, nicht als Fehler gewertet — sonst scheitert der Test zwischen zwei Tasks.

---

## Task 2: Event-Typen

**Files:**
- Create: `…-bus-api/src/main/kotlin/dev/slne/surf/eventbus/event/SurfBusEvent.kt`
- Create: `…-bus-api/…/event/BusEvent.kt`
- Create: `…-bus-api/…/event/BusEventCodec.kt`
- Create: `…-bus-api/src/test/kotlin/dev/slne/surf/eventbus/event/SurfBusEventTest.kt`

**Interfaces:**
- Consumes: `EventTopics` aus Task 1.
- Produces:
  - `abstract class SurfBusEvent` mit `var originInstanceId: String?` und
    `var publishedAtEpochMs: Long`, beide `internal set` über `@InternalEventBusApi`
  - `annotation class BusEvent(val topic: String)`
  - `interface BusEventCodec<T : SurfBusEvent> { fun encode(buffer: ByteBuf, value: T); fun decode(buffer: ByteBuf): T }`
  - `annotation class InternalEventBusApi` (Opt-in-Marker, Gegenstück zu `InternalRabbitMQ`)

- [ ] **Step 1: Failing test schreiben**

`…-bus-api/src/test/kotlin/dev/slne/surf/eventbus/event/SurfBusEventTest.kt`:

```kotlin
package dev.slne.surf.eventbus.event

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SurfBusEventTest {

    @Test
    fun `a fresh event has no origin and no publish timestamp`() {
        val event = TestEvent("x")

        assertNull(event.originInstanceId, "origin is stamped by the bus, not by the constructor")
        assertEquals(0L, event.publishedAtEpochMs, "the timestamp is stamped at publish")
    }

    @Test
    fun `the topic comes from the annotation`() {
        assertEquals("test.plain", EventTopics.topicOf(TestEvent::class.java))
    }
}

@BusEvent("test.plain")
private class TestEvent(val value: String) : SurfBusEvent()
```

Beachte: der Zeitstempel ist absichtlich **nicht** im Konstruktor gesetzt — anders als
`RedisEvent.timestamp`, das `System.currentTimeMillis()` bei der Konstruktion nahm. Ein Event, das
konstruiert und erst eine Sekunde später publiziert wird, log damit eine falsche Zeit.

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-api:test --tests '*SurfBusEventTest*'`
Expected: FAIL, „Unresolved reference: SurfBusEvent".

- [ ] **Step 3: Typen schreiben**

`…/event/SurfBusEvent.kt`:

```kotlin
package dev.slne.surf.eventbus.event

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.Serializable

/**
 * Base type of every event on the bus.
 *
 * Events are broadcast notifications without persistence: an instance that is offline misses
 * them, and there is no redelivery. Anything that must not be lost is a `@FireAndForget` RPC
 * call, not an event.
 */
@Serializable
abstract class SurfBusEvent {

    /**
     * The instance that published this event, or `null` on an event that has not been published
     * yet.
     *
     * Basis of self-delivery filtering: `@SurfSubscribe(includeSelf = false)` — the default —
     * drops events whose origin is this process.
     */
    var originInstanceId: String? = null
        @InternalEventBusApi set

    /**
     * When the event was published, in epoch milliseconds, or `0` before publishing.
     *
     * Stamped at publish rather than at construction: an event built now and published a second
     * later would otherwise carry a time that never happened on the wire.
     */
    var publishedAtEpochMs: Long = 0L
        @InternalEventBusApi set
}
```

`…/event/BusEvent.kt`:

```kotlin
package dev.slne.surf.eventbus.event

/**
 * The topic under which instances of the annotated event type travel.
 *
 * Dot-separated and wildcard-free. Declaring it at the type rather than at the call site means a
 * publisher cannot send the same event under two keys.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class BusEvent(val topic: String)
```

`…/event/BusEventCodec.kt`:

```kotlin
package dev.slne.surf.eventbus.event

import io.netty.buffer.ByteBuf

/**
 * Opt-in binary encoding for one event type, declared by its companion object.
 *
 * Events with a codec travel on `surf.eventbus.bin.<topic>` instead of the JSON channel. The
 * mechanism comes from surf-redis 1.10.1, where it exists with a JMH benchmark behind it; what
 * changed is that the topic from [BusEvent] replaces the former stable `eventId`.
 *
 * Envelope metadata — origin and timestamp — is carried by the envelope and must not be encoded
 * here.
 *
 * ```kotlin
 * @BusEvent("player.joined")
 * class PlayerJoined(val playerName: String) : SurfBusEvent() {
 *     companion object : BusEventCodec<PlayerJoined> {
 *         override fun encode(buffer: ByteBuf, value: PlayerJoined) = buffer.writeString(value.playerName)
 *         override fun decode(buffer: ByteBuf) = PlayerJoined(buffer.readString())
 *     }
 * }
 * ```
 */
interface BusEventCodec<T : SurfBusEvent> {
    fun encode(buffer: ByteBuf, value: T)
    fun decode(buffer: ByteBuf): T
}
```

`…/InternalEventBusApi.kt`:

```kotlin
package dev.slne.surf.eventbus

@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to surf-eventbus. It can change in any release."
)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_SETTER)
annotation class InternalEventBusApi
```

- [ ] **Step 4: Opt-in im Build ergänzen**

Im Wurzel-`build.gradle.kts`, neben dem bestehenden Rabbit-Opt-in:

```kotlin
                optIn.add("dev.slne.surf.eventbus.InternalEventBusApi")
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-api:test`
Expected: PASS — `SurfBusEventTest` und `EventTopicsTest` (der jetzt seine drei Referenzen hat).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add the event types and topic matcher of the bus API

EventTopics keeps surf-rabbitmq's AMQP pattern semantics verbatim; only its
source of the topic changes from @RabbitEvent to @BusEvent. The publish
timestamp moves from construction to publish time."
```

---

## Task 3: Abonnements und ihre Validierung

**Files:**
- Create: `…-bus-api/…/event/SurfSubscribe.kt`
- Create: `…-bus-core/src/main/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscription.kt`
- Create: `…-bus-core/…/registry/EventSubscriptionRegistry.kt`
- Create: `…-bus-core/src/test/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscriptionRegistryTest.kt`

**Interfaces:**
- Consumes: `SurfBusEvent`, `BusEvent`, `EventTopics`.
- Produces:
  - `annotation class SurfSubscribe(val topic: String = "", val includeSelf: Boolean = false)`
  - `data class EventSubscription(val eventClass: Class<out SurfBusEvent>, val pattern: String, val includeSelf: Boolean, val listener: Any, val method: Method)`
  - `class EventSubscriptionRegistry` mit `register(listener: Any)`, `subscriptions(): List<EventSubscription>`,
    `patterns(): Set<String>`, `exactTopics(): Set<String>`, `wildcardPatterns(): Set<String>`,
    `subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription>`,
    `isEmpty(): Boolean`, `freeze()`

- [ ] **Step 1: Failing test schreiben**

`…-bus-core/src/test/kotlin/dev/slne/surf/eventbus/core/registry/EventSubscriptionRegistryTest.kt`:

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EventSubscriptionRegistryTest {

    @Test
    fun `a handler without an explicit topic subscribes to the topic of its parameter`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)

        assertEquals(setOf("test.plain"), registry.patterns())
        assertEquals(1, registry.subscriptions().size)
        assertEquals(false, registry.subscriptions().single().includeSelf)
    }

    @Test
    fun `an explicit pattern wins over the topic of the parameter`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PatternListener)

        assertEquals(setOf("test.#"), registry.patterns())
    }

    @Test
    fun `exact and wildcard patterns are reported separately`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)
        registry.register(PatternListener)

        assertEquals(setOf("test.plain"), registry.exactTopics())
        assertEquals(setOf("test.#"), registry.wildcardPatterns())
    }

    @Test
    fun `a handler with two parameters is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(TwoParameterListener)
        }

        assertContains(failure.message!!, "exactly one parameter")
    }

    @Test
    fun `a handler whose parameter is not a bus event is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(WrongParameterListener)
        }

        assertContains(failure.message!!, "SurfBusEvent")
    }

    @Test
    fun `a malformed pattern is rejected at registration`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(MalformedPatternListener)
        }

        assertContains(failure.message!!, "test..broken")
    }

    @Test
    fun `an event type without an annotation is rejected at registration`() {
        assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(UnannotatedEventListener)
        }
    }

    @Test
    fun `subscriptionsFor returns every matching subscription`() {
        val registry = EventSubscriptionRegistry()
        registry.register(PlainListener)
        registry.register(PatternListener)

        val matches = registry.subscriptionsFor(PlainEvent::class.java, "test.plain")

        assertEquals(2, matches.size, "both the exact and the wildcard handler must fire")
    }

    @Test
    fun `a handler on a base type receives a subtype`() {
        val registry = EventSubscriptionRegistry()
        registry.register(BaseTypeListener)

        val matches = registry.subscriptionsFor(SubEvent::class.java, "test.sub")

        assertEquals(1, matches.size)
    }

    @Test
    fun `registration after freeze is rejected`() {
        val registry = EventSubscriptionRegistry()
        registry.freeze()

        assertFailsWith<IllegalStateException> { registry.register(PlainListener) }
    }

    @Test
    fun `a listener without any annotated method is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventSubscriptionRegistry().register(EmptyListener)
        }

        assertContains(failure.message!!, "@SurfSubscribe")
    }
}

@BusEvent("test.plain")
private class PlainEvent : SurfBusEvent()

@BusEvent("test.base")
private open class BaseEvent : SurfBusEvent()

@BusEvent("test.sub")
private class SubEvent : BaseEvent()

private class UnannotatedEvent : SurfBusEvent()

private object PlainListener {
    @SurfSubscribe
    fun onPlain(event: PlainEvent) = Unit
}

private object PatternListener {
    @SurfSubscribe(topic = "test.#")
    fun onAny(event: PlainEvent) = Unit
}

private object BaseTypeListener {
    @SurfSubscribe(topic = "test.#")
    fun onBase(event: BaseEvent) = Unit
}

private object TwoParameterListener {
    @SurfSubscribe
    fun onPlain(event: PlainEvent, extra: String) = Unit
}

private object WrongParameterListener {
    @SurfSubscribe
    fun onString(event: String) = Unit
}

private object MalformedPatternListener {
    @SurfSubscribe(topic = "test..broken")
    fun onPlain(event: PlainEvent) = Unit
}

private object UnannotatedEventListener {
    @SurfSubscribe
    fun onUnannotated(event: UnannotatedEvent) = Unit
}

private object EmptyListener
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventSubscriptionRegistryTest*'`
Expected: FAIL, „Unresolved reference: EventSubscriptionRegistry".

- [ ] **Step 3: `@SurfSubscribe` schreiben**

```kotlin
package dev.slne.surf.eventbus.event

/**
 * Marks a method as an event handler.
 *
 * @property topic pattern to subscribe to. Empty means the topic of the parameter type.
 *   `*` matches exactly one segment, `#` zero or more.
 * @property includeSelf whether this handler also sees events published by its own process.
 *   `false` by default because all ~20 pre-2.0 handlers opened with an
 *   `originatesFromThisClient()` check and forgetting it is the usual cause of a feedback loop.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class SurfSubscribe(
    val topic: String = "",
    val includeSelf: Boolean = false
)
```

- [ ] **Step 4: Registry schreiben**

Die bestehende `EventSubscriptionRegistry` aus rabbitmq-core ist die Vorlage; sie verliert `mode`
und `retry` und gewinnt `includeSelf`, die Trennung exakt/Wildcard und `freeze()`.

`…-bus-core/…/registry/EventSubscription.kt`:

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.SurfBusEvent
import java.lang.reflect.Method

data class EventSubscription(
    val eventClass: Class<out SurfBusEvent>,
    val pattern: String,
    val includeSelf: Boolean,
    val listener: Any,
    val method: Method
) {
    /** `Klasse#Methode`, used in log lines and audit rows. */
    val displayName: String get() = "${listener.javaClass.simpleName}#${method.name}"
}
```

`…/registry/EventSubscriptionRegistry.kt`:

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.EventTopics
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Holds every `@SurfSubscribe` method of the process.
 *
 * Everything is validated here rather than at delivery: a wrong parameter type or a malformed
 * pattern would otherwise show up as an event that simply never arrives.
 */
class EventSubscriptionRegistry {

    private val entries = CopyOnWriteArrayList<EventSubscription>()

    @Volatile
    private var frozen = false

    fun register(listener: Any) {
        check(!frozen) { "registration is closed: freeze() has already run" }

        var found = 0

        for (method in listener.javaClass.methods) {
            val annotation = method.getAnnotation(SurfSubscribe::class.java) ?: continue
            found++

            val isSuspend = method.parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation"
            val declaredCount = if (isSuspend) method.parameterCount - 1 else method.parameterCount

            require(declaredCount == 1) {
                "${listener.javaClass.name}#${method.name} must take exactly one parameter, " +
                        "the event; found $declaredCount"
            }

            val parameterType = method.parameterTypes[0]
            require(SurfBusEvent::class.java.isAssignableFrom(parameterType)) {
                "${listener.javaClass.name}#${method.name} takes ${parameterType.name}, " +
                        "which does not extend SurfBusEvent"
            }

            @Suppress("UNCHECKED_CAST")
            val eventClass = parameterType as Class<out SurfBusEvent>
            val pattern = annotation.topic.ifBlank { EventTopics.topicOf(eventClass) }
            EventTopics.validateBindingPattern(pattern)

            entries += EventSubscription(
                eventClass = eventClass,
                pattern = pattern,
                includeSelf = annotation.includeSelf,
                listener = listener,
                method = method
            )
        }

        require(found > 0) {
            "${listener.javaClass.name} has no @SurfSubscribe method. Registering it is a no-op " +
                    "and most likely a mistake."
        }
    }

    fun freeze() {
        frozen = true
    }

    fun subscriptions(): List<EventSubscription> = entries.toList()

    fun patterns(): Set<String> = entries.mapTo(mutableSetOf()) { it.pattern }

    fun exactTopics(): Set<String> = patterns().filterNotTo(mutableSetOf(), ::hasWildcard)

    fun wildcardPatterns(): Set<String> = patterns().filterTo(mutableSetOf(), ::hasWildcard)

    /**
     * Every subscription whose pattern matches [topic] and whose parameter type is assignable
     * from [eventClass].
     *
     * Type-hierarchy aware on purpose: a handler declared on a base type is what makes
     * `@SurfSubscribe(topic = "faction.#")` usable at all.
     */
    fun subscriptionsFor(eventClass: Class<*>, topic: String): List<EventSubscription> =
        entries.filter { subscription ->
            subscription.eventClass.isAssignableFrom(eventClass) &&
                    EventTopics.matches(subscription.pattern, topic)
        }

    fun isEmpty(): Boolean = entries.isEmpty()

    private fun hasWildcard(pattern: String): Boolean = '*' in pattern || '#' in pattern
}
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventSubscriptionRegistryTest*'`
Expected: PASS, alle elf Tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add @SurfSubscribe and the subscription registry

Two annotation parameters, topic and includeSelf; no mode and no retry,
because Redis Pub/Sub can hold neither promise. Everything is validated at
registration."
```

---

## Task 4: Envelope

**Files:**
- Create: `…-bus-core/src/main/kotlin/dev/slne/surf/eventbus/core/envelope/EventEnvelope.kt`
- Create: `…-bus-core/src/test/kotlin/dev/slne/surf/eventbus/core/envelope/EventEnvelopeTest.kt`

**Interfaces:**
- Produces:
  - `data class EventEnvelope(val topic: String, val type: String, val originInstanceId: String, val publishedAtEpochMs: Long, val payload: String?)`
    — `payload` ist der JSON-Text des Events, `null` bei einem Codec-Event, dessen Nutzlast
    binär auf dem anderen Kanal reist
  - `EventEnvelope.encodeToString(json: Json): String`
  - `EventEnvelope.Companion.decodeFromString(json: Json, text: String): EventEnvelope`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.envelope

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventEnvelopeTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an envelope survives a round trip`() {
        val original = EventEnvelope(
            topic = "faction.disbanded",
            type = "dev.example.FactionDisbandedEvent",
            originInstanceId = "lobby-3",
            publishedAtEpochMs = 1_764_000_000_000,
            payload = """{"factionId":"a"}"""
        )

        val restored = EventEnvelope.decodeFromString(json, original.encodeToString(json))

        assertEquals(original, restored)
    }

    @Test
    fun `an envelope without payload survives a round trip`() {
        val original = EventEnvelope(
            topic = "player.joined",
            type = "dev.example.PlayerJoined",
            originInstanceId = "lobby-3",
            publishedAtEpochMs = 1_764_000_000_000,
            payload = null
        )

        assertEquals(original, EventEnvelope.decodeFromString(json, original.encodeToString(json)))
    }

    @Test
    fun `an unknown field in the envelope is ignored`() {
        val text = """{"topic":"a.b","type":"T","originInstanceId":"i","publishedAtEpochMs":1,"payload":null,"future":"x"}"""

        val restored = EventEnvelope.decodeFromString(json, text)

        assertEquals("a.b", restored.topic)
    }

    @Test
    fun `a malformed envelope fails with its text in the message`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            EventEnvelope.decodeFromString(json, "not json")
        }

        assertEquals(true, failure.message!!.contains("not json"))
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventEnvelopeTest*'`
Expected: FAIL, „Unresolved reference: EventEnvelope".

- [ ] **Step 3: Envelope schreiben**

```kotlin
package dev.slne.surf.eventbus.core.envelope

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What travels on the wire around an event.
 *
 * Logically identical on both channel families; the payload is JSON on
 * `surf.eventbus.json.<topic>` and `null` on `surf.eventbus.bin.<topic>`, where the event body is
 * the binary frame itself.
 */
@Serializable
data class EventEnvelope(
    val topic: String,
    val type: String,
    val originInstanceId: String,
    val publishedAtEpochMs: Long,
    val payload: String?
) {
    fun encodeToString(json: Json): String = json.encodeToString(serializer(), this)

    companion object {
        /**
         * @throws IllegalArgumentException with the offending text included. A malformed envelope
         *   is a wire-level defect and the text is the only clue the receiver has.
         */
        fun decodeFromString(json: Json, text: String): EventEnvelope = try {
            json.decodeFromString(serializer(), text)
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("not a valid event envelope: $text", exception)
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventEnvelopeTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add the event envelope with a JSON round trip"
```

---

## Task 5: Audit-Typen und die Log-Senke

**Files:**
- Create: `…-bus-api/…/audit/AuditKind.kt`, `AuditReport.kt`, `AuditSink.kt`
- Create: `…-bus-core/…/audit/LoggingAuditSink.kt`
- Create: `…-bus-core/src/test/…/audit/LoggingAuditSinkTest.kt`

**Interfaces:**
- Produces:
  - `enum class AuditKind { HANDLER_FAILED, UNROUTABLE, UNDESERIALIZABLE, CHUNK_SERIES_EXPIRED, UNKNOWN_EVENT_TYPE, EVENT_HANDLER_FAILED, QUERY_HANDLER_FAILED }`
  - `data class AuditReport(...)` — vollständige Feldliste unten
  - `interface AuditSink { suspend fun report(report: AuditReport) }`
  - `object LoggingAuditSink : AuditSink`
  Plan 3 und 4 füllen `AuditReport` an den Meldestellen; Plan 4 implementiert die zweite
  `AuditSink` über RabbitMQ.

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.audit

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class LoggingAuditSinkTest {

    @Test
    fun `reporting never throws even when every optional field is absent`() = runBlocking {
        val report = AuditReport(
            messageUuid = "3f1b0c8e-0000-0000-0000-000000000001",
            kind = AuditKind.EVENT_HANDLER_FAILED,
            originService = "surf-factions",
            originInstance = "lobby-3",
            reportedByService = "surf-factions",
            reportedByInstance = "lobby-3",
            failedAtEpochMs = 1_764_000_000_000
        )

        LoggingAuditSink.report(report)
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*LoggingAuditSinkTest*'`
Expected: FAIL, „Unresolved reference: AuditReport".

- [ ] **Step 3: Typen schreiben**

`…-bus-api/…/audit/AuditKind.kt`:

```kotlin
package dev.slne.surf.eventbus.audit

/**
 * Why a message is being audited.
 *
 * Every value marks a path on which a message would otherwise vanish without a trace. Failures
 * the caller learns about — a rejected publish, an RPC timeout, an expired request — are
 * deliberately absent: they are errors with an addressee, not losses.
 */
enum class AuditKind {
    HANDLER_FAILED,
    UNROUTABLE,
    UNDESERIALIZABLE,
    CHUNK_SERIES_EXPIRED,
    UNKNOWN_EVENT_TYPE,
    EVENT_HANDLER_FAILED,
    QUERY_HANDLER_FAILED
}
```

`…/audit/AuditReport.kt`:

```kotlin
package dev.slne.surf.eventbus.audit

import kotlinx.serialization.Serializable

/**
 * One audit incident, ready to be written to a row.
 *
 * Deliberately transport-free so that the event and query dispatchers in `surf-eventbus-bus-core`
 * can report without seeing a broker type. The RabbitMQ side wraps it in a packet.
 *
 * @property messageUuid identity across every report about the same message. The retry ladder
 *   republishes up to four times, possibly in different processes; the reporter stamps this into
 *   a header on the first failure so the attempts find each other in the database.
 * @property attempt 1-based on the retry ladder, `0` on paths without one.
 * @property terminal `true` on the last attempt — the row that used to be the dead-letter queue.
 * @property payload the message body, already truncated to the configured limit.
 */
@Serializable
data class AuditReport(
    val messageUuid: String,
    val kind: AuditKind,
    val originService: String,
    val originInstance: String?,
    val reportedByService: String,
    val reportedByInstance: String,
    val failedAtEpochMs: Long,
    val exchange: String? = null,
    val routingKey: String? = null,
    val originQueue: String? = null,
    val messageType: String? = null,
    val contract: String? = null,
    val callable: String? = null,
    val correlationId: String? = null,
    val handler: String? = null,
    val attempt: Int = 0,
    val terminal: Boolean = true,
    val retryTier: String? = null,
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val stacktrace: String? = null,
    val payloadEncoding: String? = null,
    val payloadSizeBytes: Int = 0,
    val payloadTruncated: Boolean = false,
    val payload: ByteArray? = null,
    val headers: Map<String, String> = emptyMap()
) {
    // ByteArray in a data class: equals/hashCode must compare content, not identity, or two
    // reports about the same message look different.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuditReport) return false
        if (messageUuid != other.messageUuid) return false
        if (attempt != other.attempt) return false
        if (!payload.contentEquals(other.payload)) return false
        return kind == other.kind
    }

    override fun hashCode(): Int {
        var result = messageUuid.hashCode()
        result = 31 * result + attempt
        result = 31 * result + kind.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}
```

`…/audit/AuditSink.kt`:

```kotlin
package dev.slne.surf.eventbus.audit

/**
 * Where audit reports go.
 *
 * Implemented over RabbitMQ when that transport is enabled, and by a logging fallback otherwise.
 * Implementations must never throw: the original path — acking a message, logging a handler
 * failure — must not fail because the audit was unreachable.
 */
interface AuditSink {
    suspend fun report(report: AuditReport)
}
```

`…-bus-core/…/audit/LoggingAuditSink.kt`:

```kotlin
package dev.slne.surf.eventbus.core.audit

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink

/**
 * The fallback sink for a process without the RabbitMQ transport.
 *
 * The console stays the trace when the database cannot be reached, so a process that can never
 * reach the audit service still leaves the full incident somewhere.
 */
object LoggingAuditSink : AuditSink {

    private val log = logger()

    override suspend fun report(report: AuditReport) {
        log.atWarning().log(
            "Audit (%s) for %s on %s: handler=%s type=%s attempt=%s terminal=%s cause=%s: %s",
            report.kind, report.originService, report.originInstance, report.handler,
            report.messageType, report.attempt, report.terminal, report.exceptionClass,
            report.exceptionMessage
        )
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*LoggingAuditSinkTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add AuditReport, AuditKind and the logging audit sink

AuditReport lives in the bus API so the event and query dispatchers can
report without seeing a broker type."
```

---

## Task 6: Transport-Nähte und der Event-Dispatcher

**Files:**
- Create: `…-bus-api/…/transport/EventTransport.kt`
- Create: `…-bus-core/…/dispatch/EventDispatcher.kt`
- Create: `…-bus-core/src/test/…/dispatch/FakeEventTransport.kt`
- Create: `…-bus-core/src/test/…/dispatch/EventDispatcherTest.kt`

**Interfaces:**
- Consumes: Registry (Task 3), Envelope (Task 4), Audit (Task 5).
- Produces:
  - `interface EventTransport { suspend fun connect(exactTopics: Set<String>, wildcardPatterns: Set<String>, onEvent: suspend (EventEnvelope, ByteArray?) -> Unit); suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?); suspend fun disconnect() }`
  - `class EventDispatcher(registry, instanceId, auditSink, json, typeResolver)` mit
    `suspend fun dispatch(envelope: EventEnvelope, binaryPayload: ByteArray?)`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val reports = CopyOnWriteArrayList<AuditReport>()
    private val sink = object : AuditSink {
        override suspend fun report(report: AuditReport) {
            reports += report
        }
    }

    private fun envelopeFor(event: DispatchEvent, origin: String) = EventEnvelope(
        topic = "dispatch.test",
        type = DispatchEvent::class.java.name,
        originInstanceId = origin,
        publishedAtEpochMs = 1,
        payload = json.encodeToString(DispatchEvent.serializer(), event)
    )

    private fun dispatcher(registry: EventSubscriptionRegistry) = EventDispatcher(
        registry = registry,
        instanceId = "lobby-1",
        auditSink = sink,
        json = json,
        typeResolver = EventTypeResolver()
    )

    @Test
    fun `every matching handler runs`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ExactListener)
            register(WildcardListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("a"), "lobby-2"), null)

        assertEquals(listOf("exact:a", "wildcard:a"), Recorder.seen.sorted())
    }

    @Test
    fun `a failing handler does not stop its neighbour`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ThrowingListener)
            register(ExactListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("b"), "lobby-2"), null)

        assertTrue(Recorder.seen.contains("exact:b"), "the healthy handler must still have run")
        assertEquals(1, reports.size)
        assertEquals(AuditKind.EVENT_HANDLER_FAILED, reports.single().kind)
        assertEquals("ThrowingListener#onEvent", reports.single().handler)
    }

    @Test
    fun `an event from this instance is dropped unless includeSelf is set`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply {
            register(ExactListener)
            register(SelfListener)
        }

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("c"), "lobby-1"), null)

        assertEquals(listOf("self:c"), Recorder.seen)
    }

    @Test
    fun `an unresolvable type is warned about once and audited once`() = runBlocking {
        val registry = EventSubscriptionRegistry().apply { register(ExactListener) }
        val unknown = EventEnvelope("dispatch.test", "dev.example.Gone", "lobby-2", 1, "{}")
        val dispatcher = dispatcher(registry)

        dispatcher.dispatch(unknown, null)
        dispatcher.dispatch(unknown, null)

        assertEquals(1, reports.size, "a stream of unknown types must not become a stream of rows")
        assertEquals(AuditKind.UNKNOWN_EVENT_TYPE, reports.single().kind)
    }

    @Test
    fun `an event without any subscriber is not an error`() = runBlocking {
        val registry = EventSubscriptionRegistry()

        dispatcher(registry).dispatch(envelopeFor(DispatchEvent("d"), "lobby-2"), null)

        assertEquals(0, reports.size)
    }
}

@Serializable
@BusEvent("dispatch.test")
class DispatchEvent(val value: String) : SurfBusEvent()

private object Recorder {
    val seen = CopyOnWriteArrayList<String>()
}

private object ExactListener {
    @SurfSubscribe
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "exact:${event.value}"
    }
}

private object WildcardListener {
    @SurfSubscribe(topic = "dispatch.#")
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "wildcard:${event.value}"
    }
}

private object SelfListener {
    @SurfSubscribe(includeSelf = true)
    fun onEvent(event: DispatchEvent) {
        Recorder.seen += "self:${event.value}"
    }
}

private object ThrowingListener {
    @SurfSubscribe
    fun onEvent(event: DispatchEvent): Unit = error("handler is broken")
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventDispatcherTest*'`
Expected: FAIL, „Unresolved reference: EventDispatcher" und „EventTypeResolver".

- [ ] **Step 3: Naht schreiben**

```kotlin
package dev.slne.surf.eventbus.transport

import dev.slne.surf.eventbus.core.envelope.EventEnvelope

/**
 * The seam between the bus and the Redis event channels.
 *
 * Exactly one real implementation exists. It is an interface so that registry and dispatcher can
 * be tested without a server — not a promise that other transports can be plugged in.
 */
interface EventTransport {

    /**
     * Subscribes and starts delivering.
     *
     * @param exactTopics wildcard-free topics; the broker filters those.
     * @param wildcardPatterns patterns that need local matching.
     * @param onEvent called per received message. The second parameter carries the binary frame
     *   of a `BusEventCodec` event and is `null` for a JSON event.
     */
    suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    )

    suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?)

    suspend fun disconnect()
}
```

- [ ] **Step 4: `EventTypeResolver` schreiben**

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.event.SurfBusEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the wire type name to a class, in two stages.
 *
 * The wire carries the **concrete** type name while a handler may only have registered a base
 * type, so a resolver that knows only literally registered types would drop exactly the
 * documented polymorphic subscription. This is the bug the pre-2.0 Rabbit path had.
 *
 * Stage one is the registry — cheap, exact, the normal case. Stage two is `Class.forName` over
 * the class loaders of the registered listeners, because event types live in the subscribing
 * plugin on Paper and Velocity, not in ours. Positive **and** negative results are cached so a
 * stream of unknown types does not become a stream of failing lookups.
 */
class EventTypeResolver {

    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val value: Class<out SurfBusEvent>?)

    fun resolve(typeName: String, known: Collection<Class<out SurfBusEvent>>, loaders: Collection<ClassLoader>):
            Class<out SurfBusEvent>? {
        cache[typeName]?.let { return it.value }

        val fromRegistry = known.firstOrNull { it.name == typeName }
        if (fromRegistry != null) {
            cache[typeName] = Optional(fromRegistry)
            return fromRegistry
        }

        for (loader in loaders) {
            val candidate = try {
                Class.forName(typeName, false, loader)
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: LinkageError) {
                continue
            }

            // Loading only what extends SurfBusEvent: a wire type name is attacker-adjacent
            // input, and initialising an arbitrary class because someone published its name is
            // not a thing this bus does.
            if (SurfBusEvent::class.java.isAssignableFrom(candidate)) {
                @Suppress("UNCHECKED_CAST")
                val resolved = candidate as Class<out SurfBusEvent>
                cache[typeName] = Optional(resolved)
                return resolved
            }
        }

        cache[typeName] = Optional(null)
        return null
    }

    /** Whether [typeName] has already produced a negative result. Basis of warning exactly once. */
    fun isKnownUnresolvable(typeName: String): Boolean = cache[typeName]?.value == null && cache.containsKey(typeName)
}
```

- [ ] **Step 5: Dispatcher schreiben**

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.SurfBusEvent
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers one received envelope to every matching handler.
 *
 * Four rules, each of them a decision:
 * - every match runs, exact and wildcard alike;
 * - a failing handler does not stop its neighbours, and there is nothing to decide afterwards
 *   because Pub/Sub has no ack to withhold;
 * - `includeSelf = false` drops an event this process published;
 * - an unresolvable type warns and audits exactly once per type and process lifetime.
 */
class EventDispatcher(
    private val registry: EventSubscriptionRegistry,
    private val instanceId: String,
    private val auditSink: AuditSink,
    private val json: Json,
    private val typeResolver: EventTypeResolver,
    private val serviceName: String = "unknown"
) {

    private val warnedTypes = ConcurrentHashMap.newKeySet<String>()

    suspend fun dispatch(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        val subscriptions = registry.subscriptions()
        val eventClass = typeResolver.resolve(
            typeName = envelope.type,
            known = subscriptions.map { it.eventClass },
            loaders = subscriptions.mapTo(mutableSetOf()) { it.listener.javaClass.classLoader }
        )

        if (eventClass == null) {
            if (warnedTypes.add(envelope.type)) {
                log.atWarning().log(
                    "No class for event type %s on topic %s; discarding. A stale publisher or a " +
                            "deleted event type looks exactly like this.",
                    envelope.type, envelope.topic
                )
                auditSink.report(unknownTypeReport(envelope))
            }
            return
        }

        val matches = registry.subscriptionsFor(eventClass, envelope.topic)
        if (matches.isEmpty()) return

        val event = decode(envelope, eventClass, binaryPayload) ?: return

        for (subscription in matches) {
            if (!subscription.includeSelf && envelope.originInstanceId == instanceId) continue

            try {
                invoke(subscription.listener, subscription.method, event)
            } catch (throwable: Throwable) {
                log.atSevere().withCause(throwable)
                    .log("Event handler %s failed for %s", subscription.displayName, envelope.topic)
                auditSink.report(handlerFailureReport(envelope, subscription.displayName, throwable))
            }
        }
    }

    private fun decode(
        envelope: EventEnvelope,
        eventClass: Class<out SurfBusEvent>,
        binaryPayload: ByteArray?
    ): SurfBusEvent? = TODO_REPLACED_IN_STEP_6

    private fun invoke(listener: Any, method: java.lang.reflect.Method, event: SurfBusEvent) {
        method.invoke(listener, event)
    }

    private fun unknownTypeReport(envelope: EventEnvelope) = AuditReport(
        messageUuid = UUID.randomUUID().toString(),
        kind = AuditKind.UNKNOWN_EVENT_TYPE,
        originService = serviceName,
        originInstance = envelope.originInstanceId,
        reportedByService = serviceName,
        reportedByInstance = instanceId,
        failedAtEpochMs = envelope.publishedAtEpochMs,
        routingKey = envelope.topic,
        messageType = envelope.type,
        payloadEncoding = "JSON",
        payload = envelope.payload?.toByteArray(),
        payloadSizeBytes = envelope.payload?.length ?: 0
    )

    private fun handlerFailureReport(envelope: EventEnvelope, handler: String, throwable: Throwable) =
        AuditReport(
            messageUuid = UUID.randomUUID().toString(),
            kind = AuditKind.EVENT_HANDLER_FAILED,
            originService = serviceName,
            originInstance = envelope.originInstanceId,
            reportedByService = serviceName,
            reportedByInstance = instanceId,
            failedAtEpochMs = envelope.publishedAtEpochMs,
            routingKey = envelope.topic,
            messageType = envelope.type,
            handler = handler,
            attempt = 0,
            terminal = true,
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            stacktrace = throwable.stackTraceToString(),
            payloadEncoding = "JSON",
            payload = envelope.payload?.toByteArray(),
            payloadSizeBytes = envelope.payload?.length ?: 0
        )

    companion object {
        private val log = logger()
    }
}
```

- [ ] **Step 6: `decode` ausschreiben**

`TODO_REPLACED_IN_STEP_6` ist ein bewusster Platzhalter im vorigen Schritt, damit dieser Schritt
die Entscheidung isoliert trifft. Ersetze ihn durch:

```kotlin
    private fun decode(
        envelope: EventEnvelope,
        eventClass: Class<out SurfBusEvent>,
        binaryPayload: ByteArray?
    ): SurfBusEvent? {
        val event = try {
            if (binaryPayload != null) {
                BusEventCodecs.decode(eventClass, binaryPayload)
            } else {
                val payload = envelope.payload
                    ?: error("a JSON event without payload: ${envelope.type} on ${envelope.topic}")
                json.decodeFromString(serializerOf(eventClass), payload) as SurfBusEvent
            }
        } catch (throwable: Throwable) {
            log.atSevere().withCause(throwable)
                .log("Cannot decode %s on %s; discarding", envelope.type, envelope.topic)
            return null
        }

        @OptIn(dev.slne.surf.eventbus.InternalEventBusApi::class)
        event.originInstanceId = envelope.originInstanceId
        @OptIn(dev.slne.surf.eventbus.InternalEventBusApi::class)
        event.publishedAtEpochMs = envelope.publishedAtEpochMs

        return event
    }
```

`serializerOf(eventClass)` und `BusEventCodecs.decode(...)` sind zwei kleine Helfer:
`serializerOf` delegiert an
`dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache` aus Plan 1;
`BusEventCodecs` liest das Companion-Object des Event-Typs und ruft dessen
`BusEventCodec.decode` mit einem `Unpooled.wrappedBuffer(binaryPayload)`. Beide gehören nach
`…-bus-core/…/dispatch/`, jeweils mit einem eigenen Test:

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.event.BusEventCodec
import dev.slne.surf.eventbus.event.SurfBusEvent
import io.netty.buffer.Unpooled
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the `BusEventCodec` an event type declares through its companion object.
 *
 * Absence is the normal case and cached as such: without a codec an event travels as JSON.
 */
object BusEventCodecs {

    private val cache = ConcurrentHashMap<Class<*>, Any>()
    private val none = Any()

    fun codecFor(eventClass: Class<out SurfBusEvent>): BusEventCodec<SurfBusEvent>? {
        val cached = cache.computeIfAbsent(eventClass) { type ->
            val companion = try {
                type.getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
            } catch (_: NoSuchFieldException) {
                null
            }

            if (companion is BusEventCodec<*>) companion else none
        }

        @Suppress("UNCHECKED_CAST")
        return if (cached === none) null else cached as BusEventCodec<SurfBusEvent>
    }

    fun decode(eventClass: Class<out SurfBusEvent>, payload: ByteArray): SurfBusEvent {
        val codec = codecFor(eventClass)
            ?: error("${eventClass.name} arrived on the binary channel but declares no BusEventCodec")

        val buffer = Unpooled.wrappedBuffer(payload)
        try {
            return codec.decode(buffer)
        } finally {
            buffer.release()
        }
    }
}
```

- [ ] **Step 7: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test`
Expected: PASS, alle fünf Dispatcher-Tests plus die vorherigen.

- [ ] **Step 8: Codec-Pfad testen**

Ergänze `EventDispatcherTest` um zwei Fälle, damit der Binärweg nicht nur existiert:

```kotlin
    @Test
    fun `a codec event is decoded from its binary frame`() = runBlocking {
        Recorder.seen.clear()
        val registry = EventSubscriptionRegistry().apply { register(CodecListener) }
        val envelope = EventEnvelope("dispatch.codec", CodecEvent::class.java.name, "lobby-2", 1, null)

        dispatcher(registry).dispatch(envelope, "hello".toByteArray())

        assertEquals(listOf("codec:hello"), Recorder.seen)
    }

    @Test
    fun `a binary event whose type declares no codec is discarded, not thrown`() = runBlocking {
        val registry = EventSubscriptionRegistry().apply { register(ExactListener) }
        val envelope = EventEnvelope("dispatch.test", DispatchEvent::class.java.name, "lobby-2", 1, null)

        dispatcher(registry).dispatch(envelope, "junk".toByteArray())

        assertEquals(0, reports.size)
    }
```

mit:

```kotlin
@BusEvent("dispatch.codec")
class CodecEvent(val text: String) : SurfBusEvent() {
    companion object : BusEventCodec<CodecEvent> {
        override fun encode(buffer: ByteBuf, value: CodecEvent) {
            buffer.writeBytes(value.text.toByteArray())
        }

        override fun decode(buffer: ByteBuf): CodecEvent {
            val bytes = ByteArray(buffer.readableBytes())
            buffer.readBytes(bytes)
            return CodecEvent(String(bytes))
        }
    }
}

private object CodecListener {
    @SurfSubscribe
    fun onEvent(event: CodecEvent) {
        Recorder.seen += "codec:${event.text}"
    }
}
```

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*EventDispatcherTest*'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat: add the event transport seam and the dispatcher

Every match runs, a failing handler is isolated and audited, includeSelf
defaults to dropping own events, and an unresolvable type warns and audits
exactly once. Two-stage type resolution fixes the base-type subscription
that the pre-2.0 Rabbit path silently dropped."
```

---

## Task 7: Query-Verträge

**Files:**
- Create: `…-bus-api/…/query/QueryService.kt`
- Create: `…-bus-api/…/transport/QueryTransport.kt`
- Create: `…-bus-core/…/registry/QueryServiceRegistry.kt`
- Create: `…-bus-core/src/test/…/registry/QueryServiceRegistryTest.kt`

**Interfaces:**
- Produces:
  - `annotation class QueryService(val timeoutMillis: Long = 5000)`
  - `interface QueryTransport { suspend fun connect(contracts: Set<String>, onQuery: suspend (QueryFrame) -> Unit); suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String?; suspend fun answer(frame: QueryFrame, payload: String); suspend fun disconnect() }`
  - `data class QueryFrame(val contract: String, val callable: String, val correlationId: String, val originInstanceId: String, val payload: String)`
  - `class QueryServiceRegistry` mit `register(contract: Class<*>, implementation: Any)`,
    `contracts(): Set<String>`, `implementationOf(contract: String): Any?`, `freeze()`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.query.QueryService
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class QueryServiceRegistryTest {

    @Test
    fun `a registered contract is offered under its fully qualified name`() {
        val registry = QueryServiceRegistry()
        registry.register(Locator::class.java, LocatorImpl)

        assertEquals(setOf(Locator::class.java.name), registry.contracts())
        assertSame(LocatorImpl, registry.implementationOf(Locator::class.java.name))
    }

    @Test
    fun `a contract without the annotation is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QueryServiceRegistry().register(Unannotated::class.java, UnannotatedImpl)
        }

        assertContains(failure.message!!, "@QueryService")
    }

    @Test
    fun `two implementations of the same contract in one process are rejected`() {
        val registry = QueryServiceRegistry()
        registry.register(Locator::class.java, LocatorImpl)

        val failure = assertFailsWith<IllegalStateException> {
            registry.register(Locator::class.java, OtherLocatorImpl)
        }

        assertContains(failure.message!!, Locator::class.java.name)
    }

    @Test
    fun `an implementation that does not implement the contract is rejected`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QueryServiceRegistry().register(Locator::class.java, "not a locator")
        }

        assertContains(failure.message!!, "does not implement")
    }

    @Test
    fun `registration after freeze is rejected`() {
        val registry = QueryServiceRegistry()
        registry.freeze()

        assertFailsWith<IllegalStateException> { registry.register(Locator::class.java, LocatorImpl) }
    }

    @Test
    fun `the timeout of the contract is readable`() {
        assertEquals(2_000L, QueryServiceRegistry().timeoutOf(FastLocator::class.java))
        assertEquals(5_000L, QueryServiceRegistry().timeoutOf(Locator::class.java))
    }
}

@QueryService
private interface Locator {
    suspend fun whereIs(player: String): String?
}

@QueryService(timeoutMillis = 2_000)
private interface FastLocator {
    suspend fun whereIs(player: String): String?
}

private interface Unannotated {
    suspend fun whereIs(player: String): String?
}

private object LocatorImpl : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object OtherLocatorImpl : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object UnannotatedImpl : Unannotated {
    override suspend fun whereIs(player: String): String? = null
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*QueryServiceRegistryTest*'`
Expected: FAIL, „Unresolved reference: QueryServiceRegistry".

- [ ] **Step 3: Annotation und Naht schreiben**

```kotlin
package dev.slne.surf.eventbus.query

/**
 * Marks an interface as a broadcast query contract.
 *
 * A query asks everyone and is answered by whoever is responsible; the first answer wins. It is
 * deliberately **not** RPC: over Pub/Sub, "exactly one instance does this" would be a lie,
 * because every listening process runs the handler unless it abstains.
 *
 * Rules the KSP processor enforces (see plan 3):
 * - every method is `suspend` and returns a **nullable** type. `null` means abstain — no message
 *   is sent at all — so a non-nullable return type could not express "not mine".
 * - `@FireAndForget` is rejected: a question without an answer is an event.
 *
 * There is no `service` attribute: a query addresses nobody. Its channel follows the contract's
 * fully qualified name.
 *
 * @property timeoutMillis how long the caller waits before the call returns `null`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryService(val timeoutMillis: Long = 5_000)
```

```kotlin
package dev.slne.surf.eventbus.transport

/**
 * One question or answer on the wire.
 */
data class QueryFrame(
    val contract: String,
    val callable: String,
    val correlationId: String,
    val originInstanceId: String,
    val payload: String
)

/**
 * The seam between the bus and the Redis query channels.
 *
 * One real implementation, same reasoning as [EventTransport].
 */
interface QueryTransport {

    suspend fun connect(contracts: Set<String>, onQuery: suspend (QueryFrame) -> Unit)

    /**
     * Publishes the question and waits for the first answer.
     *
     * @return the answer payload, or `null` when nobody answered within [timeoutMillis]. No
     *   exception: in a broadcast, "nobody is responsible" is a normal outcome.
     */
    suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String?

    /** Sends an answer to the asker named in [frame]. Called only when the handler abstained not. */
    suspend fun answer(frame: QueryFrame, payload: String)

    suspend fun disconnect()
}
```

- [ ] **Step 4: Registry schreiben**

```kotlin
package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.query.QueryService
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the query contracts this process offers.
 *
 * One implementation per contract per process: two would both answer the same question from the
 * same instance, and which of them wins would be a race.
 */
class QueryServiceRegistry {

    private val implementations = ConcurrentHashMap<String, Any>()

    @Volatile
    private var frozen = false

    fun register(contract: Class<*>, implementation: Any) {
        check(!frozen) { "registration is closed: freeze() has already run" }

        require(contract.getAnnotation(QueryService::class.java) != null) {
            "${contract.name} is not annotated with @QueryService"
        }
        require(contract.isInstance(implementation)) {
            "${implementation.javaClass.name} does not implement ${contract.name}"
        }

        val previous = implementations.putIfAbsent(contract.name, implementation)
        check(previous == null) {
            "${contract.name} is already offered by ${previous?.javaClass?.name} in this process"
        }
    }

    fun freeze() {
        frozen = true
    }

    fun contracts(): Set<String> = implementations.keys.toSet()

    fun implementationOf(contract: String): Any? = implementations[contract]

    fun timeoutOf(contract: Class<*>): Long =
        contract.getAnnotation(QueryService::class.java)?.timeoutMillis
            ?: error("${contract.name} is not annotated with @QueryService")
}
```

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*QueryServiceRegistryTest*'`
Expected: PASS, alle sechs Tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add @QueryService, the query transport seam and its registry

A query addresses nobody, so the annotation has no service attribute; the
channel follows the contract's FQCN. One implementation per contract per
process."
```

---

## Task 8: `SurfEventBus`, Builder und Lebenszyklus

**Files:**
- Create: `…-bus-api/…/SurfEventBus.kt`, `SurfEventBusBuilder.kt`
- Create: `…-bus-core/…/SurfEventBusImpl.kt`
- Create: `…-bus-core/src/test/…/FakeEventTransport.kt`, `FakeQueryTransport.kt`
- Create: `…-bus-core/src/test/…/SurfEventBusLifecycleTest.kt`

**Interfaces:**
- Consumes: alles Vorherige, `LegacyEnvironmentGuard` aus Plan 1 Task 5.
- Produces: die Fläche, gegen die Plan 3 und 4 arbeiten:
  - `SurfEventBus.builder(serviceName: String, dataPath: Path): SurfEventBusBuilder`
  - `SurfEventBusBuilder`: `instanceName(String)`, `serializers(SerializersModule)`,
    `withRabbit()`, `withRedis()`, `build(): SurfEventBus`
  - `SurfEventBus`: `subscribe(listener: Any)`, `registerService(contract: KClass<T>, implementation: T)`,
    `publish(event: SurfBusEvent)`, `query(contract: KClass<T>): T`, `rpc(contract: KClass<T>): T`,
    `freeze()`, `connect()`, `freezeAndConnect()`, `disconnect()`, `rabbit`, `redis`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SurfEventBusLifecycleTest {

    private val dataPath = Files.createTempDirectory("bus-lifecycle")

    private fun builder() = SurfEventBus.builder("surf-test", dataPath).instanceName("lobby-1")

    @Test
    fun `a builder without any transport fails`() {
        val failure = assertFailsWith<IllegalStateException> { builder().build() }

        assertContains(failure.message!!, "withRabbit")
        assertContains(failure.message!!, "withRedis")
    }

    @Test
    fun `publish without the redis transport names the missing builder call`() = runBlocking {
        val bus = builder().withRabbit().build()

        val failure = assertFailsWith<IllegalStateException> { bus.publish(LifecycleEvent("x")) }

        assertContains(failure.message!!, ".withRedis()")
    }

    @Test
    fun `subscribing without the redis transport fails at freeze, naming the handler`() {
        val bus = builder().withRabbit().build()
        bus.subscribe(LifecycleListener)

        val failure = assertFailsWith<IllegalStateException> { bus.freeze() }

        assertContains(failure.message!!, "LifecycleListener#onEvent")
        assertContains(failure.message!!, ".withRedis()")
    }

    @Test
    fun `rpc without the rabbit transport names the missing builder call`() {
        val bus = builder().withRedis(FakeEventTransport(), FakeQueryTransport()).build()

        val failure = assertFailsWith<IllegalStateException> { bus.rabbit }

        assertContains(failure.message!!, ".withRabbit()")
    }

    @Test
    fun `registration after freeze is rejected`() {
        val bus = builder().withRedis(FakeEventTransport(), FakeQueryTransport()).build()
        bus.freeze()

        assertFailsWith<IllegalStateException> { bus.subscribe(LifecycleListener) }
    }

    @Test
    fun `connect subscribes with the topics of the registry`() = runBlocking {
        val transport = FakeEventTransport()
        val bus = builder().withRedis(transport, FakeQueryTransport()).build()
        bus.subscribe(LifecycleListener)
        bus.freezeAndConnect()

        assertEquals(setOf("lifecycle.test"), transport.exactTopics)
        assertEquals(emptySet(), transport.wildcardPatterns)

        bus.disconnect()
        assertEquals(true, transport.disconnected)
    }

    @Test
    fun `a published event reaches the transport with origin and timestamp stamped`() = runBlocking {
        val transport = FakeEventTransport()
        val bus = builder().withRedis(transport, FakeQueryTransport()).build()
        bus.subscribe(LifecycleListener)
        bus.freezeAndConnect()

        bus.publish(LifecycleEvent("payload"))

        val envelope = transport.published.single().first
        assertEquals("lifecycle.test", envelope.topic)
        assertEquals("lobby-1", envelope.originInstanceId)
        assertEquals(true, envelope.publishedAtEpochMs > 0)

        bus.disconnect()
    }

    @Test
    fun `a legacy environment variable fails the build`() {
        // LegacyEnvironmentGuard is wired into build(); this asserts the wiring, not the guard,
        // which has its own tests in surf-eventbus-common.
        val failure = assertFailsWith<IllegalStateException> {
            builder().withRabbit().build(environment = mapOf("SURF_RABBITMQ_HOST" to "x"))
        }

        assertContains(failure.message!!, "SURF_EVENTBUS_RABBITMQ_HOST")
    }
}

@Serializable
@BusEvent("lifecycle.test")
class LifecycleEvent(val value: String) : SurfBusEvent()

private object LifecycleListener {
    @SurfSubscribe
    fun onEvent(event: LifecycleEvent) = Unit
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*SurfEventBusLifecycleTest*'`
Expected: FAIL, „Unresolved reference: SurfEventBus".

- [ ] **Step 3: Fake-Transports schreiben**

```kotlin
package dev.slne.surf.eventbus.core

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryFrame
import dev.slne.surf.eventbus.transport.QueryTransport
import java.util.concurrent.CopyOnWriteArrayList

class FakeEventTransport : EventTransport {

    var exactTopics: Set<String> = emptySet()
        private set
    var wildcardPatterns: Set<String> = emptySet()
        private set
    var disconnected = false
        private set

    val published = CopyOnWriteArrayList<Pair<EventEnvelope, ByteArray?>>()

    private var onEvent: (suspend (EventEnvelope, ByteArray?) -> Unit)? = null

    override suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        this.exactTopics = exactTopics
        this.wildcardPatterns = wildcardPatterns
        this.onEvent = onEvent
    }

    override suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        published += envelope to binaryPayload
    }

    override suspend fun disconnect() {
        disconnected = true
    }

    /** Feeds an envelope back in, as if the broker had delivered it. */
    suspend fun deliver(envelope: EventEnvelope, binaryPayload: ByteArray? = null) {
        onEvent?.invoke(envelope, binaryPayload)
    }
}

class FakeQueryTransport : QueryTransport {

    var contracts: Set<String> = emptySet()
        private set
    val asked = CopyOnWriteArrayList<QueryFrame>()
    val answered = CopyOnWriteArrayList<Pair<QueryFrame, String>>()
    var nextAnswer: String? = null

    override suspend fun connect(contracts: Set<String>, onQuery: suspend (QueryFrame) -> Unit) {
        this.contracts = contracts
    }

    override suspend fun ask(frame: QueryFrame, timeoutMillis: Long): String? {
        asked += frame
        return nextAnswer
    }

    override suspend fun answer(frame: QueryFrame, payload: String) {
        answered += frame to payload
    }

    override suspend fun disconnect() = Unit
}
```

- [ ] **Step 4: `SurfEventBus` und Builder schreiben**

Die Fläche ist bewusst klein. `rpc` und `query` geben in dieser Etappe Proxies zurück, die vom
KSP-Descriptor kommen; solange Plan 3 den Generator nicht erweitert hat, wirft `query` eine
`NotImplementedError` mit klarem Text — **nicht** stillschweigend `null`.

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.event.SurfBusEvent
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The single entry point to distributed communication.
 *
 * Three promises, three verbs: [publish] notifies everyone over Redis, [rpc] calls one known
 * place over RabbitMQ, [query] asks everyone and is answered by whoever is responsible. Which
 * transport carries what follows from the verb, not from configuration.
 *
 * The name says `EventBus` while the bus carries more than events; it follows the artifact and
 * package root, which are both `eventbus`.
 */
interface SurfEventBus {

    /** Registers `@SurfSubscribe` methods on [listener]. Requires the Redis transport. */
    fun subscribe(listener: Any)

    /** Offers an `@RpcService` or `@QueryService` contract. The descriptor decides which. */
    fun <T : Any> registerService(contract: KClass<T>, implementation: T)

    /** Publishes to every matching subscriber. Requires the Redis transport. */
    suspend fun publish(event: SurfBusEvent)

    /** A client proxy for a `@QueryService` contract. Requires the Redis transport. */
    fun <T : Any> query(contract: KClass<T>): T

    /** A client proxy for an `@RpcService` contract. Requires the RabbitMQ transport. */
    fun <T : Any> rpc(contract: KClass<T>): T

    /** Closes registration and validates it. */
    fun freeze()

    suspend fun connect()

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    suspend fun disconnect()

    /** The RabbitMQ-only surface. Throws when the transport is not enabled. */
    val rabbit: Any

    /** The Redis-only surface: sync structures, caches. Throws when the transport is not enabled. */
    val redis: Any

    companion object {
        fun builder(serviceName: String, dataPath: Path): SurfEventBusBuilder =
            SurfEventBusBuilder(serviceName, dataPath)
    }
}

inline fun <reified T : Any> SurfEventBus.registerService(implementation: T) =
    registerService(T::class, implementation)

inline fun <reified T : Any> SurfEventBus.query(): T = query(T::class)

inline fun <reified T : Any> SurfEventBus.rpc(): T = rpc(T::class)

inline fun <reified L : Any> SurfEventBus.subscribe() {
    val instance = L::class.objectInstance
        ?: error("${L::class.simpleName} is not a Kotlin object; pass the instance to subscribe(listener)")
    subscribe(instance)
}
```

`rabbit` und `redis` sind hier `Any`, weil `surf-eventbus-bus-api` die Transport-APIs nicht kennen
darf. Plan 4 fügt in den Aggregat-Modulen typisierte Erweiterungen hinzu:
`val SurfEventBus.rabbit: SurfRabbitApi get() = (this.rabbit as SurfRabbitApi)`. Das ist der Preis
dafür, dass die Bus-API unterhalb der Transports liegt.

Der Builder samt Freischaltungslogik gehört ebenfalls nach `-bus-api`:

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

class SurfEventBusBuilder internal constructor(
    private val serviceName: String,
    private val dataPath: Path
) {
    private var instanceName: String? = null
    private var serializers: SerializersModule = SerializersModule { }
    private var rabbitEnabled = false
    private var eventTransport: EventTransport? = null
    private var queryTransport: QueryTransport? = null

    fun instanceName(name: String) = apply { instanceName = name }

    fun serializers(module: SerializersModule) = apply { serializers = module }

    /** Enables RPC and the audit path. */
    fun withRabbit() = apply { rabbitEnabled = true }

    /** Enables events, queries, sync structures and caches. */
    fun withRedis() = apply {
        // The real transports are wired by surf-eventbus-redis-core through a ServiceLoader in
        // plan 3. Until then the overload below is what tests use.
        eventTransport = RedisTransportLocator.event()
        queryTransport = RedisTransportLocator.query()
    }

    /** Test seam: enables Redis with explicit transports. */
    fun withRedis(event: EventTransport, query: QueryTransport) = apply {
        eventTransport = event
        queryTransport = query
    }

    fun build(environment: Map<String, String>? = null): SurfEventBus { /* see next step */ }
}
```

- [ ] **Step 5: `SurfEventBusImpl` und `build()` schreiben**

`build()` ruft die Guard und prüft die Freischaltung:

```kotlin
    fun build(environment: Map<String, String>? = null): SurfEventBus {
        LegacyEnvironmentGuard.check(
            if (environment == null) EnvironmentVariables.system else EnvironmentVariables.from(environment)
        )

        check(rabbitEnabled || eventTransport != null) {
            "a bus needs at least one transport: add .withRabbit(), .withRedis(), or both to " +
                    "SurfEventBus.builder(\"$serviceName\", …)"
        }

        return SurfEventBusImpl(
            serviceName = serviceName,
            instanceId = instanceName ?: "$serviceName-${UUID.randomUUID()}",
            dataPath = dataPath,
            serializers = serializers,
            rabbitEnabled = rabbitEnabled,
            eventTransport = eventTransport,
            queryTransport = queryTransport
        )
    }
```

`SurfEventBusImpl` hält Registry, Dispatcher und die Fehlermeldungen. Die Meldung ist Teil der
API und deshalb wörtlich festgelegt:

```kotlin
    private fun requireRedis(verb: String): EventTransport = eventTransport ?: error(
        """
        $verb requires the Redis transport,
        but this bus was built without it.
        -> add .withRedis() to SurfEventBus.builder(...)
        """.trimIndent()
    )
```

`freeze()` prüft, dass jedes Abonnement einen Transport hat, und nennt den Handler:

```kotlin
    override fun freeze() {
        if (eventTransport == null && !eventRegistry.isEmpty()) {
            val handlers = eventRegistry.subscriptions().joinToString(", ") { it.displayName }
            error(
                """
                These @SurfSubscribe handlers need the Redis transport: $handlers
                -> add .withRedis() to SurfEventBus.builder(...)
                """.trimIndent()
            )
        }

        eventRegistry.freeze()
        queryRegistry.freeze()
        frozen = true
    }
```

`connect()` verbindet, was freigeschaltet ist, und schließt bei einem Fehler den anderen:

```kotlin
    override suspend fun connect() {
        check(frozen) { "connect() before freeze(): a message could hit a half-registered handler" }

        try {
            eventTransport?.connect(
                exactTopics = eventRegistry.exactTopics(),
                wildcardPatterns = eventRegistry.wildcardPatterns(),
                onEvent = dispatcher::dispatch
            )
            queryTransport?.connect(queryRegistry.contracts()) { frame -> queryDispatcher.dispatch(frame) }
        } catch (throwable: Throwable) {
            // Half connected is harder to diagnose than not started.
            runCatching { eventTransport?.disconnect() }
            runCatching { queryTransport?.disconnect() }
            throw throwable
        }
    }
```

`publish()` stempelt Herkunft und Zeit und wählt die Kanalfamilie:

```kotlin
    override suspend fun publish(event: SurfBusEvent) {
        val transport = requireRedis("bus.publish()")
        val topic = EventTopics.topicOf(event.javaClass)
        val codec = BusEventCodecs.codecFor(event.javaClass)

        val envelope = EventEnvelope(
            topic = topic,
            type = event.javaClass.name,
            originInstanceId = instanceId,
            publishedAtEpochMs = System.currentTimeMillis(),
            payload = if (codec == null) json.encodeToString(serializerOf(event.javaClass), event) else null
        )

        transport.publish(envelope, codec?.let { encodeBinary(it, event) })
    }
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*SurfEventBusLifecycleTest*'`
Expected: PASS, alle acht Tests.

Run: `./gradlew test`
Expected: SUCCESS.

- [ ] **Step 7: Purity und ABI**

Run: `./gradlew :surf-eventbus-common:test`
Expected: PASS — `CommonPurityTest` bestätigt, dass beide Bus-Module brokerfrei sind.

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add SurfEventBus, its builder and the lifecycle

Transport enablement is code, not configuration: a verb whose transport is
missing fails loudly and names the builder call. freeze() rejects a
subscription without its transport and names the handler."
```

---

## Self-Review

**Spec-Abdeckung (Spec → Task):**

| Spec | Task |
|---|---|
| Etappe 4: `SurfBusEvent`, `@BusEvent`, `EventTopics` | 1, 2 |
| Etappe 4: `BusEventCodec` in `-bus-api` | 2, 6 |
| Etappe 4: `@SurfSubscribe(topic, includeSelf)` | 3 |
| Etappe 4: `@QueryService` | 7 |
| Etappe 4: `AuditReport`/`AuditKind`/`AuditSink` | 5 |
| Etappe 4: Builder mit `withRabbit()`/`withRedis()` | 8 |
| Etappe 5: Registry | 3, 7 |
| Etappe 5: Dispatcher, Typauflösung | 6 |
| Etappe 5: Envelope | 4 |
| Etappe 5: Lebenszyklus, Freischaltungsprüfung | 8 |
| Verifikation: Registry-Validierung, Envelope-Rundlauf, Fehlermeldungstexte | 3, 4, 8 |
| Verifikation: `-common` brokerfrei, erweitert | 1 Step 6 |

**Nicht hier:** die echten Transports (Plan 3), KSP-Generierung für `@QueryService` und
`@FireAndForget` (Plan 3), die typisierten `bus.rabbit`/`bus.redis`-Erweiterungen und die
Aggregat-Module (Plan 4), der Audit-Meldeweg über RabbitMQ (Plan 4).

**Typkonsistenz:** `EventTransport.connect(exactTopics, wildcardPatterns, onEvent)` in Task 6
entspricht dem Aufruf in Task 8 Step 5; `QueryTransport.ask(frame, timeoutMillis)` gibt `String?`
zurück, und `QueryServiceRegistry.timeoutOf(Class<*>)` liefert das `timeoutMillis` dazu.
`EventDispatcher(registry, instanceId, auditSink, json, typeResolver, serviceName)` ist die
Signatur, die Task 8 benutzt — sechs Parameter, der letzte mit Default.

**Bewusst offen gelassen:** `RedisTransportLocator` in Task 8 Step 4 ist eine Vorwärtsreferenz auf
Plan 3, wo der Redis-Transport über `ServiceLoader` gefunden wird. Bis dahin trägt die
Test-Überladung `withRedis(event, query)` die Absicherung. Wer Plan 3 beginnt, ersetzt den
Locator zuerst.
