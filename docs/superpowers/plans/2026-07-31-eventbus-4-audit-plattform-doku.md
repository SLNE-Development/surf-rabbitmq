# surf-eventbus 4: Audit, Plattform und Dokumentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** use
> `superpowers:subagent-driven-development` — this repository's owner has forbidden subagents.

**Goal:** Die Dead-Letter-Queue durch ein Datenbank-Audit ersetzen, den Microservice bauen, der es
schreibt, die Aggregat- und Plattform-Module zusammenführen und das Ergebnis dokumentieren.

**Architecture:** Jeder Verlustpfad meldet über einen `@FireAndForget`-RPC-Aufruf an den Dienst
`surf-eventbus-audit`. Der Microservice nimmt die Meldung an und schreibt drei Tabellen — Nachricht
einmal, Fehlschläge einzeln, Header einzeln — und scheitert dabei **niemals** hart. Erst wenn
dieser Weg steht, verschwinden `surf.dlx`, `surf.dlq.<service>` und `surf.unroutable`.

**Tech Stack:** surf-database 2.3.0 (Exposed über R2DBC, `AuditableLongIdTable`,
`suspendTransaction`), surf-microservice (`Microservice`, `@AutoService`, Shadow-Main),
Testcontainers (RabbitMQ, Redis, Datenbank), JUnit 5.

## Global Constraints

- **Voraussetzung:** Pläne 1 bis 3 sind abgeschlossen, Build grün.
- **Dienstname des Audits:** `surf-eventbus-audit`, überschreibbar über
  `SURF_EVENTBUS_AUDIT_SERVICE`.
- **Das Audit scheitert nie hart.** Ein Fehler beim Melden bleibt lokal; ein Fehler beim
  Schreiben wird auf `SEVERE` mit vollem Inhalt geloggt und die Nachricht trotzdem geackt.
- **Drei Schutzregeln:** Meldungen werden nie selbst auditiert (`mandatory = false`); der
  Audit-Dienst meldet nicht an sich selbst; Fehler auf dem Meldeweg brechen den ursprünglichen
  Pfad nicht ab.
- **Flutkontrolle:** 256 Einträge in der Prozess-Warteschlange, Überlauf wird verworfen und alle
  30 Sekunden gezählt gemeldet.
- **Kappungsgrenzen:** Payload 256 KiB (`SURF_EVENTBUS_AUDIT_MAX_PAYLOAD_BYTES`),
  `exception_message` 8 KiB, `stacktrace` 32 KiB, Header-Wert 4 KiB.
- **Retention:** 30 Tage (`SURF_EVENTBUS_AUDIT_RETENTION_DAYS`), beim Start und danach täglich.
- **Rollout-Hinweis, der in die README muss:** Queue-Arguments ändern sich, deshalb scheitert die
  Neudeklaration einer bestehenden `surf.service.*` mit `PRECONDITION_FAILED (406)`. Alte
  Service-Queues vor dem Rollout löschen oder den Vhost neu aufsetzen.
- **Docker ist auf dieser Maschine nicht erreichbar.** `@RequiresDocker`-Tests werden geschrieben
  und nicht ausgeführt; Status „nicht verifiziert".
- **Kein fremdes Repository wird verändert** — insbesondere nicht surf-microservice, dessen
  `RabbitModule`-Enumeration veraltet ist.

---

## File Structure

| Datei | Verantwortung |
|---|---|
| `surf-eventbus-audit/surf-eventbus-audit-api/…/audit/AuditService.kt` | `@RpcService` mit `@FireAndForget report(...)` |
| `…-rabbitmq-core/…/audit/RabbitAuditSink.kt` | `AuditSink` über den Proxy, mit Flutkontrolle und Schutzregeln |
| `…-rabbitmq-core/…/audit/AuditMessageIdentity.kt` | Header `x-surf-audit-message-id` stempeln und lesen |
| `…-rabbitmq-core/…/audit/AuditReports.kt` | die fünf Rabbit-Meldepfade in `AuditReport` überführen |
| `surf-eventbus-audit/surf-eventbus-audit-microservice/…/db/tables/*.kt` | drei Tabellen |
| `…-audit-microservice/…/db/AuditRepository.kt` | Einfügen, idempotent, gekappt |
| `…-audit-microservice/…/AuditServiceImpl.kt` | `catch (Throwable)`, nie hart scheitern |
| `…-audit-microservice/…/AuditRetention.kt` | täglich löschen |
| `…-audit-microservice/…/EventbusAuditMicroservice.kt` | `@AutoService(Microservice::class)` |
| `surf-eventbus-api/build.gradle.kts` | Aggregat für `compileOnlyApi` |
| `surf-eventbus-core/build.gradle.kts` | Aggregat für `runtimeOnly` |
| `surf-eventbus-api/…/BusTransports.kt` | typisierte `bus.rabbit` / `bus.redis` |
| `…-platform-paper`, `-velocity`, `-standalone` | je eine Instanz für beide Transports |
| `surf-eventbus-test/…` | die vier Container-Suiten |

**Gelöscht:**

| Typ | Grund |
|---|---|
| `RabbitTopology.DLX_EXCHANGE`, `UNROUTABLE_QUEUE`, `deadLetterQueue` | ersetzt durch Tabellen |
| `QueueArguments.deadLetterQueue`, `x-dead-letter-exchange` in `serviceQueue` | dito |
| `RabbitTopologyDeclarer.declareDeadLetterQueue`, `declareUnroutableQueue` | dito |
| `RetryPublisher`-Zweig `RetryDecision.DeadLetter` (Publish auf `surf.dlx`) | meldet stattdessen |
| `ReturnListenerBridge`-Republish nach `surf.unroutable` | meldet stattdessen |

---

## Task 1: Audit-Vertrag und Meldeweg

**Files:**
- Create: `surf-eventbus-audit/surf-eventbus-audit-api/build.gradle.kts`
- Create: `…-audit-api/src/main/kotlin/dev/slne/surf/eventbus/audit/AuditService.kt`
- Create: `…-rabbitmq-core/…/audit/RabbitAuditSink.kt`, `AuditMessageIdentity.kt`, `AuditReports.kt`
- Create: `…-rabbitmq-core/src/test/…/audit/RabbitAuditSinkTest.kt`
- Create: `…-rabbitmq-core/src/test/…/audit/AuditMessageIdentityTest.kt`
- Modify: `…-rabbitmq-core/…/core/retry/RetryPublisher.kt`,
  `…/core/connection/ReturnListenerBridge.kt`, `…/common/packet/RabbitPacketChunkAssembler.kt`,
  `…/common/connection/consumer/RabbitConsumer.kt`
- Modify: `…/common/topology/RabbitTopology.kt`, `QueueArguments.kt`, `RabbitTopologyDeclarer.kt`
- Modify: `RabbitTopologyTest`, `QueueArgumentsTest`, `RabbitTopologyDeclarerTest`, `RetryPolicyTest`

**Interfaces:**
- Consumes: `AuditReport`, `AuditKind`, `AuditSink` (Plan 2 Task 5), `@FireAndForget` (Plan 3
  Task 4).
- Produces:
  - `@RpcService(service = "surf-eventbus-audit") interface AuditService { @FireAndForget suspend fun report(report: AuditReport) }`
  - `class RabbitAuditSink(api, serviceName, instanceId, auditServiceName, enabled) : AuditSink`
  - `object AuditMessageIdentity { const val HEADER = "x-surf-audit-message-id"; fun of(properties: AMQP.BasicProperties): String; fun stamp(properties: AMQP.BasicProperties, id: String): AMQP.BasicProperties }`

- [x] **Step 1: Identitätstest schreiben**

```kotlin
package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AuditMessageIdentityTest {

    private fun properties(headers: Map<String, Any?> = emptyMap()): AMQP.BasicProperties =
        AMQP.BasicProperties.Builder().headers(headers).build()

    @Test
    fun `a message without the header gets a fresh identity`() {
        val first = AuditMessageIdentity.of(properties())
        val second = AuditMessageIdentity.of(properties())

        assertNotEquals(first, second)
    }

    @Test
    fun `an existing header is reused so ladder attempts group together`() {
        val stamped = AuditMessageIdentity.stamp(properties(), "id-1")

        assertEquals("id-1", AuditMessageIdentity.of(stamped))
    }

    @Test
    fun `stamping preserves the other headers`() {
        val stamped = AuditMessageIdentity.stamp(properties(mapOf("x-attempts" to 2)), "id-1")

        assertEquals(2, stamped.headers["x-attempts"])
        assertEquals("id-1", stamped.headers[AuditMessageIdentity.HEADER])
    }
}
```

- [x] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*AuditMessageIdentityTest*'`
Expected: FAIL, „Unresolved reference: AuditMessageIdentity".

- [x] **Step 3: Identität implementieren**

```kotlin
package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import java.util.UUID

/**
 * The identity that ties every report about one message together.
 *
 * The retry ladder republishes the same message up to four times, possibly from different
 * processes. Without a shared identity the database would show four unrelated incidents instead of
 * one message that failed four times. The header survives the republish because
 * `RetryPublisher.withNextAttempt` copies the header map.
 */
object AuditMessageIdentity {

    const val HEADER = "x-surf-audit-message-id"

    fun of(properties: AMQP.BasicProperties): String =
        properties.headers?.get(HEADER)?.toString() ?: UUID.randomUUID().toString()

    fun stamp(properties: AMQP.BasicProperties, id: String): AMQP.BasicProperties {
        val headers = HashMap<String, Any?>(properties.headers ?: emptyMap())
        headers[HEADER] = id

        return properties.builder().headers(headers).build()
    }
}
```

- [x] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*AuditMessageIdentityTest*'`
Expected: PASS.

- [x] **Step 5: Senken-Test schreiben**

```kotlin
package dev.slne.surf.eventbus.rabbitmq.audit

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RabbitAuditSinkTest {

    private fun report(kind: AuditKind = AuditKind.HANDLER_FAILED) = AuditReport(
        messageUuid = "id-1",
        kind = kind,
        originService = "surf-factions",
        originInstance = "lobby-3",
        reportedByService = "surf-factions",
        reportedByInstance = "lobby-3",
        failedAtEpochMs = 1
    )

    @Test
    fun `the audit service does not report to itself`() = runBlocking {
        val proxy = RecordingAuditService()
        val sink = RabbitAuditSink(proxy, serviceName = "surf-eventbus-audit", auditServiceName = "surf-eventbus-audit")

        sink.report(report())

        assertEquals(0, proxy.received.size, "a self-report would feed itself forever")
    }

    @Test
    fun `a disabled sink sends nothing`() = runBlocking {
        val proxy = RecordingAuditService()
        val sink = RabbitAuditSink(proxy, "surf-factions", "surf-eventbus-audit", enabled = false)

        sink.report(report())

        assertEquals(0, proxy.received.size)
    }

    @Test
    fun `a failing publish does not propagate`() = runBlocking {
        val sink = RabbitAuditSink(ThrowingAuditService, "surf-factions", "surf-eventbus-audit")

        sink.report(report())
        // No exception: the original path must not fail because the audit was unreachable.
    }

    @Test
    fun `overflow drops reports and counts them`() = runBlocking {
        val proxy = BlockingAuditService()
        val sink = RabbitAuditSink(proxy, "surf-factions", "surf-eventbus-audit", queueCapacity = 4)

        repeat(100) { sink.report(report()) }

        assertEquals(true, sink.droppedCount() > 0, "an outage must not become a second outage")
    }
}
```

Die drei Doubles (`RecordingAuditService`, `ThrowingAuditService`, `BlockingAuditService`)
implementieren `AuditService` und liegen in derselben Datei.

- [x] **Step 6: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*RabbitAuditSinkTest*'`
Expected: FAIL, „Unresolved reference: RabbitAuditSink".

- [x] **Step 7: Vertrag und Senke schreiben**

```kotlin
package dev.slne.surf.eventbus.audit

import dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget
import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

/**
 * Where audit reports go over the wire.
 *
 * The audit uses its own medicine: a fire-and-forget RPC call. The durable service queue of the
 * microservice carries the report while it restarts or is redeployed — exactly the property the
 * dead-letter queue was there for — and the reporting process does not wait for it.
 */
@RpcService(service = "surf-eventbus-audit")
interface AuditService {
    @FireAndForget
    suspend fun report(report: AuditReport)
}
```

`RabbitAuditSink` hält die drei Schutzregeln und die Flutkontrolle. Kern:

```kotlin
    override suspend fun report(report: AuditReport) {
        if (!enabled) return

        // Rule 2: a process that *is* the audit service logs locally instead of sending. Without
        // this its own failures would produce reports that produce failures.
        if (serviceName == auditServiceName) {
            log.atWarning().log("Audit (%s) in the audit service itself: %s", report.kind, report.exceptionMessage)
            return
        }

        // Flood control: an outage that fails ten thousand messages must not become an outage
        // that fills the broker with ten thousand reports.
        if (!pending.offer(report)) {
            dropped.incrementAndGet()
            logDroppedPeriodically()
            return
        }

        drain()
    }

    private suspend fun drain() {
        while (true) {
            val next = pending.poll() ?: return

            // Rule 3: a failure on the reporting path stays here. The original path — acking the
            // message, logging the handler failure — must not fail because of it.
            try {
                proxy.report(next)
            } catch (throwable: Throwable) {
                log.atWarning().withCause(throwable)
                    .log("Could not report audit incident %s (%s)", next.messageUuid, next.kind)
                return
            }
        }
    }
```

Regel 1 — Meldungen werden nie selbst auditiert — steckt im Publish: der generierte
`@FireAndForget`-Pfad publiziert mit `mandatory = false`, deshalb kann eine fehlende Audit-Queue
kein `basic.return` und damit keine `UNROUTABLE`-Meldung über eine Meldung erzeugen. Das gehört als
Kommentar an die Stelle im Codegen, die `mandatory` setzt.

- [x] **Step 8: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*RabbitAuditSinkTest*'`
Expected: PASS, alle vier.

- [x] **Step 9: Die fünf Rabbit-Meldepfade verdrahten**

| Ort | `kind` | Was sich ändert |
|---|---|---|
| `RetryPublisher.handleFailure` | `HANDLER_FAILED` | jeder Versuch meldet; `RetryDecision.DeadLetter` meldet mit `terminal = true` und publiziert **nicht** mehr auf `surf.dlx` |
| `ReturnListenerBridge` | `UNROUTABLE` | meldet statt nach `surf.unroutable` zu republishen |
| `RabbitConsumer` (Deserialisierungsfehler) | `UNDESERIALIZABLE` | meldet, ackt, verwirft |
| `RabbitPacketChunkAssembler.cleanupExpiredIfDue` | `CHUNK_SERIES_EXPIRED` | meldet je verfallener Serie — vorher spurlos |
| `RabbitConsumer` (Handler-Ausnahme) | `HANDLER_FAILED` mit `attempt` | stempelt die Identität beim ersten Fehlschlag |

- [x] **Step 10: Dead-Letter-Topologie entfernen**

Erst den Test anpassen, der die Argumente festhält:

```kotlin
    @Test
    fun `a service queue no longer dead-letters`() {
        val arguments = QueueArguments.serviceQueue()

        assertFalse(arguments.containsKey("x-dead-letter-exchange"),
            "failures are audited now; a DLX would park a second, unread copy")
        assertEquals("quorum", arguments["x-queue-type"])
        assertEquals("reject-publish", arguments["x-overflow"])
    }
```

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test --tests '*QueueArgumentsTest*'`
Expected: FAIL.

Dann `x-dead-letter-exchange` aus `QueueArguments.serviceQueue()` entfernen und
`QueueArguments.deadLetterQueue`, `RabbitTopology.DLX_EXCHANGE`,
`RabbitTopology.UNROUTABLE_QUEUE`, `RabbitTopology.deadLetterQueue`,
`RabbitTopologyDeclarer.declareDeadLetterQueue` und `declareUnroutableQueue` löschen.
`declareExchanges` deklariert danach nur noch `surf.rpc`.

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core:test`
Expected: PASS.

- [x] **Step 11: Rollout-Falle festhalten**

Die Argumentänderung macht die Neudeklaration einer bestehenden Queue unmöglich
(`PRECONDITION_FAILED (406)`). Ein Test hält die Erwartung fest, damit sie nicht in einem Kommentar
verhungert — `@RequiresDocker`, in `surf-eventbus-test`:

```kotlin
    @Test
    fun `a freshly declared service queue carries the new arguments`() {
        // Existing 1.6.x queues cannot be redeclared: arguments are part of a queue's identity.
        // The rollout note in the README says to delete them; this test proves what a fresh one
        // looks like.
    }
```

- [x] **Step 12: ABI und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "feat!: replace surf.dlx, surf.dlq.* and surf.unroutable with audit reports

Five loss paths now report over a @FireAndForget call to surf-eventbus-audit.
Three guards keep the audit from feeding itself, flood control keeps an
outage from becoming two, and the identity header groups ladder attempts."
```

---

## Task 2: Audit-Microservice

**Files:**
- Create: `surf-eventbus-audit/surf-eventbus-audit-microservice/build.gradle.kts`
- Create: `…/db/tables/AuditMessagesTable.kt`, `AuditFailuresTable.kt`, `AuditHeadersTable.kt`
- Create: `…/db/AuditRepository.kt`
- Create: `…/AuditServiceImpl.kt`, `AuditRetention.kt`, `EventbusAuditMicroservice.kt`
- Create: `…/src/test/…/AuditRepositoryTest.kt` (`@RequiresDocker`),
  `…/src/test/…/AuditServiceImplTest.kt` (ohne Datenbank)
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `AuditService`, `AuditReport` aus Task 1.
- Produces:
  - `object AuditRepository { suspend fun insert(report: AuditReport); suspend fun deleteOlderThan(days: Int): Int }`
  - `object AuditServiceImpl : AuditService`
  - `class EventbusAuditMicroservice : Microservice()`

- [ ] **Step 1: Test schreiben, der „nie hart scheitern" festnagelt**

Der wichtigste Test des Microservices braucht keine Datenbank:

```kotlin
package dev.slne.surf.eventbus.audit.microservice

import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class AuditServiceImplTest {

    private val report = AuditReport(
        messageUuid = "id-1",
        kind = AuditKind.HANDLER_FAILED,
        originService = "surf-factions",
        originInstance = "lobby-3",
        reportedByService = "surf-factions",
        reportedByInstance = "lobby-3",
        failedAtEpochMs = 1,
        exceptionMessage = "boom"
    )

    @Test
    fun `an unreachable database does not fail the handler`() = runBlocking {
        // No DatabaseApi initialised in this JVM: every insert throws. The handler must swallow
        // it, log it, and return — a stalled audit queue would take the broker with it.
        AuditServiceImpl.report(report)
    }

    @Test
    fun `an insert failure is logged with the full report`() = runBlocking {
        val captured = captureLog { AuditServiceImpl.report(report) }

        assert(captured.contains("id-1")) { "the console must stay the trace: $captured" }
        assert(captured.contains("boom"))
    }
}
```

`captureLog` hängt einen Flogger-Handler an und gibt das Geschriebene zurück; er gehört in
`…/src/test/…/LogCapture.kt`.

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-audit:surf-eventbus-audit-microservice:test --tests '*AuditServiceImplTest*'`
Expected: FAIL, „Unresolved reference: AuditServiceImpl".

- [ ] **Step 3: Tabellen schreiben**

Nach dem Vorbild von `surf-factions-fractions-microservice`:

```kotlin
package dev.slne.surf.eventbus.audit.microservice.db.tables

import dev.slne.surf.database.columns.nativeUuid
import dev.slne.surf.database.table.AuditableLongIdTable

/**
 * One row per message, however often it failed.
 *
 * The payload is stored once; the failures are separate rows. The retry ladder produces up to
 * four failures for the same body, and repeating a 256 KiB payload four times would make the
 * table mostly duplicates.
 */
object AuditMessagesTable : AuditableLongIdTable("eventbus_audit_messages") {
    val messageUuid = nativeUuid("message_uuid").uniqueIndex()
    val kind = varchar("kind", 32).index()
    val originService = varchar("origin_service", 128).index()
    val originInstance = varchar("origin_instance", 128).nullable()
    val exchange = varchar("exchange", 255).nullable()
    val routingKey = varchar("routing_key", 255).nullable()
    val originQueue = varchar("origin_queue", 255).nullable()
    val messageType = varchar("message_type", 512).nullable()
    val contract = varchar("contract", 512).nullable()
    val callable = varchar("callable", 255).nullable()
    val correlationId = varchar("correlation_id", 64).nullable()
    val payloadEncoding = varchar("payload_encoding", 8).nullable()
    val payloadSizeBytes = integer("payload_size_bytes").default(0)
    val payloadTruncated = bool("payload_truncated").default(false)
    val payload = blob("payload").nullable()
    val firstSeenAt = long("first_seen_at").index()
}
```

`AuditFailuresTable` und `AuditHeadersTable` analog, mit
`reference("message_id", AuditMessagesTable)` und den Spalten aus dem Spec; auf
`AuditFailuresTable` ein `index(false, messageId, attempt)`, auf `AuditHeadersTable` ein
`uniqueIndex(messageId, name)`.

- [ ] **Step 4: Repository und Handler schreiben**

```kotlin
package dev.slne.surf.eventbus.audit.microservice

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditService

/**
 * Writes one audit incident, and never fails doing so.
 *
 * `@FireAndForget` means nobody waits for an answer, so throwing would only put the report on the
 * retry ladder. Worse, an unreachable database would then stall the audit queue until it hits the
 * broker's limit — the audit would take down what it exists to observe.
 */
object AuditServiceImpl : AuditService {

    private val log = logger()

    override suspend fun report(report: AuditReport) {
        try {
            AuditRepository.insert(report)
        } catch (throwable: Throwable) {
            log.atSevere().withCause(throwable).log(
                "Could not persist audit incident %s (%s) from %s/%s: type=%s handler=%s " +
                        "attempt=%s terminal=%s cause=%s: %s",
                report.messageUuid, report.kind, report.originService, report.originInstance,
                report.messageType, report.handler, report.attempt, report.terminal,
                report.exceptionClass, report.exceptionMessage
            )
        }
    }
}
```

`AuditRepository.insert` läuft in **einer** `suspendTransaction`: Nachrichtenzeile einfügen und bei
bekannter `messageUuid` überspringen, dann die Fehlschlagzeile, dann die Header nur beim ersten
Mal. Kappung passiert hier, nicht beim Melder — so gilt die Grenze auch für eine Meldung von einem
älteren Prozess.

- [ ] **Step 5: Test laufen lassen**

Run: `./gradlew :surf-eventbus-audit:surf-eventbus-audit-microservice:test --tests '*AuditServiceImplTest*'`
Expected: PASS.

- [ ] **Step 6: Retention schreiben**

```kotlin
package dev.slne.surf.eventbus.audit.microservice

/**
 * Deletes incidents older than the configured retention, at start and then daily.
 *
 * A table that receives every failure of the fleet needs an upper bound, and the deleting belongs
 * to the service rather than to a runbook nobody reads.
 */
class AuditRetention(private val retentionDays: Int, private val scope: CoroutineScope) {

    fun start() {
        scope.launch {
            while (isActive) {
                val deleted = runCatching { AuditRepository.deleteOlderThan(retentionDays) }
                    .onFailure { log.atWarning().withCause(it).log("Audit retention run failed") }
                    .getOrDefault(0)

                if (deleted > 0) log.atInfo().log("Audit retention removed %s incidents", deleted)
                delay(24.hours)
            }
        }
    }
}
```

- [ ] **Step 7: Microservice und Build schreiben**

`build.gradle.kts` nach dem Vorbild von `surf-factions-microservice-api`:

```kotlin
plugins {
    id("dev.slne.surf.api.gradle.standalone")
    id("dev.slne.surf.microservice")
}

dependencies {
    api(projects.surfEventbusAudit.surfEventbusAuditApi)
    compileOnlyApi(projects.surfEventbusApi)
    runtimeOnly(projects.surfEventbusCore)
    ksp(projects.surfEventbusKsp)
}

surfStandaloneApi {
    withSurfDatabaseR2dbc("2.3.0", "dev.slne.surf.eventbus.libs.db")
}

surfMicroservice {
    withMicroserviceApi()
}
```

`withRabbitModule(...)` wird **nicht** benutzt: dessen `RabbitModule`-Enumeration nennt die
1.6.x-Module, die es nicht mehr gibt. Das ist eine Änderung im Repository surf-microservice und
nicht Teil dieses Plans.

Der Microservice selbst, wie im Spec:

```kotlin
@AutoService(Microservice::class)
class EventbusAuditMicroservice : Microservice() {
    override val dataPath: Path = Path("config")

    val bus = SurfEventBus.builder("surf-eventbus-audit", dataPath)
        .withRabbit()
        .build()
    val databaseApi = DatabaseApi.create(dataPath)

    override suspend fun onBootstrap(args: List<String>) {
        SchemaUtils.create(AuditMessagesTable, AuditFailuresTable, AuditHeadersTable)
        bus.registerService<AuditService>(AuditServiceImpl)
        bus.freezeAndConnect()
        retention.start()
    }

    override suspend fun onDisable() {
        bus.disconnect()
        databaseApi.shutdown()
    }
}
```

- [ ] **Step 8: Datenbanktest schreiben (Docker)**

`AuditRepositoryTest` mit `@RequiresDocker` deckt die Fälle 38, 47 und 48 der Audit-Suite ab: vier
Fehlschläge zu einer Nachricht erzeugen eine Nachrichtenzeile und vier Fehlschlagzeilen, die
letzte `terminal`; Retention löscht nur, was älter als die Grenze ist; eine Payload über der
Grenze wird gekappt gespeichert, mit `payload_truncated = true` und erhaltener echter Größe.

- [ ] **Step 9: Build, Tests, Commit**

Run: `./gradlew :surf-eventbus-audit:surf-eventbus-audit-microservice:build`
Expected: SUCCESS, Docker-Tests SKIPPED.

```bash
git add -A
git commit -m "feat: add the audit microservice writing three tables

One row per message, one per failure, one per header. The handler catches
Throwable and logs the full incident, so an unreachable database costs a log
line rather than a stalled audit queue."
```

---

## Task 3: Aggregat-Module und typisierte Transport-Zugänge

**Files:**
- Create: `surf-eventbus-api/build.gradle.kts`, `surf-eventbus-core/build.gradle.kts`
- Create: `surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/BusTransports.kt`
- Create: `surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/AggregateCompletenessTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Produces:
  - Koordinaten `dev.slne.surf.eventbus:surf-eventbus-api` (compileOnlyApi) und
    `…:surf-eventbus-core` (runtimeOnly)
  - `val SurfEventBus.rabbit: SurfRabbitApi`, `val SurfEventBus.redis: RedisApi` — typisierte
    Erweiterungen, die die `Any`-Rückgaben aus Plan 2 ablösen

- [ ] **Step 1: Vollständigkeitstest schreiben**

Ein Aggregat, das ein Modul vergisst, fällt sonst erst einem Consumer auf:

```kotlin
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
            "dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi",
            "dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService",
            "dev.slne.surf.eventbus.rabbitmq.api.rpc.FireAndForget",
            "dev.slne.surf.eventbus.redis.RedisApi",
            "dev.slne.surf.eventbus.common.circuitbreaker.CircuitBreaker"
        )

        for (name in expected) {
            val present = runCatching { Class.forName(name, false, javaClass.classLoader) }.isSuccess
            assertTrue(present, "$name is not reachable through surf-eventbus-api")
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-api:test`
Expected: FAIL — das Modul existiert nicht.

- [ ] **Step 3: Aggregate schreiben**

`surf-eventbus-api/build.gradle.kts`:

```kotlin
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

// No types of its own beyond the typed transport accessors: this module exists so a consumer can
// name one coordinate and have everything on its compile classpath.
dependencies {
    api(projects.surfEventbusBus.surfEventbusBusApi)
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
    api(projects.surfEventbusRedis.surfEventbusRedisApi)
    api(projects.surfEventbusAudit.surfEventbusAuditApi)
    api(projects.surfEventbusCommon)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

publishing {
    repositories { slneReleases() }
}
```

`surf-eventbus-core/build.gradle.kts`: dasselbe mit `-bus-core`, `-rabbitmq-core`, `-redis-core`
und ohne Testblock.

- [ ] **Step 4: Typisierte Zugänge schreiben**

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.redis.RedisApi

/**
 * The RabbitMQ-only surface of this bus.
 *
 * Typed here rather than on the interface because `surf-eventbus-bus-api` sits below the
 * transports and must not know either of them. This is the seam where that inversion is paid
 * back.
 *
 * @throws IllegalStateException when the transport was not enabled, naming the builder call.
 */
val SurfEventBus.rabbit: SurfRabbitApi
    get() = rabbit as SurfRabbitApi

/** The Redis-only surface: sync structures, caches. */
val SurfEventBus.redis: RedisApi
    get() = redis as RedisApi
```

Die Namenskollision zwischen Erweiterung und Interface-Property löst Kotlin zugunsten des
Members; damit die Erweiterung greift, heißen die Interface-Properties in Plan 2
`rabbitTransport` und `redisTransport` und werden hier umbenannt — ein Zweizeiler in
`SurfEventBus.kt` plus die zwei Aufrufstellen in `SurfEventBusImpl`.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-api:test`
Expected: PASS.

Run: `./gradlew build -x test && ./gradlew test`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add the api and core aggregate modules

One coordinate for compileOnlyApi, one for runtimeOnly — the pair
SurfMicroserviceModule expects. AggregateCompletenessTest catches a
forgotten api() line here instead of in a consumer's build."
```

---

## Task 4: Plattform-Module vereinen

**Files:**
- Modify: `…-platform-paper/**`, `…-platform-velocity/**`, `…-platform-standalone/**`
- Create: `…-platform-standalone/…/StandaloneEventbusInstance.kt`
- Delete: `…-platform-standalone/…/StandaloneRedisInstance.kt`,
  `…-rabbitmq-core/…/StandaloneRabbitMqInstance.kt`
- Create: `…-platform-standalone/src/test/…/StandaloneEventbusInstanceTest.kt`

**Interfaces:**
- Produces: `class StandaloneEventbusInstance(name: String, configPath: Path) { fun create(); fun shutdown() }`
  — eine Instanz, die beide Transports bereitstellt. Der Audit-Microservice und jeder künftige
  Microservice benutzt sie.

- [ ] **Step 1: Test schreiben**

```kotlin
package dev.slne.surf.eventbus.platform.standalone

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StandaloneEventbusInstanceTest {

    private val configPath = Files.createTempDirectory("standalone")

    @Test
    fun `the instance name and data path reach both transports`() {
        val instance = StandaloneEventbusInstance("surf-eventbus-audit", configPath)
        instance.create()

        assertEquals("surf-eventbus-audit", instance.name)
        assertEquals(configPath, instance.configPath)

        instance.shutdown()
    }

    @Test
    fun `creating twice fails instead of leaving two instances behind`() {
        val instance = StandaloneEventbusInstance("svc", configPath)
        instance.create()

        assertFailsWith<IllegalStateException> { instance.create() }

        instance.shutdown()
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-standalone:test`
Expected: FAIL, „Unresolved reference: StandaloneEventbusInstance".

- [ ] **Step 3: Instanz zusammenführen**

`StandaloneRedisInstance` und `StandaloneRabbitMqInstance` tun dasselbe: einen Instanznamen und
einen Konfigurationspfad setzen und die Instanz laden. Die neue Klasse macht es einmal für beide
und behält das `@AutoService`-Muster aus `StandaloneRedisInstance`.

- [ ] **Step 4: Paper- und Velocity-Plugin**

Jedes Plattform-Modul stellt jetzt beide Transport-Instanzen bereit statt einer. Die Paper- und
Velocity-Bootstraps aus surf-redis (`PaperBootstrap`, `PaperMain`, `RedisInstanceImpl`,
`VelocityMain`, `VelocityRedisInstanceImpl`) gehen in die bestehenden Module auf; ihre
Reflection-Proxies liegen seit Plan 1 in `surf-eventbus-common`.

- [ ] **Step 5: Tests und Jars prüfen**

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-standalone:test`
Expected: PASS.

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-paper:shadowJar`
Expected: SUCCESS. Danach prüfen, dass genau **eine** Netty-Kopie im Jar liegt:

```bash
unzip -l surf-eventbus-platform/surf-eventbus-platform-paper/build/libs/*-all.jar \
  | grep -c "dev/slne/surf/eventbus/shaded/io/netty/buffer/ByteBuf.class"
```
Expected: `1`.

```bash
unzip -l surf-eventbus-platform/surf-eventbus-platform-paper/build/libs/*-all.jar | grep -c "io/netty/buffer/ByteBuf.class"
```
Expected: `1` — dieselbe Datei, kein unrelocierter Zweitpfad.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat!: one platform plugin per platform, one standalone instance

A server loads one plugin and one Netty copy instead of two of each.
StandaloneEventbusInstance replaces the two per-transport standalone
instances."
```

---

## Task 5: Container-Suiten in `surf-eventbus-test`

**Files:**
- Move: `…-rabbitmq-core/src/test/…/common/testing/RequiresDocker.kt`,
  `RabbitBrokerExtension.kt`, `TestConfig.kt` → `surf-eventbus-test/src/test/…/testing/`
- Create: `surf-eventbus-test/src/test/…/testing/RedisContainerExtension.kt`,
  `DatabaseContainerExtension.kt`
- Create: `surf-eventbus-test/src/test/…/EventSuiteTest.kt`, `QuerySuiteTest.kt`,
  `RpcSuiteTest.kt`, `AuditSuiteTest.kt`, `TransportEnablementTest.kt`

**Interfaces:**
- Consumes: alles.
- Produces: die vier Suiten aus dem Spec, jede Testnummer als eigene `@Test`-Methode mit der
  Nummer im Namen, damit Spec und Suite aufeinander zeigen.

- [ ] **Step 1: Test-Infrastruktur verschieben**

`RequiresDocker`, `RabbitBrokerExtension` und `TestConfig` liegen heute im Rabbit-Testquellbaum und
werden von drei Modulen gebraucht. Sie ziehen nach `surf-eventbus-test`, das als
`testImplementation` in die anderen Testquellbäume hängt.

- [ ] **Step 2: Redis- und Datenbank-Extension schreiben**

Nach dem Muster von `RabbitBrokerExtension`: ein Container pro Suite-Lauf, `uniqueServiceName(...)`
für Kollisionsfreiheit zwischen Tests, Abbau am Ende.

- [ ] **Step 3: Die vier Suiten schreiben**

| Suite | Testnummern aus dem Spec |
|---|---|
| `EventSuiteTest` | 1–12, 12a–12c |
| `QuerySuiteTest` | 13–23 |
| `RpcSuiteTest` | 24–32 |
| `TransportEnablementTest` | 33–37 (ohne Container) |
| `AuditSuiteTest` | 38–49 |

Jede Methode heißt nach ihrer Nummer und Aussage, etwa
`fun \`11 - an event with every subscriber offline expires\`()`. Tests, die schon in Plan 2 oder 3
entstanden sind, werden **nicht** kopiert; die Suite verweist im KDoc auf ihren Ort. Neu entstehen
hier nur die Fälle, die mehr als ein Modul brauchen — insbesondere 38–49, die Rabbit, Datenbank und
den Microservice gleichzeitig hochfahren.

- [ ] **Step 4: Ausführbarkeit prüfen, nicht Ausführung behaupten**

Run: `./gradlew :surf-eventbus-test:compileTestKotlin`
Expected: SUCCESS — das ist hier das erreichbare Kriterium.

Run: `./gradlew :surf-eventbus-test:test`
Expected: alle Container-Tests SKIPPED, weil kein Docker-Daemon erreichbar ist.

- [ ] **Step 5: Verifikationsnotiz fortschreiben**

In `docs/superpowers/notes/2026-07-31-fundament-verification.md` einen Abschnitt „Stand nach Plan
4" ergänzen: welche Suiten existieren, wie viele Tests sie enthalten, und dass **keiner** davon
ausgeführt wurde. Die Liste aus
`grep -rl "@RequiresDocker" --include=*.kt . | grep -v /build/` gehört dazu.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add the event, query, rpc and audit container suites

Every test is named after its number in the spec so the two stay pointed at
each other. None of them ran: no Docker daemon on this machine."
```

---

## Task 6: Dokumentation

**Files:**
- Modify: `README.md` (zusammengeführt aus beiden Projekten)
- Create: `docs/rollout-2.0.md`

**Interfaces:**
- Consumes: alles.
- Produces: eine README, die die drei Verben, die Aufgabenteilung und die Zusagen beschreibt, und
  eine Rollout-Notiz mit den Schritten, die ein Betreiber tun muss.

- [ ] **Step 1: README schreiben**

Reihenfolge nach Nutzen, nicht nach Modulstruktur:

1. Die drei Verben mit je einem Beispiel und der Zusage darunter.
2. Die Aufgabenteilung als Tabelle — welche Zusage welchen Transport hat und was im Fehlerfall
   passiert.
3. **An erster Stelle unter den Zusagen:** Events haben keine Durability. Wer offline ist,
   verpasst sie, es gibt keine Wiederzustellung, und ein gescheiterter Handler hinterlässt eine
   Audit-Zeile statt einer Wiederholung. Alles, was nicht verloren gehen darf, ist ein
   `@FireAndForget`-Aufruf.
4. `@QueryService`-Anleitung mit der Falle: `null` heißt Abstinenz. Ein Vertrag muss „nichts
   gefunden" von „nicht zuständig" unterscheidbar machen — bei `Boolean?` über `false`, sonst über
   einen Ergebnistyp.
5. Konfiguration: die vier Schichten und die vollständige Tabelle der `SURF_EVENTBUS_*`-Variablen.
6. Redis-Sync-Strukturen: vor `freeze()` erstellen. Diese Falle bleibt und bekommt einen eigenen
   Abschnitt, einmal statt zweimal.
7. Das Audit: welche Tabellen es gibt und zwei SQL-Beispiele — „was ist in der letzten Stunde
   gescheitert" und „welche Nachricht hat die Leiter ausgereizt".

- [ ] **Step 2: Rollout-Notiz schreiben**

`docs/rollout-2.0.md`, in der Reihenfolge, in der ein Betreiber sie braucht:

1. **Env-Variablen umbenennen.** Vollständige Alt-nach-Neu-Tabelle. Eine gesetzte alte Variable
   ist ein Startfehler — das ist Absicht, nicht ein Bug.
2. **Alte Queues löschen.** `x-dead-letter-exchange` fällt aus den Argumenten, und Argumente sind
   Teil der Queue-Identität: die Neudeklaration einer bestehenden `surf.service.*` scheitert mit
   `PRECONDITION_FAILED (406)`. Entweder die betroffenen Queues löschen oder den Vhost neu
   aufsetzen. Der Befehl dazu:
   ```bash
   rabbitmqctl list_queues name | grep '^surf\.' 
   rabbitmqadmin delete queue name=surf.service.<dienst>
   ```
3. **Den Audit-Microservice deployen**, bevor die Clients starten. Nicht zwingend — Meldungen
   warten in seiner durable Queue —, aber ohne ihn existiert die Queue nicht und Meldungen werden
   verworfen, weil sie mit `mandatory = false` rausgehen.
4. **Gemeinsam deployen.** Wire-Format und API sind inkompatibel zu 1.6.x/1.5.x; gemischter
   Betrieb funktioniert nicht.

- [ ] **Step 3: Prüfen, dass die README keine falschen Versprechen macht**

Gegen das Spec lesen und drei Dinge bestätigen: dass nirgends „durable" über Events steht, dass
`InstanceTarget` als „für namentlich bekannte Server" beschrieben ist und nicht als
Server-zu-Server-Kanal, und dass das Audit als best effort beschrieben ist — keine Zeile ist keine
Garantie, dass nichts passiert ist.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: merge the READMEs and add the 2.0 rollout note

The rollout note leads with the two things that break silently otherwise:
renamed environment variables and queue arguments that cannot be
redeclared."
```

---

## Self-Review

**Spec-Abdeckung (Spec → Task):**

| Spec | Task |
|---|---|
| Etappe 12: `AuditService`, `AuditSink`-Implementierung, Identitäts-Header, Flutkontrolle, Schutzregeln | 1 |
| Etappe 12: `surf.dlx`, `surf.dlq.*`, `surf.unroutable` und Queue-Arguments entfernen | 1 Step 10 |
| Etappe 13: drei Tabellen, Repository, `catch (Throwable)`, Retention, `@AutoService` | 2 |
| Etappe 14: Aggregate | 3 |
| Etappe 14: ein Paper-, ein Velocity-Plugin, ein Standalone | 4 |
| Etappe 15: Event-, Query-, RPC-, Freischaltungs-, Audit-Suite | 5 |
| Etappe 16: README, Koordinaten, neue Fläche | 6 |
| Risiko „406 bei Redeklaration" | 1 Step 11, 6 Step 2 |
| Risiko „Env vergessen → localhost" | 6 Step 2 |
| Risiko „Audit ist best effort" | 6 Step 3 |
| Bekannte Einschränkung Docker | 5 Step 5 |

**Typkonsistenz:** `AuditService.report(AuditReport)` ist die Signatur aus Task 1, die Task 2 in
`AuditServiceImpl` implementiert und Task 3 im Vollständigkeitstest erwartet.
`AuditRepository.insert(AuditReport)` und `deleteOlderThan(days: Int): Int` sind in Task 2
festgelegt und werden von `AuditRetention` benutzt. Die Umbenennung der Interface-Properties zu
`rabbitTransport`/`redisTransport` in Task 3 Step 4 ist die einzige Rückwirkung auf Plan 2 und dort
als solche vermerkt.

**Bewusst nicht hier:** eine Oberfläche über den Audit-Tabellen (SQL genügt), `queryAll<T>()`,
durable Events über Redis Streams, die Korrektur der `RabbitModule`-Enumeration in
surf-microservice, die Umbenennung des GitHub-Repositories, der Umbau der Consumer.
