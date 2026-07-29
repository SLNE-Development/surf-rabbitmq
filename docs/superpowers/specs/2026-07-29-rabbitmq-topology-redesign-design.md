# surf-rabbitmq: Topologie- und API-Redesign

**Datum:** 2026-07-29
**Status:** Entwurf zur Freigabe
**Ausgangsversion:** 1.6.2

## Problemstellung

Die Library implementiert ausschließlich Request/Response über die AMQP-Default-Exchange.
Es existiert keine einzige Exchange im gesamten Projekt. Daraus folgen fünf konkrete
Einschränkungen, die den Betrieb einer Microservice-Flotte behindern.

### P1 — `pluginName` ist dreifach überladen

`pluginName` ist gleichzeitig Connection-Name, Request-Queue-Name und Callback-Queue-Prefix.
Ein Client kann strukturell nur den Service ansprechen, der denselben Namen trägt. Ein
Paper-Plugin, das `surf-factions` und `surf-punish` aufruft, benötigt zwei vollständige
`ClientRabbitMQApi`-Instanzen: zwei TCP-Verbindungen, zwei Publisher-Pools, zwei
Callback-Queues, zwei Consumer-Threadpools. Eine Zieladressierung existiert nicht.

### P2 — Kein Fire-and-Forget, kein Broadcast

Die einzige Sende-API ist `sendRequest(request, responseClass)` und wartet zwingend auf eine
Antwort. Jedes Event ist ein synchroner Round-Trip mit 60 s Timeout. `RabbitConsumer.declareExchange()`
und `RabbitConsumer.bindQueue()` existieren, werden aber nirgends aufgerufen — toter Code.

### P3 — Microservices können keine Events publizieren

`ServerRabbitMQApi` besitzt keinerlei Sende-API. Ein Microservice kann nur auf Requests
antworten, aber von sich aus nichts mitteilen. `surf-transaction` kann nicht bekanntgeben,
dass eine Transaktion abgeschlossen wurde.

**Abgrenzung:** Behoben wird ausschließlich das Publizieren von Events. Synchroner
RPC-Aufruf zwischen Microservices bleibt ein Anti-Pattern und wird nicht als Anwendungsfall
unterstützt — siehe Abschnitt "Architekturrichtlinie".

### P4 — Der Client deklariert fremde Queues

`AbstractRabbitMQConnectionImpl.connect()` läuft auf beiden Seiten identisch und deklariert
`queue = api.pluginName` als durable. Der Client deklariert damit die Queue des Servers, ohne
sie je zu konsumieren. Ändert der Service seine Queue-Argumente (DLX, Quorum, TTL), erhält der
Client `PRECONDITION_FAILED (406)`. Da Request- und Callback-Queue denselben Channel des
`mainConsumer` teilen, reißt der Channel-Fehler auch den RPC-Rückkanal mit.

### P5 — Nachrichtenverlust ohne Spur

Bei Deserialisierungsfehler, fehlendem Handler, Handler-Exception, Timeout und fehlender
`correlationId` folgt jeweils `ack.nack(requeue = false)`. Ohne Dead-Letter-Exchange ist die
Nachricht anschließend verloren. Kein Audit, kein Replay, keine Diagnose.

### Weitere Befunde

- `mandatory = false` bei allen Publishes: nicht routbare Nachrichten verschwinden still,
  der Aufrufer läuft in einen 60-Sekunden-Timeout statt sofort zu scheitern.
- Classic Queues statt Quorum Queues.
- `ClientRabbitMQConnectionImpl.kt:312` setzt `expiration` pauschal auf jede ausgehende
  Nachricht. Für RPC korrekt, für Fire-and-Forget falsch — fällt heute nicht auf, weil es
  kein Fire-and-Forget gibt.
- Keine Begrenzung der Queue-Länge: ein dauerhaft toter Service kann den Broker-Speicher
  für alle anderen Services erschöpfen.
- Das Repository enthält keine Tests. Kein `src/test`, keine Test-Dependencies.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Umfang | Breaking Changes erlaubt, keine Wire-Kompatibilität zur 1.6.x |
| Muster | RPC, Fire-and-Forget, Broadcast, Topic-Routing |
| Zieladressierung | `@RpcService(service = "...")` als Default, pro Proxy überschreibbar |
| Client/Server | Trennung wird aufgelöst zu einer `SurfRabbitApi` |
| Retry bei Handler-Fehlern | Default an, pro Handler abschaltbar |
| Client-Retry | Retry bei Transportfehlern plus Circuit Breaker pro Ziel-Service |
| surf-broker | Später als eigenes Projekt; jetzt nur extraktionsfähig schneiden |
| Tests | Unit- und Integrationstests (Testcontainers) |

## Architektur

### Exchanges

Alle `durable`, beim Verbinden idempotent deklariert.

| Exchange | Typ | Zweck |
|---|---|---|
| `surf.rpc` | `direct` | RPC und Fire-and-Forget. Routing-Key = Service-Name oder Instanz-ID |
| `surf.events` | `topic` | Broadcast und Pub/Sub. Routing-Key = Event-Topic |
| `surf.dlx` | `direct` | Dead-Letter-Ziel |
| `surf.unroutable` | `fanout` | Alternate Exchange von `surf.rpc` |

`surf.rpc` ist `direct`, nicht `topic`: Das Ziel ist stets ein exakter Name, Pattern-Matching
wird nicht benötigt.

`surf.rpc` wird mit `alternate-exchange: surf.unroutable` deklariert.

### Queues

| Queue | Eigenschaften | Deklariert von | Binding |
|---|---|---|---|
| `surf.service.<service>` | quorum, durable | nur dem Service-Host | `surf.rpc`, Key `<service>` |
| `surf.instance.<instanceId>` | exclusive, autoDelete, transient | jedem Prozess | `surf.rpc`, Key `<instanceId>` |
| `surf.events.<instanceId>` | exclusive, autoDelete, transient | jedem `BROADCAST`-Abonnenten | `surf.events`, Keys = Patterns |
| `surf.events.<service>` | quorum, durable | dem `SHARED`-Abonnenten | `surf.events`, Keys = Patterns |
| `surf.reply.<instanceId>` | exclusive, autoDelete, transient | jedem Prozess | Default-Exchange |
| `surf.dlq.<service>` | quorum, durable | dem Service-Host | `surf.dlx`, Key `<service>` |
| `surf.unroutable` | quorum, durable | beim Verbinden | `surf.unroutable` (fanout) |

Argumente von `surf.service.<service>`:

```
x-queue-type:        quorum
x-dead-letter-exchange: surf.dlx
x-max-length-bytes:  268435456        (256 MiB)
x-overflow:          reject-publish
```

`reject-publish` lässt den Broker neue Nachrichten ablehnen, sobald die Queue voll ist. Der
Absender erhält einen Fehler, statt dass ältere Nachrichten still verworfen werden.

**Ein Prozess deklariert ausschließlich Queues, die er selbst konsumiert.** Damit ist P4
behoben. Exchanges werden weiterhin von allen deklariert, da idempotent bei identischer
Definition.

### Retry-Queues

Drei global geteilte Queues für die gesamte Flotte, nicht pro Service:

| Queue | `x-message-ttl` | `x-dead-letter-exchange` |
|---|---|---|
| `surf.retry.10s` | 10 000 | `surf.rpc` |
| `surf.retry.60s` | 60 000 | `surf.rpc` |
| `surf.retry.300s` | 300 000 | `surf.rpc` |

Beim Dead-Lettering behält RabbitMQ den ursprünglichen Routing-Key, solange
`x-dead-letter-routing-key` nicht gesetzt ist. Eine fehlgeschlagene `surf-punish`-Nachricht
landet nach Ablauf der TTL selbsttätig wieder in `surf.service.surf-punish`. Drei Queues
genügen daher für beliebig viele Services.

Ablauf bei Handler-Fehler. `n` ist die Anzahl bereits erfolgter Retries, gelesen aus dem
`x-death`-Header (`0` bei Erstzustellung):

| `n` | Aktion |
|---|---|
| 0 | publish nach `surf.retry.10s`, dann `ack` der Originalnachricht |
| 1 | publish nach `surf.retry.60s`, dann `ack` |
| 2 | publish nach `surf.retry.300s`, dann `ack` |
| 3 | publish nach `surf.dlx` → `surf.dlq.<service>`, dann `ack` |

Eine Nachricht wird also höchstens **viermal zugestellt** (Erstzustellung plus drei Retries),
bevor sie in der DLQ landet. Bei `retry = false` entfällt die Retry-Kette vollständig: Der
erste Fehler führt unmittelbar zu `surf.dlx`.

`basicNack(requeue = true)` wird nirgends verwendet: Die Nachricht kehrte sofort an den
Queue-Kopf zurück, würde sofort erneut zugestellt und erneut scheitern — eine Endlosschleife
unter Volllast.

### Identität

Zwei getrennte Begriffe ersetzen `pluginName`:

- `serviceName` — logisch, von allen Instanzen geteilt, z. B. `surf-factions`
- `instanceId` — eindeutig pro Prozess, `<serviceName>-<8 Hex-Zeichen>`

### Nachrichten-TTL

TTL wird pro Nachrichtenart gesetzt, nicht global:

| Art | `expiration` | Begründung |
|---|---|---|
| RPC-Request | `requestTimeoutSeconds` | Nach Aufgeben des Aufrufers darf die Anfrage nicht später doch ausgeführt werden |
| Fire-and-Forget | keine | Muss gepuffert werden, bis der Service zurückkehrt |
| Broadcast | keine | Ephemere Queues, kein Bedarf |
| Response | `requestTimeoutSeconds` | Antwort auf einen abgelaufenen Request ist wertlos |

### Zustellverhalten bei Ausfall

| Situation | Verhalten |
|---|---|
| Alle Instanzen eines Service offline, RPC | Nachricht wartet in der Queue; Aufrufer erhält nach Timeout `SurfRabbitRequestTimeoutException`; TTL räumt die Nachricht ab |
| Alle Instanzen offline, Fire-and-Forget | Nachricht wartet in der durablen Queue und wird bei Rückkehr verarbeitet |
| Alle Instanzen offline, Broadcast | Event verfällt. Keine gebundene Queue, kein Empfänger |
| Service existiert nicht | `mandatory = true` + `ReturnListener` → sofortige `SurfRabbitServiceUnavailableException`; Kopie in `surf.unroutable` |
| Broker nicht erreichbar | Bestehende Auto-Recovery mit Backoff und Jitter (`RabbitClient.kt:148`) bleibt unverändert |
| Service-Queue voll | `reject-publish` → Absender erhält Fehler |

Die `autoDelete`-Event-Queues des `BROADCAST`-Modus bedeuten: Ein Prozess verpasst Events,
die während seiner Ausfallzeit gesendet wurden. Für Cache-Invalidierung, Kick und Reload ist
das korrekt, da ein frisch gestarteter Prozess ohnehin leeren Zustand hat. Für garantierte
Zustellung dienen der `SHARED`-Modus oder Fire-and-Forget an die durable Service-Queue.

## Abonnement-Modi

Ein Event-Abonnement wählt zwischen zwei Zustellarten. Der Unterschied wird bei mehreren
Instanzen desselben Service relevant.

| Modus | Queue | Verarbeitet von | Überlebt Ausfall | Anwendungsfall |
|---|---|---|---|---|
| `SHARED` | `surf.events.<service>`, quorum, durable | **genau einer** Instanz | ja | DB-Schreibzugriff, Statistik, Webhook, alles mit Seiteneffekt |
| `BROADCAST` | `surf.events.<instanceId>`, ephemer | **jeder** Instanz | nein | Cache-Invalidierung, Config-Reload, Spieler kicken |

Der Fallstrick, den die Modi auflösen: Laufen acht Instanzen von `surf-transaction` und
abonniert ein Handler im `BROADCAST`-Modus, führen ihn alle acht aus. Bei einem Handler, der
in die Datenbank schreibt, entstehen acht Schreibzugriffe.

```kotlin
// Falsch bei 8 Instanzen — schreibt achtmal:
@RabbitSubscribe(mode = BROADCAST)
suspend fun onPlayerBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}

// Richtig — genau eine Instanz schreibt:
@RabbitSubscribe(mode = SHARED)
suspend fun onPlayerBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}
```

**`SHARED` ist der Default.** Begründung: Der Fehlerfall bei versehentlichem `SHARED` ist
harmlos (Event wird von einer statt allen Instanzen verarbeitet, meist gewünscht), der bei
versehentlichem `BROADCAST` teuer (n-fache Ausführung mit Seiteneffekten). `BROADCAST` muss
daher bewusst angefordert werden.

Auf Paper- und Velocity-Prozessen ist typischerweise `BROADCAST` korrekt, da dort jede
Instanz ihren eigenen lokalen Zustand invalidieren muss.

## Architekturrichtlinie: keine Microservice-Ketten

Ein Microservice ruft keinen anderen Microservice synchron per RPC auf. Die vorgesehene
Struktur:

```
Paper-Plugin surf-punish  ──RPC──▶  Microservice surf-punish
Paper-Plugin surf-factions ──RPC──▶ Microservice surf-factions

Microservice surf-transaction ──publish──▶ surf.events ──▶ beliebige Abonnenten
```

Jedes Plugin stellt eine lokale API bereit (`Faction.create()`), die intern den zugehörigen
Microservice aufruft. Ein Microservice kennt keine anderen Microservices.

Begründung: Synchrone Ketten zwischen Services koppeln deren Verfügbarkeit aneinander. Fällt
ein Glied aus, fällt die gesamte Kette aus, und die Latenzen addieren sich. Das Ergebnis ist
ein verteilter Monolith.

Erlaubt und vorgesehen ist das **Publizieren von Events**: Der Publisher kennt seine
Abonnenten nicht, es entsteht keine Verfügbarkeitskopplung, und ohne Abonnenten passiert
schlicht nichts.

Technisch verhindert die vereinheitlichte `SurfRabbitApi` einen Service-zu-Service-RPC nicht.
In der bestehenden Projektstruktur ist er praktisch ausgeschlossen, da ein Microservice keinen
Zugriff auf das Service-Interface eines anderen Projekts hat. Die Richtlinie wird daher
dokumentiert, nicht erzwungen.

## Öffentliche API

### Aufbau

```kotlin
val rabbit = SurfRabbitApi.builder("surf-factions", dataPath)
    .serializers(FactionsSerializers)
    .build()

rabbit.registerService<FactionService>(FactionServiceImpl)
rabbit.registerListener(FactionsEventListener)

rabbit.freezeAndConnect()
```

Es gibt keine Client-/Server-Unterscheidung mehr. Ob ein Prozess eine Service-Queue hostet,
ergibt sich daraus, ob er `registerService()` oder `registerRequestHandler()` aufruft.

### Senden

```kotlin
// RPC — eine Instanz antwortet
val factions = rabbit.rpc<FactionService>()
val punish   = rabbit.rpc<PunishService>()
val faction  = factions.findFaction(uuid)

// Fire-and-Forget — eine Instanz, kein Warten
rabbit.send(PlayerKilledPacket(killer, victim))

// Broadcast — alle Abonnenten
rabbit.publish(FactionDisbandedEvent(factionId))

// Gezielt an eine Instanz
rabbit.send(TransferPlayerPacket(uuid), target = InstanceTarget("lobby-3"))
```

`rpc<FactionService>()` und `rpc<PunishService>()` teilen sich Connection, Publisher-Pool und
Callback-Queue. Das behebt P1.

### Empfangen

```kotlin
@RpcService(service = "surf-factions")
interface FactionService {
    suspend fun findFaction(player: UUID): Faction?
}

@RabbitEvent("faction.*.disbanded")
@Serializable
class FactionDisbandedEvent(val factionId: UUID)

object CacheInvalidationListener {
    @RabbitSubscribe
    suspend fun onDisbanded(event: FactionDisbandedEvent) {
        factionCache.invalidate(event.factionId)
    }

    @RabbitSubscribe(retry = false)
    suspend fun onCoinsTransferred(event: CoinsTransferredEvent) {
        economy.apply(event)
    }
}
```

Ziel und Topic stehen am Typ, nicht an der Call-Site — konsistent zwischen `@RpcService` und
`@RabbitEvent`. Beide sind pro Aufruf überschreibbar:

```kotlin
val staging = rabbit.rpc<FactionService>(service = "surf-factions-staging")
```

### Circuit Breaker

Liegt als **eigenständiges Modul `surf-circuitbreaker`** vor, ohne Abhängigkeit zu RabbitMQ
oder zum Rest der Library. Damit ist es in `surf-broker` und `surf-redis` unverändert
wiederverwendbar.

```kotlin
// Modul surf-circuitbreaker, keine RabbitMQ-Typen in der Signatur
class CircuitBreaker(
    val name: String,
    val failureThreshold: Int = 5,
    val openDuration: Duration = 30.seconds,
    val clock: Clock = Clock.systemUTC()
) {
    suspend fun <T> withBreaker(block: suspend () -> T): T
}
```

Die injizierbare `Clock` ist bewusst Teil der öffentlichen Signatur: Sie erlaubt, das
Zeitverhalten der Zustandsmaschine deterministisch und ohne Wartezeiten zu testen.

Verwendung in surf-rabbitmq: **ein Breaker pro Ziel-Service, nicht global.** Ist `surf-punish`
nicht erreichbar, öffnet dessen Breaker und Aufrufe dorthin scheitern sofort, statt in Timeouts
zu laufen. Aufrufe an `surf-factions` bleiben unbeeinflusst. Ohne diese Trennung würde ein
einzelner ausgefallener Service den gesamten Prozess ausbremsen.

Zustände: `CLOSED` → nach 5 aufeinanderfolgenden Transportfehlern `OPEN` (30 s) → `HALF_OPEN`
(ein Probeaufruf) → bei Erfolg `CLOSED`, bei Fehler zurück nach `OPEN`.

Nur Transportfehler zählen auf den Schwellwert ein. Fachliche Exceptions aus einem Handler
lassen den Breaker unberührt — ein Service, der zuverlässig fachliche Fehler liefert, ist
erreichbar und darf nicht abgeschaltet werden.

Client-Retry greift ausschließlich bei Transportfehlern (unroutable, Verbindungsverlust):
2 Versuche mit 250 ms und 1 s Abstand. Fachliche Exceptions aus dem Handler werden unverändert
durchgereicht und niemals wiederholt.

### Bestandsschutz

`RabbitRequestPacket`, `RabbitResponsePacket`, `@RabbitHandler` und der KSP-Prozessor bleiben
erhalten. Sie bilden die Low-Level-Schicht, auf der auch die RPC-Interfaces aufsetzen
(`RpcCallRequestPacket`). Sie erhalten zusätzlich die Zieladressierung.

Entfallen: `ClientRabbitMQApi`, `ServerRabbitMQApi`, `ClientRabbitMQConnection`,
`ServerRabbitMQConnection`, `RabbitMQConnectionFactory`.

## Horizontale Skalierung

Mehrere Instanzen desselben Service konsumieren dieselbe `surf.service.<service>`. RabbitMQ
verteilt die Nachrichten als Competing Consumers. Acht Instanzen von `surf-transaction`
laufen ohne Zusatzkonfiguration; eine weitere Instanz zu starten erhöht den Durchsatz.

Dieses Verhalten funktioniert bereits in Version 1.6.2 und bleibt unverändert. Kaputt war
nicht die Skalierung der Microservices, sondern die Aufruferseite (P1) und das Fehlen von
Events (P2, P3).

Was sich bei mehreren Instanzen ändert:

| Aspekt | Verhalten |
|---|---|
| RPC | Eine beliebige Instanz antwortet, lastverteilt |
| Fire-and-Forget | Eine beliebige Instanz verarbeitet |
| `SHARED`-Abonnement | Eine beliebige Instanz verarbeitet |
| `BROADCAST`-Abonnement | **Alle** Instanzen verarbeiten |
| In-flight-Nachrichten | `serverPrefetchCount` × Instanzanzahl |

**Keine Reihenfolgegarantie.** Zwei Nachrichten, die denselben Spieler betreffen, können
gleichzeitig auf unterschiedlichen Instanzen verarbeitet werden. Bei zustandsverändernden
Services — insbesondere `surf-transaction` — muss die Konsistenz im Service selbst
sichergestellt werden, etwa über Datenbanktransaktionen oder ein Lock pro Entität. Kein
Messaging-System löst das an dieser Stelle.

Sollte eine Reihenfolge pro Entität später zwingend erforderlich werden, wäre eine
Consistent-Hash-Exchange (Broker-Plugin `rabbitmq_consistent_hash_exchange`) das Mittel der
Wahl, um alle Nachrichten einer Spieler-UUID stets derselben Instanz zuzuleiten. Nicht
Bestandteil dieses Vorhabens.

## Modulstruktur

| heute | danach |
|---|---|
| `surf-rabbitmq-api/surf-rabbitmq-common-api` | `surf-rabbitmq-api` |
| `surf-rabbitmq-api/surf-rabbitmq-client-api` | ↑ |
| `surf-rabbitmq-api/surf-rabbitmq-server-api` | ↑ |
| `surf-rabbitmq-common` | `surf-rabbitmq-core` |
| `surf-rabbitmq-client` | ↑ |
| `surf-rabbitmq-server` | ↑ |
| `surf-rabbitmq-ksp` | unverändert, `@RpcService(service = ...)` ergänzt |
| `surf-rabbitmq-paper`, `-velocity` | unverändert |
| — | `surf-circuitbreaker` (neu, ohne RabbitMQ-Abhängigkeit) |

### Vorbereitung auf surf-broker

Diese Packages dürfen weder `com.rabbitmq.*` importieren noch `Rabbit*`-Typen in ihren
Signaturen führen:

```
dev.slne.surf.rabbitmq.shared.dispatch        Handler-Discovery, Hidden-Class-Invoker
dev.slne.surf.rabbitmq.shared.serialization   Serializer-Caches
dev.slne.surf.rabbitmq.shared.lifecycle       freeze / connect / disconnect
dev.slne.surf.rabbitmq.shared.config          env > yaml > default
dev.slne.surf.rabbitmq.platform               Paper-/Velocity-Bootstrap, Reflection-Proxies
```

Die Regel wird durch einen Test durchgesetzt, nicht durch Dokumentation. Ohne
maschinelle Prüfung weicht sie auf und die spätere Extraktion wird erneut zum Redesign.

Belegte Duplikate zwischen surf-rabbitmq und surf-redis (Stand 2026-07-29):

- `JavaPluginLoaderProxy` — identisch bis auf das `package`-Statement
- `SerializedPluginDescriptionProxy` — identisch bis auf das `package`-Statement
- `KotlinSerializerCache` — Unterschied in einem Variablennamen

Nicht nach surf-broker gehören die Sende-APIs und Zustellgarantien selbst. Redis Pub/Sub
broadcastet Requests an alle Subscriber, die erste Antwort gewinnt, und verliert Nachrichten
bei abwesenden Empfängern. RabbitMQ stellt an genau eine Instanz zu, garantiert und persistent.
Eine gemeinsame `sendRequest()`-Abstraktion würde am Aufrufort verbergen, welche Garantie gilt.
Ebenso bleiben Redis' `SyncMap`, `SyncList`, `SyncSet`, `SyncValue`, Caches und Lua-Skripte
außen vor: verteilte Datenstrukturen, kein Messaging.

## Verifikation

Testcontainers mit echtem RabbitMQ-Broker. Topologie-Eigenschaften — ob ein Binding greift,
ob Dead-Lettering den Routing-Key behält, ob `reject-publish` den Publisher blockiert — sind
gegen Mocks nicht prüfbar.

### Integrationstests

| # | Test | Sichert ab |
|---|---|---|
| 1 | Topologie nach `connect()` | Exchanges, Queues, Bindings, Argumente wie spezifiziert |
| 2 | RPC-Round-Trip | Grundfunktion |
| 3 | 3 Instanzen, 100 Nachrichten | Competing Consumers: jede Nachricht genau einmal |
| 4 | 3 Instanzen, `BROADCAST`, 1 Event | **Alle drei** erhalten das Event |
| 4b | 3 Instanzen, `SHARED`, 1 Event | **Genau eine** erhält das Event |
| 4c | `SHARED`, alle Instanzen offline, dann Neustart | Event überlebt in der durablen Queue |
| 5 | Topic-Pattern | Nur passende Abonnenten erhalten das Event |
| 6 | Handler wirft Exception | Retry über 10 s / 60 s / 300 s, danach DLQ |
| 7 | `retry = false` | Direkt DLQ ohne Wiederholung |
| 8 | Send an unbekannten Service | Sofortige Exception, Kopie in `surf.unroutable` |
| 9 | Circuit Breaker | Öffnet nach 5 Fehlern, schließt über `HALF_OPEN` |
| 10 | TTL-Trennung | RPC-Nachricht verfällt, Fire-and-Forget nicht |
| 11 | Broker-Neustart im Betrieb | Recovery, neue Callback-Queue, keine hängenden Requests |
| 12 | Queue voll | `reject-publish` meldet Fehler an den Absender |
| 13 | Chunking | Seit 1.6 vorhanden, bisher ungetestet |
| 14 | Zwei Ziele über eine Connection | Genau eine TCP-Verbindung für zwei RPC-Proxys |

Die Retry-Tests 6 und 7 verwenden zur Laufzeitverkürzung verkürzte TTLs aus der
Testkonfiguration.

### Unit-Tests

Ohne Broker lauffähig:

- Namensbildung für Queues, Exchanges und Routing-Keys
- Versuchszähler-Auswertung aus `x-death`, inklusive der Grenzfälle `n = 0` und `n = 3`
- Circuit-Breaker-Zustandsmaschine über die gesamte Zustandsfolge
  `CLOSED → OPEN → HALF_OPEN → CLOSED` und `HALF_OPEN → OPEN`, mit injizierter `Clock`
  statt realer Wartezeit
- Circuit Breaker: fachliche Exceptions zählen nicht auf den Schwellwert ein
- Config-Layering `env > plugin YAML > global YAML > Default`
- TTL-Zuordnung pro Nachrichtenart
- Import-Regel für `shared.*` und `platform.*`
- `surf-circuitbreaker` enthält keine Referenz auf RabbitMQ-Typen

### Bekannte Einschränkung

Auf der aktuellen Maschine ist kein Docker-Daemon erreichbar. Die Integrationstests werden
vollständig erstellt, ihre Ausführung erfordert einen laufenden Docker-Daemon. Solange
ungeprüft, wird der Status als "nicht verifiziert" berichtet, nicht als bestanden.

## Umsetzung in Etappen

Jede Etappe endet in einem übersetzbaren Zustand mit lauffähigen Tests.

| # | Etappe | Inhalt |
|---|---|---|
| 1 | Topologie | `RabbitTopology`, Namensbildung, Deklaration, Integrationstest 1 |
| 2 | Modul-Zusammenführung | 6 Module → 2, `shared.*`-Schnitt, Import-Regel-Test |
| 3 | `SurfRabbitApi` | Vereinheitlichte API, Zieladressierung, Tests 2, 3, 14 |
| 4 | Events | `@RabbitEvent`, `@RabbitSubscribe`, `SHARED`/`BROADCAST`, Tests 4, 4b, 4c, 5 |
| 5 | Fire-and-Forget und TTL | `send()`, TTL pro Nachrichtenart, Test 10 |
| 6 | Zuverlässigkeit | DLQ, Retry-Queues, `mandatory`, `reject-publish`, Tests 6, 7, 8, 12 |
| 7 | `surf-circuitbreaker` | Eigenständiges Modul, Zustandsmaschine, Client-Retry, Test 9 |
| 8 | KSP | `@RpcService(service = ...)`, Codegen-Anpassung |
| 9 | Migration | `surf-rabbitmq-test` auf neue API, Tests 11, 13, README |

## Offene Punkte

Keine. Alle Entwurfsfragen sind entschieden.

## Nicht Bestandteil dieses Vorhabens

- Anlage des `surf-broker`-Projekts und Migration von surf-redis. Eigenes Vorhaben mit
  eigener Spec, nach Abschluss dieses Umbaus.
- Wire-Kompatibilität zu Version 1.6.x. Ein gemischter Betrieb alter und neuer Instanzen
  wird nicht unterstützt; alle Services müssen gemeinsam aktualisiert werden.
- Änderungen an der bestehenden Verbindungs-Recovery und am Netty-Transport-Setup.
