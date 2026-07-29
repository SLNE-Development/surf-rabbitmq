# surf-eventbus Plan 3: Beide Transports und die Parity-Suite

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beide Provider implementieren `EventTransport`, die alte Rabbit-Event-API ist
entfernt, und **eine** Testsuite belegt gegen echte Broker, dass sich beide gleich verhalten —
und wo genau nicht.

**Architecture:** Der Rabbit-Transport lebt in `surf-rabbitmq-core` und benutzt die vorhandene
Topologie unverändert: `surf.events` als Topic-Exchange, durable Shared-Queues, ephemere
Instanz-Queues, Retry-Leiter, DLQ. Topic und Metadaten reisen als Routing-Key und AMQP-Header,
nicht im Body — dadurch bleibt das bestehende Frame-Format nutzbar. Der Redis-Transport lebt in
`surf-redis-core` und publiziert je Topic auf einen eigenen Kanal; wildcardfreie Abonnements
nehmen `SUBSCRIBE`, Wildcard-Abonnements `PSUBSCRIBE` plus lokales Matching mit AMQP-Semantik.

**Tech Stack:** RabbitMQ amqp-client, Redisson, kotlinx.serialization (CBOR und JSON),
Testcontainers (RabbitMQ **und** Redis), JUnit 5 mit `@ParameterizedTest`.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-surf-eventbus-design.md`
- Voraussetzung: Plan 1 und Plan 2 abgeschlossen
- Der Transport besitzt seine Kodierung. Rabbit: CBOR. Redis: JSON. Kein gemischter Betrieb
- Metadaten (`originInstanceId`, `publishedAtEpochMs`) reisen im Envelope des Transports,
  niemals im Event-Body
- Ein unbekannter Wire-Typ wird **geackt und verworfen**, niemals requeued: eine durable
  Shared-Queue würde ihn sonst endlos erneut zustellen
- Redis deklariert ausschließlich `BROADCAST`. `SHARED` über Streams ist ausdrücklich nicht
  Bestandteil
- Integrationstests tragen `@RequiresDocker` und werden mit `-PskipIntegration` ausgeschlossen.
  Ohne Docker-Daemon lautet ihr Status „nicht verifiziert", nicht „bestanden"

---

### Task 1: Envelope-Kodierung für den Rabbit-Transport

Topic und Metadaten liegen bereits auf AMQP-Ebene: der Routing-Key **ist** das Topic, Header
tragen Herkunft und Zeitstempel. Der Body braucht deshalb nur Typ und Payload — dasselbe
Frame-Format, das `RabbitPacketSerializer` schon benutzt.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/eventbus/RabbitBusEventCodec.kt`
- Create: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/eventbus/RabbitBusEventCodecTest.kt`

**Interfaces:**
- Consumes: `SurfBusEvent`, `EventTypeResolver` aus Plan 2
- Produces: `class RabbitBusEventCodec(private val cbor: Cbor)` mit
  - `fun encode(event: SurfBusEvent): ByteArray`
  - `fun decode(body: ByteArray, resolver: EventTypeResolver): SurfBusEvent?` — `null` bei
    unbekanntem Typ
  - `companion object { const val HEADER_ORIGIN = "x-surf-origin"; const val HEADER_PUBLISHED_AT = "x-surf-published-at" }`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.rabbitmq.core.eventbus

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventTypeResolver
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
@BusEvent("codec.thing")
class CodecThingEvent(val value: String) : SurfBusEvent()

@OptIn(ExperimentalSerializationApi::class)
class RabbitBusEventCodecTest {

    private val codec = RabbitBusEventCodec(Cbor { ignoreUnknownKeys = true })

    private val resolver = EventTypeResolver { name ->
        if (name == CodecThingEvent::class.java.name) CodecThingEvent::class.java else null
    }

    @Test
    fun `round-trips an event`() {
        val decoded = codec.decode(codec.encode(CodecThingEvent("hello")), resolver)

        assertEquals("hello", (decoded as CodecThingEvent).value)
    }

    @Test
    fun `returns null for a type the resolver does not know`() {
        val body = codec.encode(CodecThingEvent("hello"))

        assertNull(codec.decode(body, EventTypeResolver { null }))
    }

    @Test
    fun `does not put metadata into the body`() {
        val event = CodecThingEvent("hello").also { it.applyMetadata("lobby-3", 999L) }

        val body = codec.encode(event).decodeToString()

        // Metadata belongs in the AMQP headers; in the body it would be a second source of
        // truth that a publisher could forge.
        assert(!body.contains("lobby-3")) { "origin leaked into the body" }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*RabbitBusEventCodecTest*'`
Expected: FAIL — `Unresolved reference: RabbitBusEventCodec`

- [ ] **Step 3: Implementieren**

```kotlin
@file:OptIn(ExperimentalSerializationApi::class)

package dev.slne.surf.rabbitmq.core.eventbus

import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventTypeResolver
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitSerializerNotFoundException
import io.netty.buffer.Unpooled
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor

/**
 * Wire format of a bus event on RabbitMQ.
 *
 * ```
 * routing key : the topic
 * headers     : x-surf-origin, x-surf-published-at
 * body        : [2 bytes typeNameLength][typeName UTF-8][CBOR payload]
 * ```
 *
 * Topic and metadata live on the AMQP message rather than in the body: the broker needs the
 * topic for routing anyway, and keeping metadata out of the body means the payload stays
 * exactly the event a consumer declared.
 */
class RabbitBusEventCodec(private val cbor: Cbor) {

    private val serializers = KotlinSerializerCache<SurfBusEvent>(cbor.serializersModule)

    private val typeNameBytes = object : ClassValue<ByteArray>() {
        override fun computeValue(type: Class<*>): ByteArray = type.name.encodeToByteArray()
    }

    fun encode(event: SurfBusEvent): ByteArray {
        val serializer = serializers.get(event.javaClass)
            ?: throw SurfRabbitSerializerNotFoundException(event.javaClass.name)

        val nameBytes = typeNameBytes.get(event.javaClass)
        val payload = cbor.encodeToByteArray(serializer, event)

        val buf = Unpooled.buffer(
            Short.SIZE_BYTES + nameBytes.size + payload.size,
            Short.SIZE_BYTES + nameBytes.size + payload.size
        )

        return try {
            buf.writeShort(nameBytes.size)
            buf.writeBytes(nameBytes)
            buf.writeBytes(payload)
            buf.array()
        } finally {
            buf.release()
        }
    }

    /** @return the decoded event, or `null` if this process cannot decode the type */
    fun decode(body: ByteArray, resolver: EventTypeResolver): SurfBusEvent? {
        val buf = Unpooled.wrappedBuffer(body)

        return try {
            val length = buf.readUnsignedShort()
            val typeName = buf.toString(buf.readerIndex(), length, Charsets.UTF_8)
            buf.skipBytes(length)

            val eventClass = resolver.resolve(typeName) ?: return null
            val serializer = serializers.get(eventClass)
                ?: throw SurfRabbitSerializerNotFoundException(typeName)

            val offset = buf.readerIndex()
            val payload = body.copyOfRange(offset, offset + buf.readableBytes())

            cbor.decodeFromByteArray(serializer, payload)
        } finally {
            buf.release()
        }
    }

    companion object {
        const val HEADER_ORIGIN = "x-surf-origin"
        const val HEADER_PUBLISHED_AT = "x-surf-published-at"
    }
}
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*RabbitBusEventCodecTest*'`
Expected: PASS, drei Tests

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(rabbit): add the bus event wire format"
```

---

### Task 2: Rabbit-Event-Pfad hinter EventTransport legen

Der Consumer-, Publish- und Retry-Code existiert in `RabbitConnectionImpl` bereits. Diese Task
verschiebt seine Ansteuerung von `SurfRabbitApi.publish`/`registerListener` auf das SPI und
lässt die Topologie unberührt.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/eventbus/RabbitEventTransport.kt`
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/eventbus/RabbitEventTransportFactory.kt`
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/connection/RabbitMQConnection.kt`
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/RabbitConnectionImpl.kt:112-118,233-258,274-294,500-559`

**Interfaces:**
- Consumes: `EventTransport`, `EventTransportFactory`, `EventTransportContext`, `EventSink`,
  `TopicBinding`, `OutgoingEvent`, `IncomingEvent`, `EventHandlingFailure`,
  `RabbitBusEventCodec`
- Produces:
  - `RabbitMQConnection.startEventConsumers(bindings: Set<TopicBinding>, deliver: suspend (topic: String, body: ByteArray, headers: Map<String, Any?>) -> Unit)`
  - `RabbitMQConnection.publishBusEvent(topic: String, body: ByteArray, headers: Map<String, Any?>)`
  - `class RabbitEventTransport(private val api: SurfRabbitApi, private val context: EventTransportContext) : EventTransport`
    mit `provider = Provider.RABBIT`, `capabilities = TransportCapability.entries.toSet()`,
    `transportApi = api`, `instanceId = api.identity.instanceId`
  - `@AutoService(EventTransportFactory::class) class RabbitEventTransportFactory`

- [ ] **Step 1: Verbindungs-SPI erweitern**

In `RabbitMQConnection.kt` die beiden Event-Methoden ersetzen. Entfernt werden:

```kotlin
    fun registerListener(listener: Any)
    suspend fun publishEvent(event: RabbitEventPacket)
```

Neu:

```kotlin
    /**
     * Declares and consumes the event queues for [bindings].
     *
     * [deliver] is called per message; throwing from it drives the retry ladder, exactly as
     * the previous in-connection dispatcher did.
     */
    suspend fun startEventConsumers(
        bindings: Set<TopicBinding>,
        deliver: suspend (topic: String, body: ByteArray, headers: Map<String, Any?>) -> Unit
    )

    /** Publishes an already-encoded event body under [topic]. */
    suspend fun publishBusEvent(topic: String, body: ByteArray, headers: Map<String, Any?>)
```

Der Import von `RabbitEventPacket` entfällt, `dev.slne.surf.eventbus.transport.TopicBinding`
kommt hinzu.

- [ ] **Step 2: RabbitConnectionImpl umbauen**

In `RabbitConnectionImpl`:

1. Die Felder `subscriptions`, `eventDispatcher`, `eventSerializerCache` und `eventNameCache`
   (Zeilen 112–118) entfallen. Stattdessen:

```kotlin
    private var eventDeliver:
            (suspend (topic: String, body: ByteArray, headers: Map<String, Any?>) -> Unit)? = null
    private var eventRetryDecider: ((Throwable) -> Boolean)? = null
```

2. Der Event-Block in `connect()` (Zeilen 233–258) entfällt vollständig. Die Event-Queues
   werden jetzt in `startEventConsumers` deklariert, das der Transport **nach** `connect()`
   aufruft.

3. `registerListener` und `publishEvent` (Zeilen 274–294) werden ersetzt durch:

```kotlin
    override suspend fun publishBusEvent(
        topic: String,
        body: ByteArray,
        headers: Map<String, Any?>
    ) {
        client.publish(
            exchange = RabbitTopology.EVENTS_EXCHANGE,
            routingKey = topic,
            body = body,
            properties = properties(MessageKind.EVENT, extraHeaders = headers),
            // An event with no subscriber is normal, not an error. Requesting a return would
            // make every unobserved event look like a failure.
            mandatory = false
        )
    }

    override suspend fun startEventConsumers(
        bindings: Set<TopicBinding>,
        deliver: suspend (topic: String, body: ByteArray, headers: Map<String, Any?>) -> Unit
    ) {
        if (bindings.isEmpty()) return

        eventDeliver = deliver

        val consumer = client.newConsumer("events")
        this.eventConsumer = consumer

        val sharedPatterns = bindings
            .filter { it.mode == SubscriptionMode.SHARED }
            .map { it.pattern }
            .toSet()

        if (sharedPatterns.isNotEmpty()) {
            val queue = consumer.withChannel { channel ->
                RabbitTopologyDeclarer(channel)
                    .declareSharedEventQueue(api.identity.serviceName, sharedPatterns)
            }
            startConsumingEvents(consumer, queue)
        }

        val broadcastPatterns = bindings
            .filter { it.mode == SubscriptionMode.BROADCAST }
            .map { it.pattern }
            .toSet()

        if (broadcastPatterns.isNotEmpty()) {
            val queue = consumer.withChannel { channel ->
                RabbitTopologyDeclarer(channel)
                    .declareInstanceEventQueue(api.identity.instanceId, broadcastPatterns)
            }
            startConsumingEvents(consumer, queue)
        }
    }
```

`SubscriptionMode` wird jetzt aus `dev.slne.surf.eventbus.event.SubscriptionMode` importiert.

4. `startConsumingEvents` (Zeilen 500–559) verliert Deserialisierung und Dispatch; beides
   gehört jetzt dem Transport. Der Rumpf wird:

```kotlin
    private suspend fun startConsumingEvents(consumer: RabbitConsumer, queue: String) {
        consumer.consume(
            queue = queue,
            autoAck = false,
            prefetchCount = prefetchCount,
            requeueOnHandlerError = false
        ) { _, message, ack ->
            val topic = message.envelope.routingKey
            val deliver = eventDeliver

            if (deliver == null) {
                // Cannot happen: consumers only start after the deliver callback is set.
                ack.nack(requeue = false)
                return@consume
            }

            try {
                deliver(topic, message.body, message.properties.headers ?: emptyMap())
                ack.ack()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                log.atWarning()
                    .withCause(t)
                    .log("Failed to handle event on topic %s", topic)

                // A non-idempotent subscription among the matches vetoes retry for all of
                // them: they arrive as one message, and re-running the idempotent handler
                // alongside the non-idempotent one is not an option. The bus decides and
                // reports it through EventHandlingFailure.
                val retryEnabled = (t as? EventHandlingFailure)?.retryable ?: false

                try {
                    retryPublisher.handleFailure(
                        body = message.body,
                        properties = message.properties,
                        originQueue = queue,
                        serviceName = api.identity.serviceName,
                        retryEnabled = retryEnabled,
                        rechunkAsRequest = false
                    )
                    ack.ack()
                } catch (republishFailure: Throwable) {
                    if (republishFailure is CancellationException) throw republishFailure

                    log.atSevere()
                        .withCause(republishFailure)
                        .log(
                            "Failed to republish event on topic %s to the retry ladder, " +
                                    "falling back to nack",
                            topic
                        )

                    ack.nack(requeue = false)
                }
            }
        }
    }
```

5. `properties(...)` bekommt einen weiteren Parameter, damit die Metadaten-Header mitgehen:

```kotlin
    private fun properties(
        kind: MessageKind,
        correlationId: String? = null,
        replyTo: String? = null,
        messageId: String? = null,
        extraHeaders: Map<String, Any?> = emptyMap()
    ): AMQP.BasicProperties = AMQP.BasicProperties.Builder()
        .deliveryMode(kind.deliveryMode(persistRequests, persistResponses))
        .also { builder ->
            correlationId?.let(builder::correlationId)
            replyTo?.let(builder::replyTo)
            messageId?.let(builder::messageId)
            kind.expirationMillis(requestTimeoutSeconds)?.let(builder::expiration)
        }
        .headers(
            buildMap {
                put(RabbitMqVersion.AMQP_HEADER, RabbitMqVersion.CURRENT.toString())
                putAll(extraHeaders)
            }
        )
        .build()
```

- [ ] **Step 3: Transport implementieren**

```kotlin
package dev.slne.surf.rabbitmq.core.eventbus

import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventSink
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.IncomingEvent
import dev.slne.surf.eventbus.transport.OutgoingEvent
import dev.slne.surf.eventbus.transport.TopicBinding
import dev.slne.surf.eventbus.transport.TransportCapability
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.SurfRabbitApi

/**
 * Delivers bus events over RabbitMQ.
 *
 * Offers every capability: the `surf.events` topic exchange routes by pattern, a durable
 * quorum queue per service gives `SHARED`, an ephemeral queue per instance gives
 * `BROADCAST`, and the retry ladder plus `surf.dlq.<service>` give `RETRY` and
 * `DEAD_LETTER`.
 */
@OptIn(InternalEventBus::class)
class RabbitEventTransport(
    private val api: SurfRabbitApi,
    private val context: EventTransportContext
) : EventTransport {

    companion object {
        private val log = logger()
    }

    private val codec = RabbitBusEventCodec(api.cbor)

    override val provider: Provider = Provider.RABBIT
    override val instanceId: String get() = api.identity.instanceId
    override val capabilities: Set<TransportCapability> = TransportCapability.entries.toSet()
    override val transportApi: Any get() = api

    override suspend fun start(bindings: Set<TopicBinding>, sink: EventSink) {
        // The api owns the connection; the bus owns the lifecycle. freeze() here, not in
        // consumer code, is what guarantees one connection and one point of no return.
        api.freezeAndConnect()

        api.connection.startEventConsumers(bindings) { topic, body, headers ->
            val event = codec.decode(body, context.typeResolver)

            if (event == null) {
                // Loud on purpose. Durable SHARED queues keep their bindings across deploys,
                // so a pattern removed from the code keeps routing events here, where they
                // are acked and dropped. This log line is the only trace of that drift.
                log.atWarning().log(
                    "Received an event on topic %s whose type this process cannot decode - " +
                            "acking and dropping. If the subscription was removed, delete " +
                            "its binding on the shared event queue.",
                    topic
                )
                return@startEventConsumers
            }

            sink.accept(
                IncomingEvent(
                    event = event,
                    topic = topic,
                    originInstanceId = headers[RabbitBusEventCodec.HEADER_ORIGIN]?.toString()
                        ?: "",
                    publishedAtEpochMs =
                        headers[RabbitBusEventCodec.HEADER_PUBLISHED_AT]?.toString()
                            ?.toLongOrNull() ?: 0L
                )
            )
        }
    }

    override suspend fun publish(outgoing: OutgoingEvent) {
        api.connection.publishBusEvent(
            topic = outgoing.topic,
            body = codec.encode(outgoing.event),
            headers = mapOf(
                RabbitBusEventCodec.HEADER_ORIGIN to outgoing.originInstanceId,
                RabbitBusEventCodec.HEADER_PUBLISHED_AT to
                        outgoing.publishedAtEpochMs.toString()
            )
        )
    }

    override suspend fun stop() {
        api.disconnect()
    }
}
```

Und die Factory:

```kotlin
package dev.slne.surf.rabbitmq.core.eventbus

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.EventTransportFactory
import dev.slne.surf.rabbitmq.api.SurfRabbitApi

@OptIn(InternalEventBus::class)
@AutoService(EventTransportFactory::class)
class RabbitEventTransportFactory : EventTransportFactory {

    override val provider: Provider = Provider.RABBIT

    override fun create(context: EventTransportContext): EventTransport {
        val api = SurfRabbitApi.builder(context.serviceName, context.dataPath)
            .serializers(context.serializers)
            .also { builder -> context.instanceName?.let(builder::instanceName) }
            .build()

        return RabbitEventTransport(api, context)
    }
}
```

- [ ] **Step 4: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: FAIL — `SurfRabbitApi.publish`, `SurfRabbitApi.registerListener` und die
Event-Tests referenzieren noch die alte API. Das räumt Task 3 auf.

- [ ] **Step 5: Commit (Zwischenstand, bewusst nicht grün)**

```bash
git add -A
git commit -m "feat(rabbit): implement EventTransport over the existing topology

WIP: the legacy event API is removed in the next commit; the build is red
in between because both paths cannot coexist on one connection."
```

---

### Task 3: Alte Rabbit-Event-API entfernen

**Files:**
- Delete: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event/` (vier Dateien)
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventDispatcher.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscription.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistry.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsBridge.kt`
- Delete: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistryTest.kt`
- Modify: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApi.kt:164-184`
- Rewrite: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventDeliveryTest.kt`
  → `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/…` (Task 6)

**Interfaces:**
- Consumes: nichts
- Produces: `SurfRabbitApi` ohne `publish`, `registerListener`; `freeze`, `connect`,
  `freezeAndConnect`, `disconnect` mit `@InternalRabbitMQ` markiert

- [ ] **Step 1: Alte Typen löschen**

```bash
git rm -r surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/event
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventDispatcher.kt \
       surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscription.kt \
       surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistry.kt \
       surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/event/EventTopicsBridge.kt \
       surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventSubscriptionRegistryTest.kt
git rm surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/event/EventDeliveryTest.kt
```

- [ ] **Step 2: SurfRabbitApi aufräumen**

`publish` und `registerListener` entfernen. Der Lebenszyklus gehört jetzt dem Bus; die
Methoden bleiben aufrufbar, werden aber als intern markiert, damit ein Consumer sie nicht
versehentlich neben dem Bus benutzt:

```kotlin
    /**
     * Locks registration.
     *
     * Driven by the event bus, which owns the lifecycle so that a process has exactly one
     * freeze point. Calling it directly alongside a bus would produce two.
     */
    @InternalRabbitMQ
    fun freeze() { … }

    @InternalRabbitMQ
    suspend fun connect() { … }

    @InternalRabbitMQ
    suspend fun freezeAndConnect() { … }

    @InternalRabbitMQ
    suspend fun disconnect() { … }
```

Der Import von `RabbitEventPacket` in `SurfRabbitApi.kt` und die zugehörigen KDoc-Absätze
entfallen.

- [ ] **Step 3: Auf verbleibende Referenzen prüfen**

Run: `grep -rn -e 'RabbitEventPacket' -e '@RabbitSubscribe' -e 'RabbitEvent(' --include='*.kt' . | grep -v '/build/'`
Expected: keine Treffer außer in `docs/`

- [ ] **Step 4: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 5: ABI-Dump aktualisieren**

Run: `./gradlew updateLegacyAbi && git diff --stat -- '*/api/*.api'`
Expected: Die entfernten Event-Typen und die beiden entfernten Methoden verschwinden aus
`surf-rabbitmq-api.api`. Jede weitere Änderung ist ein unbeabsichtigter Bruch.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(rabbit)!: remove the transport-specific event API

Events now go through SurfEventBus. @RabbitSubscribe had no external
consumers, so nothing outside this repository is affected."
```

---

### Task 4: Redis-Transport

**Files:**
- Create: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/eventbus/RedisBusEventCodec.kt`
- Create: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/eventbus/RedisEventTransport.kt`
- Create: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/eventbus/RedisEventTransportFactory.kt`
- Create: `surf-redis-core/src/test/kotlin/dev/slne/surf/redis/eventbus/RedisBusEventCodecTest.kt`
- Modify: `surf-redis-core/build.gradle.kts` (Abhängigkeit auf `surf-eventbus-api`, Testabhängigkeiten)

**Interfaces:**
- Consumes: das SPI aus Plan 2, `RedisApi`, `EventTopics`
- Produces:
  - `class RedisBusEventCodec(private val json: Json)` mit
    `fun encode(outgoing: OutgoingEvent): String` und
    `fun decode(message: String, resolver: EventTypeResolver): IncomingEvent?`
  - `class RedisEventTransport(private val api: RedisApi, private val context: EventTransportContext) : EventTransport`
    mit `provider = Provider.REDIS`, `capabilities = setOf(TransportCapability.BROADCAST)`,
    `transportApi = api`
  - `@AutoService(EventTransportFactory::class) class RedisEventTransportFactory`
  - `const val CHANNEL_PREFIX = "surf.eventbus."`

- [ ] **Step 1: Abhängigkeiten ergänzen**

In `surf-redis-core/build.gradle.kts` im `dependencies`-Block:

```kotlin
    api(projects.surfEventbusApi)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))
    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
```

Und einen `tasks.test`-Block, den das Modul bisher nicht hat:

```kotlin
tasks.test {
    useJUnitPlatform {
        if (providers.gradleProperty("skipIntegration").isPresent) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

- [ ] **Step 2: Failing test für den Codec schreiben**

```kotlin
package dev.slne.surf.redis.eventbus

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventTypeResolver
import dev.slne.surf.eventbus.transport.OutgoingEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Serializable
@BusEvent("redis.codec.thing")
class RedisCodecThingEvent(val value: String) : SurfBusEvent()

class RedisBusEventCodecTest {

    private val codec = RedisBusEventCodec(Json { ignoreUnknownKeys = true })

    private val resolver = EventTypeResolver { name ->
        if (name == RedisCodecThingEvent::class.java.name) {
            RedisCodecThingEvent::class.java
        } else {
            null
        }
    }

    private fun outgoing(event: SurfBusEvent) = OutgoingEvent(
        event = event,
        topic = "redis.codec.thing",
        typeName = event.javaClass.name,
        originInstanceId = "lobby-3",
        publishedAtEpochMs = 4711L
    )

    @Test
    fun `round-trips an event with its metadata`() {
        val decoded = codec.decode(codec.encode(outgoing(RedisCodecThingEvent("hi"))), resolver)!!

        assertEquals("hi", (decoded.event as RedisCodecThingEvent).value)
        assertEquals("redis.codec.thing", decoded.topic)
        assertEquals("lobby-3", decoded.originInstanceId)
        assertEquals(4711L, decoded.publishedAtEpochMs)
    }

    @Test
    fun `returns null for an unknown type`() {
        assertNull(
            codec.decode(codec.encode(outgoing(RedisCodecThingEvent("hi"))), EventTypeResolver { null })
        )
    }

    @Test
    fun `returns null for a malformed message`() {
        // Another process on the same Redis may publish anything at all.
        assertNull(codec.decode("not json", resolver))
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-redis-core:test --tests '*RedisBusEventCodecTest*'`
Expected: FAIL — `Unresolved reference: RedisBusEventCodec`

- [ ] **Step 4: Codec implementieren**

```kotlin
package dev.slne.surf.redis.eventbus

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.transport.EventTypeResolver
import dev.slne.surf.eventbus.transport.IncomingEvent
import dev.slne.surf.eventbus.transport.OutgoingEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire format of a bus event on Redis.
 *
 * A JSON envelope on the topic's channel. Unlike the AMQP side there is no message header to
 * put metadata in, so it rides in the envelope — still outside the payload, so the body stays
 * exactly the event a consumer declared.
 */
class RedisBusEventCodec(private val json: Json) {

    private val serializers = KotlinSerializerCache<SurfBusEvent>(json.serializersModule)

    fun encode(outgoing: OutgoingEvent): String {
        val serializer = serializers.get(outgoing.event.javaClass)
            ?: error("No serializer for event ${outgoing.event.javaClass.name}")

        return json.encodeToString(
            Envelope.serializer(),
            Envelope(
                topic = outgoing.topic,
                type = outgoing.typeName,
                origin = outgoing.originInstanceId,
                publishedAt = outgoing.publishedAtEpochMs,
                payload = json.encodeToJsonElement(serializer, outgoing.event)
            )
        )
    }

    /** @return `null` if the message is not ours, malformed, or of a type we cannot decode */
    fun decode(message: String, resolver: EventTypeResolver): IncomingEvent? {
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), message)
        } catch (e: SerializationException) {
            // Anyone may publish on a Redis instance; a message we cannot parse is not an
            // error on our side.
            log.atFine().withCause(e).log("Ignoring an unparseable event bus message")
            return null
        }

        val eventClass = resolver.resolve(envelope.type) ?: return null
        val serializer = serializers.get(eventClass) ?: return null

        val event = try {
            json.decodeFromJsonElement(serializer, envelope.payload)
        } catch (e: SerializationException) {
            log.atWarning()
                .withCause(e)
                .log("Failed to deserialize event %s on topic %s", envelope.type, envelope.topic)
            return null
        }

        return IncomingEvent(
            event = event,
            topic = envelope.topic,
            originInstanceId = envelope.origin,
            publishedAtEpochMs = envelope.publishedAt
        )
    }

    @Serializable
    private data class Envelope(
        val topic: String,
        val type: String,
        val origin: String,
        val publishedAt: Long,
        val payload: JsonElement
    )

    companion object {
        private val log = logger()

        /** Channel prefix. The topic follows, so an exact subscription is possible. */
        const val CHANNEL_PREFIX = "surf.eventbus."

        /** Pattern for wildcard subscriptions: everything, filtered locally. */
        const val CHANNEL_PATTERN = CHANNEL_PREFIX + "*"

        fun channelFor(topic: String): String = CHANNEL_PREFIX + topic
    }
}
```

- [ ] **Step 5: Transport implementieren**

```kotlin
package dev.slne.surf.redis.eventbus

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.topic.EventTopics
import dev.slne.surf.eventbus.transport.EventSink
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.OutgoingEvent
import dev.slne.surf.eventbus.transport.TopicBinding
import dev.slne.surf.eventbus.transport.TransportCapability
import dev.slne.surf.redis.RedisApi
import kotlinx.coroutines.launch
import org.redisson.client.codec.StringCodec

/**
 * Delivers bus events over Redis Pub/Sub.
 *
 * Broadcast only, and deliberately not more: pub/sub has no queue, so an event published while
 * a subscriber is down is gone, and there is nothing to redeliver from. `SHARED`, `RETRY` and
 * `DEAD_LETTER` are therefore **not** declared, and the bus refuses a `SHARED` handler at
 * `freeze()` instead of quietly downgrading it.
 *
 * Routing: a publish goes to `surf.eventbus.<topic>`. A wildcard-free binding subscribes to
 * exactly that channel and lets Redis filter. A binding with `*` or `#` cannot — Redis globs
 * cross dots where AMQP patterns do not — so it subscribes to the whole prefix and matches
 * locally with [EventTopics], which is the same matcher the RabbitMQ side relies on.
 */
@OptIn(InternalEventBus::class)
class RedisEventTransport(
    private val api: RedisApi,
    private val context: EventTransportContext
) : EventTransport {

    companion object {
        private val log = logger()
    }

    private val codec = RedisBusEventCodec(api.json)
    private val listenerIds = mutableListOf<Pair<String, Int>>()
    private var localPatterns: Set<String> = emptySet()

    override val provider: Provider = Provider.REDIS
    override val instanceId: String get() = api.clientId
    override val capabilities: Set<TransportCapability> = setOf(TransportCapability.BROADCAST)
    override val transportApi: Any get() = api

    override suspend fun start(bindings: Set<TopicBinding>, sink: EventSink) {
        api.freezeAndConnect()

        if (bindings.isEmpty()) return

        val (wildcard, exact) = bindings
            .map { it.pattern }
            .partition { it.contains('*') || it.contains('#') }

        localPatterns = wildcard.toSet()

        for (topic in exact.toSet()) {
            subscribe(RedisBusEventCodec.channelFor(topic), sink, matchLocally = false)
        }

        if (localPatterns.isNotEmpty()) {
            subscribePattern(sink)
        }
    }

    private fun subscribe(channel: String, sink: EventSink, matchLocally: Boolean) {
        val topic = api.redisson.getTopic(channel, StringCodec.INSTANCE)

        val id = topic.addListener(String::class.java) { _, message ->
            handle(message, sink, matchLocally)
        }

        listenerIds += channel to id
    }

    private fun subscribePattern(sink: EventSink) {
        val pattern = api.redisson.getPatternTopic(
            RedisBusEventCodec.CHANNEL_PATTERN,
            StringCodec.INSTANCE
        )

        val id = pattern.addListener(String::class.java) { _, _, message ->
            handle(message, sink, matchLocally = true)
        }

        listenerIds += RedisBusEventCodec.CHANNEL_PATTERN to id
    }

    private fun handle(message: String, sink: EventSink, matchLocally: Boolean) {
        val incoming = codec.decode(message, context.typeResolver) ?: return

        if (matchLocally && localPatterns.none { EventTopics.matches(it, incoming.topic) }) {
            return
        }

        context.scope.launch {
            try {
                sink.accept(incoming)
            } catch (cause: Throwable) {
                // No queue, no redelivery: logging is all this transport can do, which is
                // exactly why it does not declare RETRY and why the bus warns at startup.
                log.atSevere()
                    .withCause(cause)
                    .log(
                        "Handler failed for event %s on topic %s - Redis Pub/Sub cannot " +
                                "redeliver, the event is gone",
                        incoming.event.javaClass.name,
                        incoming.topic
                    )
            }
        }
    }

    override suspend fun publish(outgoing: OutgoingEvent) {
        api.redisson
            .getTopic(RedisBusEventCodec.channelFor(outgoing.topic), StringCodec.INSTANCE)
            .publish(codec.encode(outgoing))
    }

    override suspend fun stop() {
        for ((channel, id) in listenerIds) {
            runCatching {
                if (channel == RedisBusEventCodec.CHANNEL_PATTERN) {
                    api.redisson
                        .getPatternTopic(channel, StringCodec.INSTANCE)
                        .removeListener(id)
                } else {
                    api.redisson.getTopic(channel, StringCodec.INSTANCE).removeListener(id)
                }
            }
        }
        listenerIds.clear()

        api.disconnect()
    }
}
```

Und die Factory:

```kotlin
package dev.slne.surf.redis.eventbus

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.InternalEventBus
import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.transport.EventTransport
import dev.slne.surf.eventbus.transport.EventTransportContext
import dev.slne.surf.eventbus.transport.EventTransportFactory
import dev.slne.surf.redis.RedisApi

@OptIn(InternalEventBus::class)
@AutoService(EventTransportFactory::class)
class RedisEventTransportFactory : EventTransportFactory {

    override val provider: Provider = Provider.REDIS

    override fun create(context: EventTransportContext): EventTransport =
        RedisEventTransport(
            RedisApi.create(context.serviceName, context.serializers),
            context
        )
}
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :surf-redis-core:test -PskipIntegration`
Expected: PASS, drei Codec-Tests

Anmerkung zu `RedisApi.freezeAndConnect()`: bis Plan 4 ist es blockierend
(`@Blocking`, Reactor). Der Aufruf in `start()` ist damit vorübergehend eine blockierende
Operation in einer `suspend`-Funktion. Das ist bewusst so belassen und wird in Plan 4 Task 1
aufgelöst; ein `withContext(Dispatchers.IO)` als Zwischenlösung ist erlaubt und erwünscht:

```kotlin
        withContext(Dispatchers.IO) { api.freezeAndConnect() }
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(redis): implement EventTransport over Redis Pub/Sub"
```

---

### Task 5: Testmodul mit beiden Brokern

**Files:**
- Create: `surf-eventbus-test/build.gradle.kts`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/testing/BrokerExtensions.kt`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/testing/RequiresDocker.kt`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/testing/BusFixture.kt`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml` (Testcontainers-Redis)

**Interfaces:**
- Consumes: beide Transports
- Produces:
  - `object RedisBrokerExtension` mit `fun redisUri(): RedisURI`, `fun host(): String`,
    `fun port(): Int`
  - `annotation class RequiresDocker` (Tag `integration`)
  - `object BusFixture` mit
    `fun bus(provider: Provider, service: String, listener: Any? = null): SurfEventBus`
    und `suspend fun awaitCondition(description: String, timeoutMillis: Long = 10_000, condition: () -> Boolean)`

- [ ] **Step 1: Katalog prüfen — kein neuer Eintrag nötig**

Für Redis genügt `GenericContainer` aus `testcontainers-core`, das bereits im Katalog steht.
Ein `testcontainers-redis`-Modul existiert, bringt gegenüber `GenericContainer` mit einem
exponierten Port aber nichts und wird nicht aufgenommen.

Run: `grep -n 'testcontainers' gradle/libs.versions.toml`
Expected: `testcontainers-bom`, `-core`, `-junit`, `-rabbitmq` sind vorhanden.

- [ ] **Step 2: Testmodul anlegen**

`surf-eventbus-test/build.gradle.kts`:

```kotlin
plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    testImplementation(projects.surfEventbusApi)
    testImplementation(projects.surfEventbusCore)
    testImplementation(projects.surfRabbitmqCore)
    testImplementation(projects.surfRedisCore)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.rabbitmq)

    testImplementation("dev.slne.surf.api:surf-api-core:+")
    testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")
}

tasks.test {
    useJUnitPlatform {
        if (providers.gradleProperty("skipIntegration").isPresent) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

In `settings.gradle.kts` — analog zum bestehenden `surf-rabbitmq-test` nur außerhalb von CI,
weil beide Container Zeit kosten:

```kotlin
if (!isCi) {
    include("surf-eventbus-test")
}
```

- [ ] **Step 3: Broker-Extensions schreiben**

```kotlin
package dev.slne.surf.eventbus.testing

import org.redisson.misc.RedisURI
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName

/**
 * One Redis instance shared by every integration test in the JVM.
 *
 * Tests isolate themselves by unique service and topic names rather than by their own
 * container: starting one per class costs seconds each, and sharing is closer to production.
 */
object RedisBrokerExtension {

    private const val PORT = 6379

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(PORT)
            .also { it.start() }
    }

    fun host(): String = container.host
    fun port(): Int = container.getMappedPort(PORT)

    fun redisUri(): RedisURI = RedisURI("redis://${host()}:${port()}")
}
```

`RequiresDocker.kt` — dieselbe Form wie im Rabbit-Testpaket:

```kotlin
package dev.slne.surf.eventbus.testing

import org.junit.jupiter.api.Tag

/** Needs a running Docker daemon. Excluded by `-PskipIntegration`. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
annotation class RequiresDocker
```

- [ ] **Step 4: Fixture schreiben**

`BusFixture.kt` baut einen Bus pro Provider gegen den jeweiligen Container. Der Rabbit-Pfad
nimmt die vorhandene `testConfig()`-Mechanik, der Redis-Pfad die Container-URI. Beide werden
über den Builder verdrahtet, damit kein Plattform-Plugin nötig ist:

```kotlin
package dev.slne.surf.eventbus.testing

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import kotlin.test.assertTrue

object BusFixture {

    private val dataPath = Files.createTempDirectory("eventbus-parity")

    /**
     * A bus on [provider] for [service], with [listener] registered and connected.
     *
     * The container coordinates reach the transports through system properties, which both
     * config layers already consult — see the note in the plan for how each provider picks
     * them up.
     */
    suspend fun bus(provider: Provider, service: String, listener: Any? = null): SurfEventBus {
        val bus = SurfEventBus.builder(service, dataPath)
            .provider(provider)
            .build()

        listener?.let(bus::registerListener)
        bus.freezeAndConnect()
        return bus
    }

    fun uniqueService(prefix: String): String =
        "$prefix-${System.nanoTime().toString(16)}-${java.util.UUID.randomUUID().toString().take(8)}"

    suspend fun awaitCondition(
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

**Verdrahtung der Container-Koordinaten.** Beide Transports lesen ihre Verbindungsdaten aus
ihrer eigenen Konfigurationsschicht, nicht aus dem Bus-Builder. Für die Tests wird deshalb je
Transport der bereits vorhandene Umgebungs-/Property-Weg benutzt:

- RabbitMQ: `SURF_RABBITMQ_HOST`, `SURF_RABBITMQ_PORT`, `SURF_RABBITMQ_USERNAME`,
  `SURF_RABBITMQ_PASSWORD` als Systemproperties über `tasks.test { systemProperty(...) }` sind
  **nicht** ausreichend, weil die Auflösung Umgebungsvariablen liest. Stattdessen wird der
  Test-Task mit `environment(...)` konfiguriert. Da die Container-Ports erst zur Laufzeit
  bekannt sind, geschieht das im Test selbst über einen `@BeforeAll`, der die Werte in eine
  Datei `rabbitmq.yml` im `dataPath` schreibt — den Weg, den `GlobalRabbitMQConfig.getOrLoad`
  ohnehin nimmt.
- Redis: analog über die von `RedisConfig` gelesene YAML im `dataPath`.

Der Fixture-Code dafür wird in Task 6 Step 1 mit dem ersten Parity-Test gemeinsam
geschrieben, weil erst dort sichtbar wird, welche Felder tatsächlich gebraucht werden.

- [ ] **Step 5: Build ausführen**

Run: `./gradlew :surf-eventbus-test:build -PskipIntegration`
Expected: PASS (keine Tests, nur Kompilierung)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add the shared two-broker test module"
```

---

### Task 6: Parity-Suite über beide Transports

**Files:**
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/ParityEvents.kt`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/ProviderParityTest.kt`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/ProviderDivergenceTest.kt`

**Interfaces:**
- Consumes: `BusFixture`, `RedisBrokerExtension`, beide Transports
- Produces: die in der Spec tabellierten Tests 1–18

- [ ] **Step 1: Event-Typen und Listener für die Suite**

`ParityEvents.kt`:

```kotlin
package dev.slne.surf.eventbus.parity

import dev.slne.surf.eventbus.event.BusEvent
import dev.slne.surf.eventbus.event.SubscriptionMode
import dev.slne.surf.eventbus.event.SurfBusEvent
import dev.slne.surf.eventbus.event.SurfSubscribe
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

@Serializable
@BusEvent("parity.plain")
open class ParityPlainEvent(val payload: String) : SurfBusEvent()

@Serializable
@BusEvent("parity.plain.special")
class ParitySpecialEvent(payload: String) : ParityPlainEvent(payload)

@Serializable
@BusEvent("parity.other")
class ParityOtherEvent(val payload: String) : SurfBusEvent()

@Serializable
@BusEvent("faction.abc.disbanded")
class ParitySegmentEvent(val payload: String) : SurfBusEvent()

class BroadcastListener {
    val count = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
    suspend fun on(event: ParityPlainEvent) {
        count.incrementAndGet()
    }
}

class SegmentWildcardListener {
    val count = AtomicInteger()

    @SurfSubscribe(topic = "faction.*.disbanded", mode = SubscriptionMode.BROADCAST)
    suspend fun on(event: ParitySegmentEvent) {
        count.incrementAndGet()
    }
}

class MultiSegmentWildcardListener {
    val count = AtomicInteger()

    @SurfSubscribe(topic = "parity.#", mode = SubscriptionMode.BROADCAST)
    suspend fun on(event: SurfBusEvent) {
        count.incrementAndGet()
    }
}

class OverlappingListener {
    val exact = AtomicInteger()
    val wide = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
    suspend fun onExact(event: ParityPlainEvent) {
        exact.incrementAndGet()
    }

    @SurfSubscribe(topic = "parity.#", mode = SubscriptionMode.BROADCAST)
    suspend fun onWide(event: ParityPlainEvent) {
        wide.incrementAndGet()
    }
}

class SelfExcludingListener {
    val count = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.BROADCAST, includeSelf = false)
    suspend fun on(event: ParityPlainEvent) {
        count.incrementAndGet()
    }
}

class SelfIncludingListener {
    val count = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.BROADCAST, includeSelf = true)
    suspend fun on(event: ParityPlainEvent) {
        count.incrementAndGet()
    }
}

class FailingWithNeighbourListener {
    val neighbour = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.BROADCAST, retry = false)
    suspend fun boom(event: ParityPlainEvent): Unit = error("handler exploded")

    @SurfSubscribe(topic = "parity.plain", mode = SubscriptionMode.BROADCAST, retry = false)
    suspend fun neighbour(event: ParityPlainEvent) {
        neighbour.incrementAndGet()
    }
}

class SharedListener {
    val count = AtomicInteger()

    @SurfSubscribe(mode = SubscriptionMode.SHARED)
    suspend fun on(event: ParityPlainEvent) {
        count.incrementAndGet()
    }
}
```

- [ ] **Step 2: Parity-Tests schreiben (Tests 1–10 der Spec)**

`ProviderParityTest.kt`. Der Kern ist `@ParameterizedTest` über `Provider`, damit jede
Behauptung wörtlich für beide Transports gilt:

```kotlin
package dev.slne.surf.eventbus.parity

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.testing.BusFixture
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals

/**
 * The same behaviour, asserted for every provider.
 *
 * This is the file that makes "pick a provider" a real promise rather than a hope. Anything
 * that legitimately differs lives in [ProviderDivergenceTest] instead — and is asserted there,
 * not left undocumented.
 */
@RequiresDocker
class ProviderParityTest {

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a broadcast event reaches every instance`(provider: Provider) = runBlocking {
        val service = BusFixture.uniqueService("bcast")
        val listeners = (1..3).map { BroadcastListener() }
        val instances = listeners.map { BusFixture.bus(provider, service, it) }
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParityPlainEvent("hello"))

            BusFixture.awaitCondition("all three instances receive the event") {
                listeners.all { it.count.get() == 1 }
            }

            assertEquals(listOf(1, 1, 1), listeners.map { it.count.get() })
        } finally {
            publisher.disconnect()
            instances.forEach { it.disconnect() }
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a wildcard-free topic reaches only its own subscribers`(provider: Provider) =
        runBlocking {
            val listener = BroadcastListener()
            val instance = BusFixture.bus(provider, BusFixture.uniqueService("exact"), listener)
            val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

            try {
                publisher.publish(ParityOtherEvent("not-for-you"))
                delay(1000)
                assertEquals(0, listener.count.get())

                publisher.publish(ParityPlainEvent("for-you"))
                BusFixture.awaitCondition("the matching event arrives") {
                    listener.count.get() == 1
                }
            } finally {
                publisher.disconnect()
                instance.disconnect()
            }
        }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a star matches exactly one segment`(provider: Provider) = runBlocking {
        val listener = SegmentWildcardListener()
        val instance = BusFixture.bus(provider, BusFixture.uniqueService("star"), listener)
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParitySegmentEvent("x"))

            BusFixture.awaitCondition("faction.*.disbanded matches faction.abc.disbanded") {
                listener.count.get() == 1
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a hash matches zero or more segments`(provider: Provider) = runBlocking {
        val listener = MultiSegmentWildcardListener()
        val instance = BusFixture.bus(provider, BusFixture.uniqueService("hash"), listener)
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            // parity.plain has one segment after the prefix, parity.plain.special has two.
            publisher.publish(ParityPlainEvent("one"))
            publisher.publish(ParitySpecialEvent("two"))

            BusFixture.awaitCondition("both depths match parity.#") {
                listener.count.get() == 2
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `two overlapping patterns both fire`(provider: Provider) = runBlocking {
        val listener = OverlappingListener()
        val instance = BusFixture.bus(provider, BusFixture.uniqueService("overlap"), listener)
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParityPlainEvent("x"))

            BusFixture.awaitCondition("both the exact and the wide handler run") {
                listener.exact.get() == 1 && listener.wide.get() == 1
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a handler on a supertype receives a subtype`(provider: Provider) = runBlocking {
        val listener = MultiSegmentWildcardListener()
        val instance = BusFixture.bus(provider, BusFixture.uniqueService("polymorph"), listener)
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParitySpecialEvent("x"))

            // The subscriber declared SurfBusEvent and never named the concrete type: this
            // only works because the resolver falls back to the classpath.
            BusFixture.awaitCondition("the base-type handler receives the subtype") {
                listener.count.get() == 1
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `includeSelf false withholds the publisher's own event`(provider: Provider) =
        runBlocking {
            val listener = SelfExcludingListener()
            val bus = BusFixture.bus(provider, BusFixture.uniqueService("self-off"), listener)

            try {
                bus.publish(ParityPlainEvent("mine"))
                delay(1500)

                assertEquals(0, listener.count.get())
            } finally {
                bus.disconnect()
            }
        }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `includeSelf true delivers the publisher's own event`(provider: Provider) = runBlocking {
        val listener = SelfIncludingListener()
        val bus = BusFixture.bus(provider, BusFixture.uniqueService("self-on"), listener)

        try {
            bus.publish(ParityPlainEvent("mine"))

            BusFixture.awaitCondition("the own event arrives") { listener.count.get() == 1 }
        } finally {
            bus.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a failing handler does not stop its neighbour`(provider: Provider) = runBlocking {
        val listener = FailingWithNeighbourListener()
        val instance = BusFixture.bus(provider, BusFixture.uniqueService("fail"), listener)
        val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParityPlainEvent("x"))

            BusFixture.awaitCondition("the unrelated handler still runs") {
                listener.neighbour.get() >= 1
            }
        } finally {
            publisher.disconnect()
            instance.disconnect()
        }
    }

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `publishing an event nobody subscribes to is not an error`(provider: Provider) =
        runBlocking {
            val publisher = BusFixture.bus(provider, BusFixture.uniqueService("lonely"))

            try {
                // Must not throw: a publisher never knows whether anyone is listening.
                publisher.publish(ParityOtherEvent("nobody-home"))
            } finally {
                publisher.disconnect()
            }
        }
}
```

- [ ] **Step 3: Unbekannten Wire-Typ testen (Test 9 der Spec)**

Dieser Test lässt sich nicht über die Bus-API stellen: der Typauflöser aus Plan 2 findet jede
Klasse, die im selben JVM auf dem Classpath liegt. Ein wirklich unbekannter Typ entsteht nur,
wenn eine Nachricht mit erfundenem Typnamen direkt auf dem Transport landet — genau die Lage
nach einem Deploy, in dem ein Dienst einen Event-Typ gelöscht hat, dessen durable Binding noch
steht. Der Test schreibt daher mit dem Rohclient.

`surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/UnknownTypeTest.kt`:

```kotlin
package dev.slne.surf.eventbus.parity

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.testing.BusFixture
import dev.slne.surf.eventbus.testing.RedisBrokerExtension
import dev.slne.surf.eventbus.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.redisson.Redisson
import org.redisson.client.codec.StringCodec
import org.redisson.config.Config
import kotlin.test.assertEquals

/**
 * A message of a type this process cannot decode must be acked and dropped.
 *
 * Not requeued: a durable SHARED queue would redeliver it forever, and a poison message would
 * stall every later event behind it. The subscriber must also stay healthy afterwards — a
 * dropped message may not take the consumer down with it.
 */
@RequiresDocker
class UnknownTypeTest {

    private val bogusTypeName = "dev.slne.surf.nowhere.DeletedEvent"

    @Test
    fun `rabbit drops an undecodable message and keeps consuming`() = runBlocking {
        val listener = BroadcastListener()
        val bus = BusFixture.bus(
            Provider.RABBIT,
            BusFixture.uniqueService("unknown-rabbit"),
            listener
        )

        try {
            // Frame shape per RabbitBusEventCodec: [2 bytes nameLength][name][CBOR payload].
            val nameBytes = bogusTypeName.encodeToByteArray()
            val body = ByteArray(2 + nameBytes.size).also {
                it[0] = ((nameBytes.size shr 8) and 0xFF).toByte()
                it[1] = (nameBytes.size and 0xFF).toByte()
                nameBytes.copyInto(it, 2)
            }

            RabbitBrokerExtension.newConnection("poison").use { connection ->
                connection.createChannel().use { channel ->
                    channel.basicPublish("surf.events", "parity.plain", null, body)
                }
            }

            delay(2000)
            assertEquals(0, listener.count.get(), "the undecodable message must not be dispatched")

            // The consumer must still be alive: a real event now has to arrive.
            val publisher = BusFixture.bus(Provider.RABBIT, BusFixture.uniqueService("pub"))
            publisher.publish(ParityPlainEvent("after-poison"))

            BusFixture.awaitCondition("the consumer survived the undecodable message") {
                listener.count.get() == 1
            }

            publisher.disconnect()
        } finally {
            bus.disconnect()
        }
    }

    @Test
    fun `redis ignores an undecodable message and keeps consuming`() = runBlocking {
        val listener = BroadcastListener()
        val bus = BusFixture.bus(
            Provider.REDIS,
            BusFixture.uniqueService("unknown-redis"),
            listener
        )

        val raw = Redisson.create(
            Config().apply { useSingleServer().setAddress(RedisBrokerExtension.redisUri().toString()) }
        )

        try {
            raw.getTopic("surf.eventbus.parity.plain", StringCodec.INSTANCE).publish(
                """{"topic":"parity.plain","type":"$bogusTypeName","origin":"x",""" +
                        """"publishedAt":1,"payload":{}}"""
            )

            delay(2000)
            assertEquals(0, listener.count.get(), "the undecodable message must not be dispatched")

            val publisher = BusFixture.bus(Provider.REDIS, BusFixture.uniqueService("pub"))
            publisher.publish(ParityPlainEvent("after-poison"))

            BusFixture.awaitCondition("the subscriber survived the undecodable message") {
                listener.count.get() == 1
            }

            publisher.disconnect()
        } finally {
            raw.shutdown()
            bus.disconnect()
        }
    }
}
```

Der Rabbit-Fall benötigt Zugriff auf `RabbitBrokerExtension` aus `surf-rabbitmq-core`s
Testquellen. Da Testquellen nicht über `api(...)` sichtbar sind, wird in
`surf-eventbus-test/build.gradle.kts` ergänzt:

```kotlin
    testImplementation(testFixtures(projects.surfRabbitmqCore))
```

Ist im Modul keine `java-test-fixtures`-Unterstützung aktiv, wird stattdessen die schlankere
Variante gewählt: `RabbitBrokerExtension` wandert nach
`surf-rabbitmq-core/src/testFixtures/kotlin/...` und das Modul aktiviert das Plugin
`java-test-fixtures`. Alternativ — und mit weniger Build-Änderung — wird eine kleine Kopie der
Container-Verwaltung als `RabbitBrokerExtension` in
`surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/testing/` angelegt. Beides ist
zulässig; die Entscheidung fällt beim Ausführen, je nachdem was die Gradle-Konvention
`dev.slne.surf.api.gradle.core` bereits mitbringt.

- [ ] **Step 4: Divergenz-Tests schreiben (Tests 11–18 der Spec)**

`ProviderDivergenceTest.kt`:

```kotlin
package dev.slne.surf.eventbus.parity

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.exception.AmbiguousTransportException
import dev.slne.surf.eventbus.exception.UnsupportedSubscriptionModeException
import dev.slne.surf.eventbus.testing.BusFixture
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** What differs between providers — asserted, not merely documented. */
@RequiresDocker
class ProviderDivergenceTest {

    private val dataPath = Files.createTempDirectory("divergence")

    @Test
    fun `SHARED on redis fails the start naming handler, mode and provider`() {
        val bus = SurfEventBus.builder(BusFixture.uniqueService("shared-redis"), dataPath)
            .provider(Provider.REDIS)
            .build()

        bus.registerListener(SharedListener())

        val failure = assertFailsWith<UnsupportedSubscriptionModeException> { bus.freeze() }

        val message = failure.message!!
        assertTrue(message.contains("SharedListener#on"), message)
        assertTrue(message.contains("mode SHARED"), message)
        assertTrue(message.contains("provider REDIS"), message)
        assertTrue(message.contains("use provider RABBIT"), message)
    }

    @Test
    fun `SHARED on rabbit delivers to exactly one instance`() = runBlocking {
        val service = BusFixture.uniqueService("shared-rabbit")
        val listeners = (1..3).map { SharedListener() }
        val instances = listeners.map { BusFixture.bus(Provider.RABBIT, service, it) }
        val publisher = BusFixture.bus(Provider.RABBIT, BusFixture.uniqueService("pub"))

        try {
            publisher.publish(ParityPlainEvent("once"))

            BusFixture.awaitCondition("exactly one instance receives it") {
                listeners.sumOf { it.count.get() } == 1
            }

            // Give a wrongly-bound instance time to also receive it.
            delay(1000)

            assertEquals(
                1, listeners.sumOf { it.count.get() },
                "SHARED must deliver once across all instances - more than one means each " +
                        "instance bound its own queue, which would multiply every side effect"
            )
        } finally {
            publisher.disconnect()
            instances.forEach { it.disconnect() }
        }
    }

    @Test
    fun `a SHARED subscription on rabbit survives all instances restarting`() = runBlocking {
        val service = BusFixture.uniqueService("durable")

        // Bring an instance up and down so the durable queue exists and stays.
        BusFixture.bus(Provider.RABBIT, service, SharedListener()).disconnect()

        val publisher = BusFixture.bus(Provider.RABBIT, BusFixture.uniqueService("pub"))
        publisher.publish(ParityPlainEvent("while-down"))
        publisher.disconnect()

        val second = SharedListener()
        val restarted = BusFixture.bus(Provider.RABBIT, service, second)

        try {
            BusFixture.awaitCondition("the event published while offline arrives later") {
                second.count.get() == 1
            }
        } finally {
            restarted.disconnect()
        }
    }

    @Test
    fun `a broadcast event published while nobody listens is lost on both providers`() =
        runBlocking {
            for (provider in Provider.entries) {
                val service = BusFixture.uniqueService("bcast-down")

                val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))
                publisher.publish(ParityPlainEvent("into-the-void"))
                publisher.disconnect()

                val listener = BroadcastListener()
                val late = BusFixture.bus(provider, service, listener)

                try {
                    delay(1500)
                    assertEquals(
                        0, listener.count.get(),
                        "BROADCAST has no queue on either provider, so an event sent while " +
                                "the process was down must not arrive afterwards"
                    )
                } finally {
                    late.disconnect()
                }
            }
        }

    @Test
    fun `two transports on the classpath and no provider configured is refused`() {
        // Both transport modules are test dependencies of this module, so ServiceLoader finds
        // two factories - exactly the situation an operator can create.
        assertFailsWith<AmbiguousTransportException> {
            SurfEventBus.builder(BusFixture.uniqueService("ambiguous"), dataPath).build()
        }
    }
}
```

Test 15 der Spec (Redis warnt bei `retry = true`) und Test 16 (Retry-Leiter auf Rabbit) sind
bereits abgedeckt: die Warnung durch `CapabilityValidatorTest` in Plan 2, die Retry-Leiter
durch die bestehenden `RetryIntegrationTest`/`RetryQueueTest` in `surf-rabbitmq-core`. Test 17
(`includeSelf` mit `SHARED`) durch `EventSubscriptionRegistryTest` in Plan 2. Sie werden hier
nicht wiederholt.

- [ ] **Step 5: Container-Koordinaten verdrahten**

Beide Transports lesen ihre Verbindungsdaten aus ihrer eigenen Konfigurationsschicht. Der
Fixture bekommt daher ein `@BeforeAll`-Äquivalent, das je Provider die passende YAML in den
gemeinsamen `dataPath` schreibt, **bevor** der erste Bus gebaut wird. In `BusFixture`
ergänzen:

```kotlin
    private var prepared = false

    private fun prepareConfigs() {
        if (prepared) return
        prepared = true

        val rabbit = RabbitBrokerExtension.connectionFactory()
        dataPath.resolve("rabbitmq.yml").toFile().writeText(
            """
            host: ${rabbit.host}
            port: ${rabbit.port}
            username: ${rabbit.username}
            password: ${rabbit.password}
            vhost: ${rabbit.virtualHost}
            requestTimeoutSeconds: 10
            """.trimIndent()
        )

        dataPath.resolve("redis.yml").toFile().writeText(
            """
            host: ${RedisBrokerExtension.host()}
            port: ${RedisBrokerExtension.port()}
            """.trimIndent()
        )
    }
```

und `prepareConfigs()` als erste Zeile von `bus(...)` aufrufen.

Die tatsächlichen Dateinamen und Feldnamen sind aus `GlobalRabbitMQConfig` beziehungsweise
`RedisConfig` abzulesen — sie sind die Vorgabe, nicht dieser Plan. Weicht ein Feldname ab,
gilt der Code, und dieser Schritt wird entsprechend angepasst.

- [ ] **Step 6: Suite mit Docker ausführen**

Run: `./gradlew :surf-eventbus-test:test`
Expected: PASS — 20 Parity-Ausführungen (10 Tests × 2 Provider), 2 Unbekannter-Typ-Tests,
5 Divergenz-Tests.

Ohne Docker-Daemon: `./gradlew :surf-eventbus-test:test -PskipIntegration` — alle
Integrationstests werden übersprungen, und der Status ist **„nicht verifiziert"**, nicht
„bestanden". Diese Unterscheidung wird im Abschlussbericht wörtlich so gemacht.

- [ ] **Step 7: Gesamtbuild ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "test: assert identical behaviour across both providers

Ten behavioural claims run against both transports; the five documented
divergences are asserted rather than left to the README."
```

---

## Definition of Done

- Beide Transports implementieren `EventTransport` und werden über `ServiceLoader` gefunden
- Die alte Rabbit-Event-API ist restlos entfernt, ABI-Dump aktualisiert
- `./gradlew build -PskipIntegration` grün
- Mit Docker: 10 Parity-Tests × 2 Provider grün, 2 Unbekannter-Typ-Tests grün,
  5 Divergenz-Tests grün
- Ohne Docker: Status der Integrationstests wird als „nicht verifiziert" berichtet
- Der Redis-Transport deklariert ausschließlich `BROADCAST`; ein `SHARED`-Handler lässt den
  Start mit einer Meldung scheitern, die Handler, Modus und Provider nennt
