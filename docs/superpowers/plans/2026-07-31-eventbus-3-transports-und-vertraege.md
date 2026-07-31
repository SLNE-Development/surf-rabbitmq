# surf-eventbus 3: Transports und Verträge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** use
> `superpowers:subagent-driven-development` — this repository's owner has forbidden subagents.

**Goal:** Die Nähte aus Plan 2 mit den echten Transports füllen und die drei doppelten
Mechanismen löschen: Redis trägt Events und Queries, RabbitMQ trägt RPC inklusive
`@FireAndForget`, und die untypisierte Paket-API sowie die alten Event- und
Request/Response-APIs verschwinden.

**Architecture:** Jede Löschung kommt **nach** ihrem Ersatz, damit keine Etappe unübersetzbar
endet. Zuerst der Redis-Event-Transport, dann fällt Rabbits Event-Teil; zuerst `@FireAndForget`,
dann fällt die Paket-API; zuerst `@QueryService`, dann fällt `RedisRequest`. Der KSP-Prozessor
generiert am Ende für zwei Vertragsarten.

**Tech Stack:** Redisson 4.6.1 (Reactive Topics), `kotlinx.serialization` JSON, Netty `ByteBuf`,
KSP mit KotlinPoet, amqp-client 5.34.0, Testcontainers (Redis, RabbitMQ), JUnit 5.

## Global Constraints

- **Voraussetzung:** Plan 1 und Plan 2 sind abgeschlossen, Build grün, `CommonPurityTest` grün.
- **Kanalnamen** exakt: `surf.eventbus.json.<topic>`, `surf.eventbus.bin.<topic>`,
  `surf.eventbus.query.<vertrag-fqcn>`, `surf.eventbus.reply.<instanceId>`.
- **`@QueryService`-Methoden** sind `suspend` und haben einen **nullable** Rückgabetyp. `null`
  heißt Abstinenz: es geht keine Nachricht raus. Timeout liefert `null`, keine Ausnahme.
  Handler-Ausnahmen reisen **nicht** zum Aufrufer.
- **`@FireAndForget`-Methoden** haben Rückgabetyp `Unit` und benutzen keine Reply-Queue.
- **Die interne Paket-Schicht bleibt.** `RpcCallRequestPacket`, `RpcCallResponsePacket`, Chunking
  und Properties-Injektion sind Interna und werden nicht angefasst. Gelöscht wird nur, was
  Anwendungscode anfassen musste.
- **Docker ist auf dieser Maschine nicht erreichbar.** Jeder Test mit `@RequiresDocker` wird
  geschrieben und **nicht** ausgeführt; Status „nicht verifiziert".
- **Kein fremdes Repository wird verändert.**

---

## File Structure

| Datei | Verantwortung |
|---|---|
| `…-redis-core/…/redis/bus/RedisEventTransport.kt` | `EventTransport` über zwei Redisson-Topic-Familien |
| `…-redis-core/…/redis/bus/RedisQueryTransport.kt` | `QueryTransport` mit Korrelation und Timeout |
| `…-redis-core/…/redis/bus/RedisTransportProvider.kt` | `ServiceLoader`-Fund für den Builder |
| `…-bus-api/…/transport/RedisTransportLocator.kt` | `ServiceLoader`-Aufruf, ersetzt die Vorwärtsreferenz aus Plan 2 |
| `…-bus-core/…/dispatch/QueryDispatcher.kt` | Zustellung einer Frage an den lokalen Anbieter |
| `…-rabbitmq-api/…/rpc/FireAndForget.kt` | Markierung an einer RPC-Methode |
| `…-ksp/…/processor/query/*` | Generierung für `@QueryService` |
| `…-ksp/…/processor/rpc/*` | erweitert um `@FireAndForget` |

**Gelöscht:**

| Datei/Typ | Ersetzt durch |
|---|---|
| `…redis/event/RedisEvent.kt`, `OnRedisEvent.kt`, `RedisEventBus.kt`, `RedisEventBusImpl.kt`, `RedisEventInvoker.kt`, `RedisEventCodec.kt`, `RedisEventCodecRegistrar.kt` | `SurfBusEvent`, `@SurfSubscribe`, `SurfEventBus`, `BusEventCodec` |
| `…redis/request/**` (7 Dateien), `RequestResponseBusImpl.kt`, `RedisRequestHandlerInvoker.kt` | `@QueryService` |
| `…rabbitmq/api/event/**` (4 Dateien) | `@BusEvent`, `@SurfSubscribe` |
| `…rabbitmq/core/event/**` (4 Dateien) | Registry und Dispatcher aus `-bus-core` |
| `…rabbitmq/api/packet/RabbitRequestPacket.kt`, `RabbitResponsePacket.kt`, `packet/standard/**` (6 Dateien) | Rückgabetypen der Vertragsmethode |
| `…rabbitmq/api/handler/RabbitHandler.kt` | `@RpcService`-Methoden |
| `RabbitTopology.EVENTS_EXCHANGE`, `sharedEventQueue`, `instanceEventQueue`, `QueueArguments.sharedEventQueue` | nichts — Events sind Redis |

---

## Task 1: Redis-Event-Transport

**Files:**
- Create: `…-redis-core/src/main/kotlin/dev/slne/surf/eventbus/redis/bus/RedisEventTransport.kt`
- Create: `…-redis-core/…/bus/RedisChannels.kt`
- Create: `…-redis-core/…/bus/RedisTransportProvider.kt`
- Create: `…-bus-api/…/transport/RedisTransportLocator.kt`
- Create: `…-redis-core/src/test/…/bus/RedisChannelsTest.kt`
- Create: `…-redis-core/src/test/…/bus/RedisEventTransportTest.kt` (`@RequiresDocker`)

**Interfaces:**
- Consumes: `EventTransport`, `EventEnvelope` aus Plan 2.
- Produces:
  - `object RedisChannels { fun json(topic: String): String; fun binary(topic: String): String; fun query(contract: String): String; fun reply(instanceId: String): String; const val JSON_PATTERN: String; const val BINARY_PATTERN: String }`
  - `class RedisEventTransport(redis: RedisApi, json: Json) : EventTransport`
  - `interface RedisTransportProvider { fun event(redis: RedisApi, json: Json): EventTransport; fun query(...): QueryTransport }`
  - `object RedisTransportLocator { fun event(): EventTransport; fun query(): QueryTransport }`

- [ ] **Step 1: Failing test für die Kanalnamen**

```kotlin
package dev.slne.surf.eventbus.redis.bus

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisChannelsTest {

    @Test
    fun `channel names carry their family in the prefix`() {
        assertEquals("surf.eventbus.json.faction.disbanded", RedisChannels.json("faction.disbanded"))
        assertEquals("surf.eventbus.bin.faction.disbanded", RedisChannels.binary("faction.disbanded"))
        assertEquals("surf.eventbus.query.dev.example.Locator", RedisChannels.query("dev.example.Locator"))
        assertEquals("surf.eventbus.reply.lobby-3", RedisChannels.reply("lobby-3"))
    }

    @Test
    fun `the json pattern does not catch the binary family`() {
        // Redis glob: * crosses dots. Distinct prefixes are what keep the families apart.
        assertTrue(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.json("a.b")))
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.binary("a.b")))
        assertTrue(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.binary("a.b")))
        assertFalse(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.json("a.b")))
    }

    @Test
    fun `neither pattern catches the query or reply families`() {
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.query("dev.example.Locator")))
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.reply("lobby-3")))
        assertFalse(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.query("dev.example.Locator")))
    }

    private fun globMatches(pattern: String, channel: String): Boolean =
        Regex(pattern.replace(".", "\\.").replace("*", ".*")).matches(channel)
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*RedisChannelsTest*'`
Expected: FAIL, „Unresolved reference: RedisChannels".

- [ ] **Step 3: Kanalnamen schreiben**

```kotlin
package dev.slne.surf.eventbus.redis.bus

/**
 * Every Redis channel name the bus uses.
 *
 * Two event families with distinct prefixes rather than one family with an encoding marker: a
 * Redisson `Topic` is bound to exactly one codec, and a pattern subscription must not catch the
 * other family — Redis glob crosses dots, so `surf.eventbus.*` would.
 */
object RedisChannels {

    private const val PREFIX = "surf.eventbus."

    const val JSON_PATTERN = PREFIX + "json.*"
    const val BINARY_PATTERN = PREFIX + "bin.*"

    fun json(topic: String): String = PREFIX + "json." + topic

    fun binary(topic: String): String = PREFIX + "bin." + topic

    fun query(contract: String): String = PREFIX + "query." + contract

    fun reply(instanceId: String): String = PREFIX + "reply." + instanceId

    /** The topic a JSON or binary channel name belongs to, or `null` for another family. */
    fun topicOf(channel: String): String? = when {
        channel.startsWith(PREFIX + "json.") -> channel.removePrefix(PREFIX + "json.")
        channel.startsWith(PREFIX + "bin.") -> channel.removePrefix(PREFIX + "bin.")
        else -> null
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*RedisChannelsTest*'`
Expected: PASS.

- [ ] **Step 5: Transport schreiben**

`RedisEventTransport` benutzt `RedisApi.redissonReactive.getTopic(...)` genau wie
`RedisEventBusImpl` heute, aber pro Topic statt global:

```kotlin
package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.redis.RedisApi
import dev.slne.surf.eventbus.transport.EventTransport
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.serialization.json.Json
import org.redisson.api.listener.MessageListener
import org.redisson.client.codec.ByteArrayCodec
import org.redisson.client.codec.StringCodec
import reactor.core.Disposable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Carries events over Redis Pub/Sub.
 *
 * Wildcard-free subscriptions take an exact channel and let the broker filter. Wildcard
 * subscriptions take a pattern subscription over both families and match locally with
 * `EventTopics`, because Redis glob and the documented topic semantics disagree and the
 * documented one wins.
 */
class RedisEventTransport(
    private val redis: RedisApi,
    private val json: Json
) : EventTransport {

    private val disposables = CopyOnWriteArrayList<Disposable>()

    override suspend fun connect(
        exactTopics: Set<String>,
        wildcardPatterns: Set<String>,
        onEvent: suspend (EventEnvelope, ByteArray?) -> Unit
    ) {
        for (topic in exactTopics) {
            subscribeJson(RedisChannels.json(topic), onEvent)
            subscribeBinary(RedisChannels.binary(topic), onEvent)
        }

        if (wildcardPatterns.isNotEmpty()) {
            subscribeJsonPattern(RedisChannels.JSON_PATTERN, onEvent)
            subscribeBinaryPattern(RedisChannels.BINARY_PATTERN, onEvent)
        }
    }

    override suspend fun publish(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        if (binaryPayload == null) {
            redis.redissonReactive
                .getTopic(RedisChannels.json(envelope.topic), StringCodec.INSTANCE)
                .publish(envelope.encodeToString(json))
                .awaitFirstOrNull()
        } else {
            // The envelope travels as a header frame in front of the payload so the receiver
            // knows the type before it decodes: length-prefixed JSON, then the binary body.
            redis.redissonReactive
                .getTopic(RedisChannels.binary(envelope.topic), ByteArrayCodec.INSTANCE)
                .publish(BinaryFrame.encode(envelope, binaryPayload, json))
                .awaitFirstOrNull()
        }
    }

    override suspend fun disconnect() {
        disposables.forEach { runCatching { it.dispose() } }
        disposables.clear()
    }

    companion object {
        private val log = logger()
    }
}
```

Die vier `subscribe*`-Helfer und `BinaryFrame` gehören in dieselbe Datei beziehungsweise nach
`BinaryFrame.kt`. `BinaryFrame` ist bewusst schlicht: 4-Byte-Länge, JSON-Envelope, Rest ist
Nutzlast — und bekommt seinen eigenen Rundlauftest:

```kotlin
package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/**
 * A binary event on the wire: envelope length, envelope JSON, payload.
 *
 * The envelope stays JSON even on the binary channel. It is small, it changes rarely, and a
 * receiver must be able to read the type before it can pick a codec to decode the body with.
 */
object BinaryFrame {

    fun encode(envelope: EventEnvelope, payload: ByteArray, json: Json): ByteArray {
        val header = envelope.encodeToString(json).toByteArray()
        return ByteBuffer.allocate(4 + header.size + payload.size)
            .putInt(header.size)
            .put(header)
            .put(payload)
            .array()
    }

    fun decode(frame: ByteArray, json: Json): Pair<EventEnvelope, ByteArray> {
        require(frame.size >= 4) { "binary frame shorter than its length prefix" }

        val buffer = ByteBuffer.wrap(frame)
        val headerSize = buffer.int
        require(headerSize in 0..(frame.size - 4)) { "binary frame declares a header of $headerSize bytes" }

        val header = ByteArray(headerSize).also(buffer::get)
        val payload = ByteArray(buffer.remaining()).also(buffer::get)

        return EventEnvelope.decodeFromString(json, String(header)) to payload
    }
}
```

- [ ] **Step 6: `BinaryFrame`-Rundlauftest schreiben und laufen lassen**

```kotlin
package dev.slne.surf.eventbus.redis.bus

import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BinaryFrameTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val envelope = EventEnvelope("a.b", "dev.example.T", "lobby-1", 7, null)

    @Test
    fun `a frame survives a round trip`() {
        val payload = byteArrayOf(1, 2, 3)

        val (restoredEnvelope, restoredPayload) = BinaryFrame.decode(
            BinaryFrame.encode(envelope, payload, json), json
        )

        assertEquals(envelope, restoredEnvelope)
        assertContentEquals(payload, restoredPayload)
    }

    @Test
    fun `an empty payload survives a round trip`() {
        val (_, restored) = BinaryFrame.decode(BinaryFrame.encode(envelope, ByteArray(0), json), json)

        assertEquals(0, restored.size)
    }

    @Test
    fun `a truncated frame fails instead of reading past its end`() {
        assertFailsWith<IllegalArgumentException> { BinaryFrame.decode(byteArrayOf(1, 2), json) }
    }

    @Test
    fun `a frame with an impossible header size fails`() {
        val bogus = byteArrayOf(0x7F, 0x7F, 0x7F, 0x7F, 1, 2)

        assertFailsWith<IllegalArgumentException> { BinaryFrame.decode(bogus, json) }
    }
}
```

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*BinaryFrameTest*'`
Expected: PASS.

- [ ] **Step 7: Locator und Provider schreiben**

```kotlin
package dev.slne.surf.eventbus.transport

import java.util.ServiceLoader

/**
 * Finds the Redis transports on the classpath.
 *
 * `withRedis()` on the builder must not reference `surf-eventbus-redis-core` — the bus API sits
 * below the transports. A ServiceLoader is the smallest thing that inverts that dependency.
 */
object RedisTransportLocator {

    private val provider by lazy {
        ServiceLoader.load(RedisTransportProvider::class.java).firstOrNull()
            ?: error(
                "withRedis() was called but surf-eventbus-redis-core is not on the runtime " +
                        "classpath. Add runtimeOnly(\"dev.slne.surf.eventbus:surf-eventbus-core\")."
            )
    }

    fun event(): EventTransport = provider.event()

    fun query(): QueryTransport = provider.query()
}

interface RedisTransportProvider {
    fun event(): EventTransport
    fun query(): QueryTransport
}
```

Die Implementierung in `…-redis-core` trägt `@AutoService(RedisTransportProvider::class)` und
baut ihre Transports über `RedisApi`.

- [ ] **Step 8: Integrationstest gegen echten Redis schreiben**

`…-redis-core/src/test/…/bus/RedisEventTransportTest.kt`, markiert mit `@RequiresDocker` (die
Annotation existiert bereits in `…-rabbitmq-core/src/test/.../common/testing/RequiresDocker.kt`
und wandert in Plan 4 Task 5 nach `surf-eventbus-test`; solange hier eine Kopie im
Redis-Testquellbaum). Inhalt: die Fälle 1–5 und 12a–12c der Event-Suite aus dem Spec — drei
Instanzen bekommen ein Broadcast-Event; ein wildcardfreies Topic trifft nur passende Abonnenten;
`*` trifft ein Segment; `#` trifft null oder mehr; zwei überlappende Muster feuern beide; ein
Codec-Event kommt binär an; ein Wildcard-Abonnement bekommt JSON und Codec.

- [ ] **Step 9: Ausführung dokumentieren, nicht behaupten**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*RedisEventTransportTest*'`
Expected: SKIPPED — kein Docker-Daemon. In
`docs/superpowers/notes/2026-07-31-fundament-verification.md` als „geschrieben, nicht verifiziert"
nachtragen, mit der Testklasse namentlich.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat: add the Redis event transport with two channel families

Wildcard-free subscriptions let the broker filter; wildcard subscriptions
match locally because Redis glob crosses dots. BinaryFrame keeps the
envelope readable in front of a codec payload."
```

---

## Task 2: Alte Redis-Event-API löschen

**Files:**
- Delete: `…redis/event/RedisEvent.kt`, `OnRedisEvent.kt`, `RedisEventBus.kt`,
  `RedisEventCodec.kt`, `RedisEventCodecRegistrar.kt`, `RedisEventBusImpl.kt`,
  `RedisEventInvoker.kt`, `CustomEventPacketCodec.kt`
- Modify: `…redis/RedisApi.kt` (`publishEvent`, `subscribeToEvents` entfallen)
- Modify: `…redis/event/EventCodecRegistry.kt` (routet über Topic statt `eventId`)
- Move: `CustomEventPacketCodecTest`, `EventCodecRegistryTest`, `EventCodecRegistryLincheckTest`
  auf die neuen Typen
- Modify: `…-redis-core/src/jmh/…/event/EventTransportBenchmark.kt`

**Interfaces:**
- Consumes: `RedisEventTransport` aus Task 1, `BusEventCodec` aus Plan 2.
- Produces: `EventCodecRegistry` schlüsselt über den Event-Typ statt über `eventId`; der
  Benchmark misst `RedisEventTransport` statt `RedisEventBusImpl`.

- [ ] **Step 1: Lincheck-Test auf den neuen Schlüssel umstellen**

`EventCodecRegistryLincheckTest` ist der wertvollste bestehende Test: die Registry wird jetzt
zusätzlich vom Event-Dispatcher gelesen. Erst den Test umschreiben, dann die Registry.

Im Test `eventId`-Schlüssel durch den Klassenschlüssel ersetzen; die Concurrency-Szenarien
(paralleles `register` und `codecFor`) bleiben unverändert.

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*EventCodecRegistry*'`
Expected: FAIL — die Registry hat den neuen Schlüssel noch nicht.

- [ ] **Step 3: Registry umstellen**

`EventCodecRegistry` verliert `eventId` und schlüsselt über `Class<out SurfBusEvent>`. Was
bleibt: die Thread-Sicherheit, die der Lincheck-Test prüft, und die Registrierung beim Auffinden
eines Listeners.

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*EventCodecRegistry*' --tests '*CustomEventPacketCodec*'`
Expected: PASS.

- [ ] **Step 5: Alte Event-API löschen**

```bash
cd S:/Workspaces/surf-rabbitmq
api=surf-eventbus-redis/surf-eventbus-redis-api/src/main/kotlin/dev/slne/surf/eventbus/redis
core=surf-eventbus-redis/surf-eventbus-redis-core/src/main/kotlin/dev/slne/surf/eventbus/redis
git rm "$api/event/RedisEvent.kt" "$api/event/OnRedisEvent.kt" "$api/event/RedisEventBus.kt" \
       "$api/event/RedisEventCodec.kt" "$api/event/RedisEventCodecRegistrar.kt"
git rm "$core/event/RedisEventBusImpl.kt" "$core/event/RedisEventInvoker.kt" \
       "$core/event/CustomEventPacketCodec.kt"
```

Aus `RedisApi.kt` die Event-Methoden entfernen (`publishEvent`, `subscribeToEvents` und den
`eventBus`-Zugang). Der Compiler nennt jede weitere Stelle.

- [ ] **Step 6: Benchmark umstellen**

`EventTransportBenchmark` zeigt auf `RedisEventBusImpl`. Er misst weiterhin denselben Vergleich —
JSON gegen Codec —, jetzt über `RedisEventTransport.publish`. Ohne diese Anpassung übersetzt der
`jmh`-Quellbaum nicht.

- [ ] **Step 7: Build und Tests laufen lassen**

Run: `./gradlew build -x test`
Expected: SUCCESS.

Run: `./gradlew test`
Expected: SUCCESS.

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:compileJmhKotlin`
Expected: SUCCESS.

- [ ] **Step 8: ABI und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: remove RedisEvent, @OnRedisEvent and RedisEventBus

Their replacement is SurfEventBus over RedisEventTransport. The codec
registry keys on the event class now; the topic from @BusEvent replaces the
former stable eventId. The Lincheck test moved first."
```

---

## Task 3: Rabbits Event-Teil löschen

**Files:**
- Delete: `…rabbitmq/api/event/RabbitEvent.kt`, `RabbitEventPacket.kt`, `RabbitSubscribe.kt`,
  `SubscriptionMode.kt`
- Delete: `…rabbitmq/core/event/EventDispatcher.kt`, `EventSubscription.kt`,
  `EventSubscriptionRegistry.kt`, `EventTopics.kt`
- Delete: `…rabbitmq-core/src/test/…/core/event/EventDeliveryTest.kt`,
  `EventSubscriptionRegistryTest.kt`, `EventTopicsTest.kt`
- Modify: `…rabbitmq/api/SurfRabbitApi.kt` (`publish`, `registerListener` entfallen)
- Modify: `…common/topology/RabbitTopology.kt` (`EVENTS_EXCHANGE`, `sharedEventQueue`,
  `instanceEventQueue` entfallen)
- Modify: `…common/topology/QueueArguments.kt` (`sharedEventQueue` entfällt)
- Modify: `…common/topology/RabbitTopologyDeclarer.kt` (`declareSharedEventQueue`,
  `declareInstanceEventQueue` entfallen, `declareExchanges` ohne `surf.events`)
- Modify: `…core/publish/MessageKind.kt` (Event-Zweig entfällt)
- Modify: `RabbitTopologyTest`, `RabbitTopologyDeclarerTest`, `QueueArgumentsTest`, `MessageKindTest`

**Interfaces:**
- Consumes: Task 1 und 2 — Events laufen bereits über Redis.
- Produces: `RabbitTopology` mit `RPC_EXCHANGE`, `DLX_EXCHANGE`, `UNROUTABLE_QUEUE`,
  `serviceQueue`, `instanceQueue`, `replyQueue`, `deadLetterQueue`, `sanitize`. Plan 4 entfernt die
  drei Dead-Letter-Namen.

- [ ] **Step 1: Topologie-Tests auf die kleinere Fläche umschreiben**

In `RabbitTopologyTest` die Fälle für `sharedEventQueue` und `instanceEventQueue` entfernen und
einen negativen Fall ergänzen, der festhält, dass es keinen Events-Exchange mehr gibt:

```kotlin
    @Test
    fun `there is no events exchange`() {
        val names = RabbitTopology::class.java.declaredFields.map { it.name }

        assertFalse(names.any { it.contains("EVENTS", ignoreCase = true) },
            "events live on Redis; a leftover exchange constant would invite a second event path")
    }
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*RabbitTopologyTest*'`
Expected: FAIL beim neuen Fall.

- [ ] **Step 3: Löschen**

```bash
cd S:/Workspaces/surf-rabbitmq
api=surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq
core=surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq
test=surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq
git rm -r "$api/api/event"
git rm -r "$core/core/event"
git rm -r "$test/core/event"
```

Dann `SurfRabbitApi.publish`, `SurfRabbitApi.registerListener`, die Event-Zweige in
`RabbitConnection`/`RabbitConnectionImpl`, `RabbitTopology.EVENTS_EXCHANGE`,
`RabbitTopology.sharedEventQueue`, `RabbitTopology.instanceEventQueue`,
`QueueArguments.sharedEventQueue`, `RabbitTopologyDeclarer.declareSharedEventQueue`,
`declareInstanceEventQueue` und den Event-Zweig in `MessageKind` entfernen. Der Compiler nennt
jede Fundstelle; keine davon ist Anwendungscode.

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew test`
Expected: SUCCESS. Die verbliebene Rabbit-Suite (Topologie, RPC, Competing Consumers, Retry,
Chunking, Broker-Neustart, Queue-Overflow, Unroutable, Failure) ist unverändert grün, soweit sie
ohne Docker läuft.

- [ ] **Step 5: ABI und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: remove the RabbitMQ event path

surf.events, the shared and per-instance event queues, the event dispatcher
and @RabbitSubscribe are gone; events are Redis. @RabbitSubscribe had no
external consumers, so nothing outside this repository notices."
```

---

## Task 4: `@FireAndForget`

**Files:**
- Create: `…-rabbitmq-api/…/rpc/FireAndForget.kt`
- Modify: `…-ksp/…/processor/Names.kt`, `…/processor/rpc/model/RpcFunctionModel.kt`,
  `RpcServiceModelFactory.kt`, `…/codegen/RpcClientImplCodegen.kt`,
  `…/codegen/RpcDescriptorCodegen.kt`
- Modify: `…-rabbitmq-api/…/rpc/callable/RabbitRpcCallable.kt` (`fireAndForget: Boolean`)
- Modify: `…-rabbitmq-core/…/rpc/RabbitRpcServiceImpl.kt` (`call` verzweigt; `createService` nimmt
  ein `RabbitTarget`; Proxy-Cache)
- Create: `…-rabbitmq-core/src/test/…/rpc/FireAndForgetContractTest.kt` (`@RequiresDocker`)
- Create: `…-ksp/src/test/…/FireAndForgetValidationTest.kt`

**Interfaces:**
- Produces:
  - `annotation class FireAndForget`
  - `RabbitRpcCallable.fireAndForget: Boolean`
  - `RabbitRpcService.createService(kClass: KClass<T>, target: RabbitTarget): T`
  - `SurfRabbitApi.rpc(kClass: KClass<T>, target: RabbitTarget? = null): T`

- [ ] **Step 1: KSP-Validierungstest schreiben**

```kotlin
package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The processor rejects a @FireAndForget method that promises a value.
 *
 * Compile-time is the only place this can be caught: at runtime the caller would wait for an
 * answer that the server was told not to send.
 */
class FireAndForgetValidationTest {

    @Test
    fun `a fire-and-forget method returning something other than Unit fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

            @RpcService(service = "svc")
            interface Broken {
                @FireAndForget
                suspend fun doWork(id: String): Boolean
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "@FireAndForget")
        assertContains(result.messages, "Unit")
    }

    @Test
    fun `a fire-and-forget method returning Unit compiles`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

            @RpcService(service = "svc")
            interface Fine {
                @FireAndForget
                suspend fun doWork(id: String)
            }
            """.trimIndent()
        )

        assertEquals(true, result.succeeded, result.messages)
    }
}
```

`compile(...)` ist ein kleiner Helfer über `kotlin-compile-testing` mit dem KSP-Prozessor; er
gehört in `…-ksp/src/test/…/CompilationSupport.kt` und braucht
`testImplementation("dev.zacsweers.kctfork:ksp:+")` im KSP-Modul.

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test --tests '*FireAndForgetValidationTest*'`
Expected: FAIL — die Annotation existiert nicht.

- [ ] **Step 3: Annotation schreiben**

```kotlin
package dev.slne.surf.eventbus.rabbitmq.api.rpc

/**
 * Marks an RPC method that does not wait for an answer.
 *
 * The caller returns after the publish; the message waits in the durable service queue if
 * nobody is running. Handler failures still take the retry ladder and end in the audit, but the
 * caller learns nothing about them.
 *
 * Must return `Unit` — KSP rejects anything else. The marker is at the method rather than at the
 * call site because both sides need it: the server must not wait for a `respond()` that never
 * comes, and the client must not wait for an answer that is never sent. The return type alone
 * cannot express it, since a *waiting* method returning `Unit` is a legitimate and different
 * thing: "do this, and tell me if it fails".
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FireAndForget
```

- [ ] **Step 4: Prozessor erweitern**

In `Names.kt` die Konstante ergänzen:

```kotlin
    const val FIRE_AND_FORGET_ANNOTATION_FQ = "dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget"
```

`RpcFunctionModel` bekommt ein `val fireAndForget: Boolean`; `RpcServiceModelFactory` liest die
Annotation und lehnt einen Rückgabetyp ≠ `Unit` mit einer Meldung ab, die beides nennt:

```kotlin
        if (fireAndForget && returnType.declaration.qualifiedName?.asString() != "kotlin.Unit") {
            logger.error(
                "@FireAndForget on ${function.simpleName.asString()} requires the return type Unit: " +
                        "nobody sends an answer, so nothing can be returned.",
                function
            )
        }
```

`RpcClientImplCodegen` generiert für eine solche Methode `send(...)` statt `sendRequest(...)`,
`RpcDescriptorCodegen` schreibt das Flag in den Descriptor.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test`
Expected: PASS, beide Fälle.

- [ ] **Step 6: Ziel- und Proxy-Cache im Client**

`RabbitRpcServiceImpl.createService` nimmt statt `service: String?` ein `RabbitTarget` und cacht
pro Paar:

```kotlin
    private val proxies = Caffeine.newBuilder().build<Pair<KClass<*>, RabbitTarget>, Any>()

    override fun <Service : Any> createService(serviceKClass: KClass<Service>, target: RabbitTarget?): Service {
        val descriptor = serviceDescriptorOf(serviceKClass)
        val resolved = target ?: RabbitTarget.ServiceTarget(
            descriptor.defaultService.ifBlank {
                error(
                    "No target for ${descriptor.fqName}. Either annotate the interface with " +
                            "@RpcService(service = \"...\") or pass rpc(target = ...)."
                )
            }
        )

        @Suppress("UNCHECKED_CAST")
        return proxies.get(serviceKClass to resolved) {
            descriptor.createInstance(serviceIdCounter.incrementAndGet(), api, resolved)
        } as Service
    }
```

Der Cache ist der Grund, warum `bus.rpc<PlayerService>(InstanceTarget(id))` in einem
Schleifenkörper stehen darf — ohne ihn wäre jeder Durchlauf eine Proxy-Konstruktion.

- [ ] **Step 7: Contract-Test schreiben (Docker)**

`FireAndForgetContractTest` mit `@RequiresDocker` deckt die Fälle 14, 15, 17, 18, 20 der
RPC-Suite ab: der Aufrufer kehrt vor der Verarbeitung zurück; ein Auftrag überlebt einen
Serverneustart in der durable Queue; `InstanceTarget` trifft genau eine von drei Instanzen; ein
`InstanceTarget` auf eine tote Instanz wird unroutable; zweimal `rpc<T>(InstanceTarget(x))`
liefert dieselbe Proxy-Instanz.

- [ ] **Step 8: Ausführung dokumentieren und committen**

Run: `./gradlew test`
Expected: SUCCESS, `FireAndForgetContractTest` SKIPPED.

```bash
git add -A
git commit -m "feat: add @FireAndForget and per-target RPC proxies

The KSP processor rejects a non-Unit return type at compile time. Proxies
are cached per interface and target so instance-addressed calls are cheap."
```

---

## Task 5: Untypisierte Paket-API löschen

**Files:**
- Delete: `…rabbitmq/api/packet/RabbitRequestPacket.kt`, `RabbitResponsePacket.kt`,
  `packet/standard/**` (6 Dateien), `api/handler/RabbitHandler.kt`
- Modify: `…rabbitmq/api/SurfRabbitApi.kt` (`send`, `registerRequestHandler`,
  `defaultSerializersModule` entfallen)
- Modify: `…rabbitmq/core/connection/**` (Handler-Registrierung entfällt)
- Rewrite: `FireAndForgetTest`, `CompetingConsumersTest`, `RetryIntegrationTest`,
  `BrokerLossDuringSendTest`, `ConsumerDeathTest`, `QueueOverflowTest`, `UnroutableTest`,
  `ChunkingTest`, `ChunkSeriesTest` — jeder Test mit eigener `RabbitRequestPacket`-Klasse

**Interfaces:**
- Consumes: `@FireAndForget` aus Task 4 — der Ersatz existiert, bevor gelöscht wird.
- Produces: `SurfRabbitApi` ohne Paket-API; `RabbitPacket`, `RpcCallRequestPacket`,
  `RpcCallResponsePacket` bleiben intern.

- [ ] **Step 1: Einen Test umschreiben und laufen lassen**

`FireAndForgetTest` ist der Musterfall: er definiert heute `WorkPacket`/`WorkResponse` und
registriert einen `@RabbitHandler`. Neu als Vertrag:

```kotlin
@RpcService(service = "fnf-test")
interface WorkService {
    @FireAndForget
    suspend fun doWork(text: String)
}
```

und die Serverseite ist eine Implementierung, die `bus.registerService<WorkService>(impl)`
registriert. Die Behauptungen bleiben wörtlich: der Handler läuft genau einmal, die Nachricht ist
nach dem Ack aus der Queue verschwunden, und nach fünf Sekunden — länger als das
Request-Timeout — ist er nicht ein zweites Mal gelaufen.

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*FireAndForgetTest*'`
Expected: SKIPPED (Docker). Übersetzbarkeit ist hier das Prüfkriterium:
`./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:compileTestKotlin` muss durchlaufen.

- [ ] **Step 2: Die übrigen acht Tests umschreiben**

Dieselbe Bewegung, jeder Test einzeln, jeweils mit `compileTestKotlin` als Kriterium. Wo ein Test
eine Antwort braucht, wird die Methode eine gewöhnliche RPC-Methode mit Rückgabetyp; wo er keine
braucht, `@FireAndForget`.

- [ ] **Step 3: Löschen**

```bash
cd S:/Workspaces/surf-rabbitmq
api=surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api
git rm "$api/packet/RabbitRequestPacket.kt" "$api/packet/RabbitResponsePacket.kt"
git rm -r "$api/packet/standard"
git rm "$api/handler/RabbitHandler.kt"
```

Aus `SurfRabbitApi` `send`, `registerRequestHandler` und `defaultSerializersModule` entfernen —
letzteres registriert genau die gelöschten Standard-Antwortpakete.

- [ ] **Step 4: Build und Tests**

Run: `./gradlew build -x test`
Expected: SUCCESS.

Run: `./gradlew test`
Expected: SUCCESS, Docker-Tests SKIPPED.

- [ ] **Step 5: ABI und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: remove the untyped packet API

RabbitRequestPacket, @RabbitHandler, respond(), send() and the six standard
response packets are gone; typed @RpcService contracts replace them. The
internal packet layer — RpcCallRequestPacket, chunking, properties — stays."
```

---

## Task 6: `@QueryService` generieren

**Files:**
- Create: `…-ksp/…/processor/query/QueryServiceProcessor.kt`, `…/query/codegen/*`
- Modify: `…-ksp/…/processor/Names.kt`
- Create: `…-ksp/src/test/…/QueryServiceValidationTest.kt`

**Interfaces:**
- Consumes: `@QueryService` aus Plan 2 Task 7.
- Produces: pro Vertrag ein `<Name>Descriptor` mit Kanalnamen, Timeout und pro Methode einem
  Callable; ein Client-Proxy, der `QueryTransport.ask` aufruft und die Antwort deserialisiert.

- [ ] **Step 1: Validierungstests schreiben**

```kotlin
package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class QueryServiceValidationTest {

    @Test
    fun `a non-nullable return type fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                suspend fun whereIs(player: String): String
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "nullable")
        assertContains(result.messages, "abstain")
    }

    @Test
    fun `a Unit return type fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                suspend fun notify(player: String)
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "event")
    }

    @Test
    fun `fire-and-forget on a query method fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget

            @QueryService
            interface Broken {
                @FireAndForget
                suspend fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "@FireAndForget")
    }

    @Test
    fun `a nullable suspend method compiles`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService(timeoutMillis = 2_000)
            interface Fine {
                suspend fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(true, result.succeeded, result.messages)
    }

    @Test
    fun `a non-suspend method fails compilation`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService

            @QueryService
            interface Broken {
                fun whereIs(player: String): String?
            }
            """.trimIndent()
        )

        assertEquals(false, result.succeeded)
        assertContains(result.messages, "suspend")
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test --tests '*QueryServiceValidationTest*'`
Expected: FAIL, alle fünf — der Prozessor kennt `@QueryService` nicht und lässt alles durch.

- [ ] **Step 3: Prozessor schreiben**

Der Generator ist derselbe Mechanismus wie für `@RpcService`: Descriptor plus Client-Proxy. Was
sich unterscheidet, ist die Validierung (drei Regeln oben) und der Aufrufpfad — `QueryTransport.ask`
statt `connection.sendRequest`. Die drei Fehlermeldungen sind Teil der API und lauten wörtlich:

```kotlin
        "@QueryService method ${name} must return a nullable type: null means abstain, so a " +
                "non-nullable return type cannot express \"not mine\"."
```

```kotlin
        "@QueryService method ${name} must not return Unit: a question without an answer is an event."
```

```kotlin
        "@FireAndForget has no meaning on a @QueryService method: a question without an answer " +
                "is an event. Use bus.publish(...) instead."
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test`
Expected: PASS, alle fünf Query-Fälle und die zwei aus Task 4.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: generate proxies and descriptors for @QueryService

One processor, two contract kinds. Three compile-time rules: nullable
return, never Unit, never @FireAndForget."
```

---

## Task 7: Redis-Query-Transport und Query-Dispatch

**Files:**
- Create: `…-redis-core/…/bus/RedisQueryTransport.kt`
- Create: `…-bus-core/…/dispatch/QueryDispatcher.kt`
- Create: `…-bus-core/src/test/…/dispatch/QueryDispatcherTest.kt`
- Create: `…-redis-core/src/test/…/bus/RedisQueryTransportTest.kt` (`@RequiresDocker`)
- Delete: `…redis/request/**`, `RequestResponseBusImpl.kt`, `RedisRequestHandlerInvoker.kt`
- Modify: `…redis/RedisApi.kt` (`sendRequest`, `registerRequestHandler` entfallen)

**Interfaces:**
- Consumes: `QueryTransport`, `QueryFrame`, `QueryServiceRegistry` aus Plan 2; Generierung aus
  Task 6.
- Produces: `class QueryDispatcher(registry, instanceId, auditSink, json, transport)` mit
  `suspend fun dispatch(frame: QueryFrame)`.

- [ ] **Step 1: Dispatcher-Test schreiben**

Die vier Regeln aus dem Spec, ohne Server:

```kotlin
package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.core.registry.QueryServiceRegistry
import dev.slne.surf.eventbus.query.QueryService
import dev.slne.surf.eventbus.transport.QueryFrame
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryDispatcherTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val reports = CopyOnWriteArrayList<AuditReport>()
    private val sink = object : AuditSink {
        override suspend fun report(report: AuditReport) { reports += report }
    }

    private fun frame() = QueryFrame(
        contract = Locator::class.java.name,
        callable = "whereIs",
        correlationId = "c-1",
        originInstanceId = "lobby-9",
        payload = """{"player":"ada"}"""
    )

    @Test
    fun `an answering handler sends exactly one answer`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Answering) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frame())

        assertEquals(1, transport.answered.size)
        assertEquals(0, reports.size)
    }

    @Test
    fun `an abstaining handler sends nothing`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Abstaining) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frame())

        assertEquals(0, transport.answered.size, "null means abstain: no message goes out")
        assertEquals(0, reports.size)
    }

    @Test
    fun `a throwing handler sends nothing and is audited`() = runBlocking {
        val transport = FakeQueryTransport()
        val registry = QueryServiceRegistry().apply { register(Locator::class.java, Throwing) }

        QueryDispatcher(registry, "lobby-1", sink, json, transport).dispatch(frame())

        assertEquals(0, transport.answered.size, "an error answer would overtake a correct one")
        assertEquals(1, reports.size)
        assertEquals(AuditKind.QUERY_HANDLER_FAILED, reports.single().kind)
        assertTrue(reports.single().contract!!.endsWith("Locator"))
    }

    @Test
    fun `a frame for an unoffered contract is ignored`() = runBlocking {
        val transport = FakeQueryTransport()

        QueryDispatcher(QueryServiceRegistry(), "lobby-1", sink, json, transport).dispatch(frame())

        assertEquals(0, transport.answered.size)
        assertEquals(0, reports.size)
    }
}

@QueryService
private interface Locator {
    suspend fun whereIs(player: String): String?
}

private object Answering : Locator {
    override suspend fun whereIs(player: String): String? = "lobby-1"
}

private object Abstaining : Locator {
    override suspend fun whereIs(player: String): String? = null
}

private object Throwing : Locator {
    override suspend fun whereIs(player: String): String? = error("handler is broken")
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*QueryDispatcherTest*'`
Expected: FAIL, „Unresolved reference: QueryDispatcher".

- [ ] **Step 3: Dispatcher schreiben**

Kern der Implementierung, die vier Regeln in Code:

```kotlin
    suspend fun dispatch(frame: QueryFrame) {
        val implementation = registry.implementationOf(frame.contract) ?: return

        val answer = try {
            invoke(implementation, frame)
        } catch (throwable: Throwable) {
            // The error stays here: another process may still answer correctly, and an error
            // answer would overtake it. For the asker a broken handler looks exactly like
            // abstention — which is why this audit row is the only trace there is.
            log.atSevere().withCause(throwable)
                .log("Query handler %s#%s failed", frame.contract, frame.callable)
            auditSink.report(failureReport(frame, throwable))
            return
        }

        // null means abstain: not "no", and not an answer. Nothing goes out.
        if (answer == null) return

        transport.answer(frame, answer)
    }
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-bus:surf-eventbus-bus-core:test --tests '*QueryDispatcherTest*'`
Expected: PASS, alle vier.

- [ ] **Step 5: `RedisQueryTransport` schreiben**

`ask` publiziert auf `RedisChannels.query(contract)`, legt ein `CompletableDeferred` unter der
`correlationId` ab und wartet mit `withTimeoutOrNull(timeoutMillis)`. Läuft die Zeit ab, wird der
Eintrag entfernt und `null` zurückgegeben — **keine** Ausnahme. `answer` publiziert auf
`RedisChannels.reply(frame.originInstanceId)`. `connect` abonniert einen Kanal pro Vertrag plus
den eigenen Reply-Kanal.

- [ ] **Step 6: Alte Request/Response-API löschen**

```bash
cd S:/Workspaces/surf-rabbitmq
api=surf-eventbus-redis/surf-eventbus-redis-api/src/main/kotlin/dev/slne/surf/eventbus/redis
core=surf-eventbus-redis/surf-eventbus-redis-core/src/main/kotlin/dev/slne/surf/eventbus/redis
git rm -r "$api/request"
git rm "$core/request/RequestResponseBusImpl.kt" "$core/request/RedisRequestHandlerInvoker.kt"
```

Aus `RedisApi` `sendRequest`, `registerRequestHandler` und den `requestResponseBus`-Zugang
entfernen.

- [ ] **Step 7: Integrationstest schreiben (Docker)**

`RedisQueryTransportTest` mit `@RequiresDocker` deckt die Fälle 13–20 und 23 der Query-Suite ab:
drei Anbieter, einer antwortet; zwei antworten, die erste gewinnt; alle abstinieren → `null` nach
dem Timeout; kein Anbieter → `null`; ein Handler wirft, ein anderer antwortet; ein Handler wirft,
niemand antwortet; ein Prozess ohne den Vertrag empfängt den Kanal nicht; nur der Fragende
empfängt die Antwort.

- [ ] **Step 8: Build, Tests, ABI, Commit**

Run: `./gradlew build -x test && ./gradlew test`
Expected: SUCCESS, Docker-Tests SKIPPED.

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: replace RedisRequest with typed @QueryService contracts

One channel per contract and one reply channel per instance instead of two
global channels. null abstains, a timeout returns null, and a throwing
handler is audited rather than answered."
```

---

## Task 8: Redis-Lebenszyklus und Konfiguration

**Files:**
- Modify: `…redis/RedisApi.kt` (vier `@Blocking`-Stellen → `suspend`)
- Modify: `…redis/config/RedisConfig.kt` (vierstufiges Layering)
- Create: `…-redis-core/src/test/…/config/RedisConfigLayeringTest.kt`
- Modify: `…-bus-core/…/SurfEventBusImpl.kt` (Redis in `connect`/`disconnect` einhängen)

**Interfaces:**
- Produces: `suspend fun RedisApi.connect()`, `suspend fun RedisApi.disconnect()`;
  `resolveRedisConfig(global, plugin, environment): RedisConfig`.

- [ ] **Step 1: Layering-Test schreiben**

```kotlin
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
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*RedisConfigLayeringTest*'`
Expected: FAIL, „Unresolved reference: resolveRedisConfig".

- [ ] **Step 3: Layering implementieren**

`RedisConfig` behält seine vier Felder und `SpongeYmlConfigClass`-Companion; neu ist eine freie
Funktion, die die vier Schichten in der Reihenfolge `env > plugin > global > default` zusammenlegt.
`overwriteFromEnv()` und `val redisConfig by lazy { … }` entfallen — beide kannten nur zwei
Schichten.

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test --tests '*RedisConfigLayeringTest*'`
Expected: PASS, alle fünf.

- [ ] **Step 5: `connect()` auf `suspend` umstellen**

Die vier `@Blocking`-Stellen in `RedisApi` werden `suspend`. Die Reactor-Interna
(`Initializable.init(): Mono<Void>`) bleiben; nur die Naht nach außen wird `suspend`, über
`awaitFirstOrNull()`. Danach hängt `SurfEventBusImpl.connect()` den Redis-Client mit an — und
`disconnect()` in umgekehrter Reihenfolge.

- [ ] **Step 6: Build, Tests, ABI, Commit**

Run: `./gradlew build -x test && ./gradlew test`
Expected: SUCCESS.

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: suspend RedisApi.connect() and give Redis the four config layers

RedisConfig knew config.yml plus overwriteFromEnv(); it now resolves
env > plugin yaml > global yaml > default like RabbitMQ always did."
```

---

## Self-Review

**Spec-Abdeckung (Spec → Task):**

| Spec | Task |
|---|---|
| Etappe 6: Kanalfamilien, exakt/Muster, `BusEventCodec`-Registry | 1, 2 |
| Etappe 6: `RedisEvent`, `@OnRedisEvent`, `RedisEventBus` löschen | 2 |
| Etappe 7: Rabbits Event-Teil löschen | 3 |
| Etappe 8: `@FireAndForget`, `rpc<T>(target)`, Proxy-Cache | 4 |
| Etappe 9: Paket-API löschen, Tests umschreiben | 5 |
| Etappe 10: `@QueryService` generieren, Kanäle, Abstinenz, Timeout | 6, 7 |
| Etappe 10: `RedisRequest` löschen | 7 |
| Etappe 11: `RedisApi.connect()` suspend, Config-Layering | 8 |
| Verifikation Query-Suite 13–23 | 6, 7 |
| Verifikation RPC-Suite 14–20, 30, 32 | 4 |
| Verifikation Event-Suite 1–5, 12a–12c | 1 |

**Nicht hier:** der Audit-Meldeweg über RabbitMQ und der Microservice (Plan 4), die
Aggregat-Module und die Plattform-Vereinigung (Plan 4), die Entfernung von `surf.dlx`,
`surf.dlq.*`, `surf.unroutable` (Plan 4 Task 1 — sie fällt zusammen mit dem Meldeweg, der sie
ersetzt).

**Typkonsistenz:** `QueryTransport.answer(frame, payload)` in Plan 2 entspricht dem Aufruf in
Task 7 Step 3. `RabbitRpcService.createService(kClass, target: RabbitTarget?)` in Task 4 Step 6
ist die Signatur, die `SurfEventBus.rpc(contract, target)` in Plan 4 benutzt.
`RedisChannels.query(contract)` nimmt den FQCN, und `QueryServiceRegistry.contracts()` liefert
genau FQCNs — beide Seiten passen.

**Reihenfolge, die nicht verhandelbar ist:** 1 vor 2 (Ersatz vor Löschung, Event-Seite), 4 vor 5
(RPC-Seite), 6 vor 7 (Query-Seite). Wer eine Löschung vorzieht, hat eine Etappe, die nicht
übersetzt.
