# surf-eventbus: Ein Bus, zwei Transports, drei Zusagen

**Datum:** 2026-07-29
**Überarbeitet:** 2026-07-31 — erste Runde: Package-Umbenennung, beide Transports gleichzeitig,
Aufgabenteilung statt Provider-Wahl, DB-Audit statt DLQ, Audit-Microservice. Zweite Runde:
Modulschnitt mit Aggregat-Modulen, `surf-circuitbreaker` eingegliedert, Fire-and-Forget als
Eigenschaft eines RPC-Aufrufs, Consumer-Umbau nicht mehr Lieferbestandteil. Dritte Runde:
`registerService` bleibt, Redis' Request/Response bleibt — als typisierter `@QueryService`.
Vierte Runde: alle Env-Variablen zu `SURF_EVENTBUS_*`, und Korrektur gegen den tatsächlichen
Stand von surf-redis 1.10.1.
**Status:** Entwurf zur Freigabe
**Ausgangslage:** surf-rabbitmq 1.6.2 auf Branch `feat/topology-redesign` (Redesign zu 2.0
implementiert), surf-redis **1.10.1** auf `master` (die Linie `version/26.1` ist remote gelöscht;
der lokale Stand 1.5.0 war 81 Commits alt)
**Branch:** `feat/surf-event-bus`, abgezweigt von `feat/topology-redesign`

## Auftrag

Ein Bus als einziger Einstiegspunkt für verteilte Kommunikation. surf-redis wird dabei
vollständig in dieses Repository absorbiert; das Repository surf-redis wird danach abgeschaltet.

Es gibt **keine Provider-Wahl**. Beide Transports laufen gleichzeitig, und jeder macht genau
das, was er kann:

| Zweck | Verb | Transport |
|---|---|---|
| Benachrichtigung an alle | `bus.publish(event)` | **Redis** Pub/Sub |
| Aufruf an genau eine bekannte Stelle, mit Antwort | `bus.rpc<T>()` | **RabbitMQ** |
| Auftrag an genau eine bekannte Stelle, ohne Antwort, durable | `bus.rpc<T>()` auf `@FireAndForget`-Methode | **RabbitMQ** |
| Frage an alle, es antwortet, wer zuständig ist | `bus.query<T>()` | **Redis** Pub/Sub |
| Verteilter Zustand | `bus.redis` | **Redis** |
| Audit fehlgeschlagener Nachrichten | intern | RabbitMQ → Microservice → Datenbank |

Drei Zusagen, drei Verben, jede mit genau einer Implementierung. Was doppelt existiert und
dabei unterschiedliche Garantien verspricht — Rabbits Events neben Redis' Pub/Sub, die
untypisierte Paket-API neben typisierten RPC-Proxies — wird gelöscht. Was **verschieden** ist,
bleibt und bekommt einen eigenen Namen: eine Frage an alle ist kein RPC-Aufruf und darf nicht
so aussehen.

Die bestehende Topologie-Spec (`2026-07-29-rabbitmq-topology-redesign-design.md`) hat dieses
Vorhaben unter dem Namen `surf-broker` vorgedacht und die broker-neutralen Packages bereits
so geschnitten, dass sie extrahierbar sind — inklusive `SharedPackagePurityTest` als
maschineller Absicherung. Dieser Entwurf löst das ein, mit einer Abweichung: nicht als
Extraktion in ein drittes Projekt, sondern als Zusammenführung in dieses.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Ort | Ein Repository. surf-redis zieht als Module herein, sein Repository wird danach gelöscht |
| Aufgabenteilung | Events und Zuständigkeitsfragen über Redis, adressierte Aufrufe über RabbitMQ |
| Provider-Wahl | **Entfällt.** Ein Prozess schaltet am Builder frei, welche Transports er benutzt: `withRabbit()`, `withRedis()`, beides |
| `SubscriptionMode` | **Entfällt.** Events sind Broadcast. Ein Modus mit genau einem Wert wäre eine Lüge über Wahlfreiheit |
| Fire-and-Forget | Kein eigener Mechanismus mehr, sondern `@FireAndForget` an einer RPC-Methode. Die untypisierte Paket-API entfällt |
| Redis' Request/Response | **Bleibt**, aber typisiert: `@QueryService` mit KSP-Proxy, `null` als Abstinenz, erste Antwort gewinnt |
| Registrierung | `bus.registerService<T>(impl)` für beide Vertragsarten. Der Descriptor weiß, ob RPC oder Query |
| Ziel eines Aufrufs | `ServiceTarget` aus `@RpcService(service = …)`; `bus.rpc<T>(InstanceTarget(…))` adressiert einen Prozess. `@QueryService` braucht kein Ziel |
| Ziel eines Events | Gibt es nicht. Ein adressiertes Event ist ein RPC-Aufruf |
| RPC über Redis | Nein. Ein Query ist kein RPC; Begründung und die Tür über Redis Streams sind dokumentiert |
| Event-API in RabbitMQ | Wird gelöscht, inklusive `surf.events`, Event-Queues und Event-Retry |
| Dead-Letter-Queue | Wird ersetzt: ein Audit-Microservice schreibt jeden Verlustpfad in die Datenbank |
| Modulschnitt | Ein Klammermodul pro Bereich, dazu zwei Aggregate: `surf-eventbus-api` und `surf-eventbus-core` |
| KSP | Ein Prozessor für beide Vertragsarten, deshalb `surf-eventbus-ksp` statt `…-rabbitmq-ksp` |
| `surf-circuitbreaker` | Kein eigenes Modul mehr. Zieht nach `surf-eventbus-common` |
| Packages | `dev.slne.surf.eventbus.*`, `dev.slne.surf.eventbus.rabbitmq.*`, `dev.slne.surf.eventbus.redis.*` |
| Namen | Artefakte `surf-eventbus-*`, Gruppe `dev.slne.surf.eventbus`, Typen `SurfEventBus`, `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe` |
| Migration | Harter Schnitt. Der Umbau der Consumer geschieht manuell und ist **nicht** Teil dieses Vorhabens |
| Version | 2.0.0, in der vom Topologie-Redesign eröffneten Linie |

## Problemstellung

### P1 — Zwei Event-Systeme mit unvereinbarem Vokabular

| | surf-rabbitmq | surf-redis |
|---|---|---|
| Basis | `RabbitEventPacket : RabbitPacket` | `RedisEvent` |
| Topic | `@RabbitEvent("faction.disbanded")` | keins — Routing über den FQCN |
| Handler | `@RabbitSubscribe(topic, mode, retry)` | `@OnRedisEvent` |
| Zustellarten | `SHARED` (durable, genau eine Instanz) und `BROADCAST` | ausschließlich Broadcast |
| Dispatch | Reflection, typhierarchie-bewusst | Hidden-Class-Invoker, exakter Typ |
| Selbstzustellung | Publisher erhält sein Event | `originatesFromThisClient()`, von jedem Consumer manuell geprüft |
| Format | CBOR | JSON auf `surf-redis:events`, **plus** optionaler Binär-Codec auf `surf-redis:events:binary` |
| Fehlerbehandlung | Retry-Leiter 10 s/60 s/300 s, danach DLQ | Log |

Die erste Fassung dieses Entwurfs wollte beide Systeme hinter eine gemeinsame API heben. Der
gewählte Weg ist der kürzere: **eines von beiden verschwindet.** `@RabbitSubscribe` hat null
externe Consumer — die Event-API des Redesigns ist noch nicht in Benutzung — während rund
zwanzig `@OnRedisEvent`-Handler produktiv laufen. Der Rabbit-Zweig wird gelöscht, der
Redis-Zweig wird auf das gemeinsame Fundament gehoben.

### P2 — Zwei Request/Response-Systeme, deren Unterschied man nicht sieht

| | RabbitMQ RPC | Redis `RedisRequest` |
|---|---|---|
| Empfänger | genau eine Instanz eines benannten Dienstes | **alle** Prozesse, die den Typ kennen |
| Wer antwortet | der Empfänger | wer sich zuständig fühlt; die erste Antwort gewinnt |
| Dienst offline | Request wartet in der durable Queue | Request ist weg, ohne Spur |
| Nicht-idempotenter Handler | läuft einmal | läuft in jedem Prozess, der nicht abbricht |
| Fehlerfall | Retry-Leiter, danach Audit | Timeout, das war's |
| Typisierte Schnittstelle | `@RpcService` mit KSP-Proxy | handgeschriebene Paketklassen |

Das Problem ist **nicht**, dass es zwei gibt — die beiden Spalten sind wirklich zwei
verschiedene Dinge. Das Problem ist, dass man am Aufrufort nicht sieht, welches man benutzt:
`sendRequest(...)` heißt beides, und `RedisRequest` verlangt handgeschriebene Paketklassen mit
einem `respond()`, das man vergessen kann.

Die Auflösung ist deshalb nicht Löschen, sondern Benennen: der adressierte Aufruf heißt
`bus.rpc<T>()`, die Frage an alle heißt `bus.query<T>()`, und beide bekommen typisierte
Verträge aus demselben KSP-Prozessor.

### P3 — Drei Wege, eine Nachricht an einen Dienst zu schicken

Innerhalb von surf-rabbitmq gibt es die untypisierte Paket-API (`RabbitRequestPacket`,
`@RabbitHandler`, `respond()`, `send()`) **und** typisierte RPC-Proxies (`@RpcService`, KSP).
Beide tun dasselbe über dieselbe Queue; die eine mit handgeschriebenen Paketklassen, die andere
mit generierten Proxies und Methodensignaturen. Dazu ein halbes Dutzend Standard-Antwortpakete
(`StringResponsePacket`, `PrimitiveResponse`, `ArrayResponse` …), die nur existieren, weil die
Paket-API keine Rückgabetypen kennt.

### P4 — Dreifach dupliziertes Fundament

Belegte Duplikate (Stand 2026-07-29):

- `JavaPluginLoaderProxy` — identisch bis auf das `package`-Statement
- `SerializedPluginDescriptionProxy` — identisch bis auf das `package`-Statement
- `KotlinSerializerCache` — Unterschied in einem Variablennamen
- Der Netty-`META-INF/native`-Mangling-Block in `build.gradle.kts` — Zeichen für Zeichen gleich
- Lifecycle `freeze → connect → disconnect` — zweimal getrennt implementiert, einmal
  `suspend`, einmal blockierend über Reactor
- Zwei Plattform-Plugin-Paare (Paper und Velocity), die dasselbe tun: eine Instanz bereitstellen

### P5 — Jeder Server trägt zwei Plugins und zwei Netty-Versionen

surf-rabbitmq pinnt Netty 4.2.16, surf-redis 4.2.10, beide relocaten nach eigenem Ziel. Ein
Server, der beides nutzt — und das ist der Normalfall — lädt zwei Plattform-Plugins, zwei
Netty-Kopien und zwei Shading-Bäume.

### P6 — surf-core hat den Bus schon von Hand nachgebaut

```kotlin
// surf-core: SurfEventBus.fire() -> Redis -> LocalSurfEventBusListener -> fireLocal()
fun fire(event: SurfEvent) {
    CoreInstance.redisApi.publishEvent(SurfEventFireRedisEvent(event))
}
```

Ein verteilter Event-Bus in 60 Zeilen, ohne Topics, ohne Fehlerbehandlung, dispatchend über
`KClass`-Reflection auf exakten Typ. Genau die Lücke, die dieses Vorhaben schließt.

### P7 — Fehlgeschlagene Nachrichten liegen in Queues, die niemand ansieht

`surf.dlq.<service>` und `surf.unroutable` sind durable Queues ohne Consumer. Sie halten die
Nachricht, aber nicht die Ursache: keine Exception, kein Stacktrace, kein Handler-Name, keine
Historie der Versuche. Wer wissen will, warum etwas gescheitert ist, sucht im Log des
Prozesses, der es damals versucht hat — falls das Log noch existiert. Ein Grep über acht
Instanzen ist die derzeitige Antwort auf „warum ist das nicht angekommen".

### P8 — Der globale Request-Kanal geht an alle

`RequestResponseBusImpl` benutzt zwei Kanäle für die ganze Flotte: `surf-redis:requests` und
`surf-redis:responses`. Jeder Prozess empfängt jede Anfrage, deserialisiert den Typnamen und
verwirft, was ihn nicht betrifft; jeder Prozess empfängt jede Antwort, auch die auf fremde
Fragen. Das skaliert mit der Flotte statt mit dem Interesse.

## Zielarchitektur

### Aufgabenteilung statt Provider-Wahl

Der Angelpunkt dieses Entwurfs. Die erste Fassung hat versucht, dieselbe Zusage über zwei
Transports zu geben, und musste dafür ein Capability-Modell einführen, das Löcher zur Startzeit
meldet. Die zweite Fassung dreht es um: **jede Zusage hat genau einen Transport, der sie
halten kann.**

| Zusage | Ausdruck im Code | Transport | Was passiert im Fehlerfall |
|---|---|---|---|
| „alle interessierten Prozesse erfahren davon" | `bus.publish(event)` | Redis Pub/Sub | Handler-Fehler wird geloggt und auditiert; das Event ist weg |
| „genau eine Instanz tut das und antwortet" | RPC-Methode mit Rückgabetyp | RabbitMQ | Timeout oder Fehlerantwort beim Aufrufer; Retry-Leiter, danach Audit |
| „genau eine Instanz tut das, ich warte nicht" | RPC-Methode mit `@FireAndForget` | RabbitMQ | Retry-Leiter, danach Audit. Der Aufrufer erfährt nichts |
| „wer auch immer zuständig ist, antwortet mir" | Query-Methode | Redis Pub/Sub | Keine Antwort innerhalb des Timeouts heißt `null`. Handler-Fehler werden geloggt und auditiert |
| „dieser Zustand gilt fleet-weit" | `bus.redis.createSyncSet(…)` | Redis | Redisson-Semantik, unverändert |

Die Frage „welcher Transport?" ist damit keine Konfiguration und keine Annotation am Event,
sondern folgt aus dem Verb. Ein Event kann nicht durable sein, weil Events über Pub/Sub laufen —
und genau darum ist ein Event die falsche Wahl, wenn eine Datenbankzeile davon abhängt.

**Kein Mischbetriebsproblem mehr.** Zwei Prozesse können nicht auf verschiedenen Kanälen
aneinander vorbeireden, weil kein Prozess mehr wählen kann. Die alte
`eventbus.provider`-Konfiguration und `SURF_EVENTBUS_PROVIDER` entfallen.

### Module

Ein Klammermodul pro Bereich, darin die Schichten. Dazu zwei Aggregate, die nichts eigenes
enthalten und nur weiterreichen.

```
surf-eventbus-common                          Fundament, transportfrei, inkl. CircuitBreaker und Codec-Basis
surf-eventbus-bus/
    surf-eventbus-bus-api                     SurfEventBus, Events, Annotationen, BusEventCodec, AuditReport
    surf-eventbus-bus-core                    Registry, Dispatcher, Envelope, Lebenszyklus
surf-eventbus-rabbitmq/
    surf-eventbus-rabbitmq-api                RPC-Vertrag, Identität, Ziele, Topologie-Konfiguration
    surf-eventbus-rabbitmq-core               Broker-Implementierung, Retry-Leiter, AuditSink
surf-eventbus-redis/
    surf-eventbus-redis-api                   Sync-Strukturen, Caches
    surf-eventbus-redis-core                  Redisson-Implementierung, EventTransport, QueryTransport
surf-eventbus-audit/
    surf-eventbus-audit-api                   AuditService (@RpcService, @FireAndForget)
    surf-eventbus-audit-microservice          Tabellen, Repository, Handler, Retention
surf-eventbus-platform/
    surf-eventbus-platform-paper              ein Paper-Plugin
    surf-eventbus-platform-velocity           ein Velocity-Plugin
    surf-eventbus-platform-standalone         eine Standalone-Instanz für Microservices
surf-eventbus-ksp                             Proxies und Descriptoren für @RpcService und @QueryService
surf-eventbus-api                             Aggregat für compileOnlyApi
surf-eventbus-core                            Aggregat für runtimeOnly
surf-eventbus-test                            Integrationstests
```

| Modul | Abhängig von |
|---|---|
| `surf-eventbus-common` | — |
| `surf-eventbus-bus-api` | `surf-eventbus-common` |
| `surf-eventbus-bus-core` | `surf-eventbus-bus-api` |
| `surf-eventbus-rabbitmq-api` | `surf-eventbus-common` |
| `surf-eventbus-rabbitmq-core` | `surf-eventbus-rabbitmq-api`, `surf-eventbus-audit-api` |
| `surf-eventbus-redis-api` | `surf-eventbus-common` |
| `surf-eventbus-redis-core` | `surf-eventbus-redis-api`, `surf-eventbus-bus-api` |
| `surf-eventbus-audit-api` | `surf-eventbus-bus-api`, `surf-eventbus-rabbitmq-api` |
| `surf-eventbus-audit-microservice` | `surf-eventbus-api`, surf-database, surf-microservice |
| `surf-eventbus-ksp` | `surf-eventbus-bus-api` |
| `surf-eventbus-api` | `api(...)` auf bus-api, rabbitmq-api, redis-api, audit-api |
| `surf-eventbus-core` | `api(...)` auf bus-core, rabbitmq-core, redis-core |

Keine Zyklen. `surf-eventbus-redis-core` kennt `surf-eventbus-bus-api`, nicht `-bus-core`. Die
`EventTransport`- und `QueryTransport`-Nähte in `-bus-core` haben je genau eine echte
Implementierung; sie existieren, damit Registry- und Dispatcher-Tests ohne Redis laufen können,
und sind ausdrücklich **kein** Pluggability-Versprechen.

Der Audit-Weg zeigt in eine Richtung: `AuditReport` und `AuditSink` liegen in `-bus-api`, damit
`-bus-core` einen fehlgeschlagenen Event- oder Query-Handler melden kann, ohne einen Broker-Typ
zu sehen. Die RPC-Schnittstelle, die den Report trägt, liegt in `surf-eventbus-audit-api`, die
Implementierung von `AuditSink` in `surf-eventbus-rabbitmq-core`.

**Ein KSP-Prozessor, zwei Vertragsarten.** `@RpcService` und `@QueryService` erzeugen dieselbe
Art Artefakt — Descriptor plus Client-Proxy —, unterscheiden sich aber in der Validierung und
im generierten Aufrufpfad. Ein zweiter Prozessor mit derselben Mechanik wäre Duplikat; deshalb
heißt das Modul `surf-eventbus-ksp` und nicht mehr `…-rabbitmq-ksp`.

### Die zwei Aggregat-Module

Ein Microservice — oder ein Plugin — soll eine Koordinate benennen und alles haben:

```kotlin
dependencies {
    compileOnlyApi("dev.slne.surf.eventbus:surf-eventbus-api:+")
    runtimeOnly("dev.slne.surf.eventbus:surf-eventbus-core:+")
    ksp("dev.slne.surf.eventbus:surf-eventbus-ksp:+")
}
```

`surf-eventbus-api` enthält **keinen eigenen Typ**. Es reicht `surf-eventbus-bus-api`,
`surf-eventbus-rabbitmq-api`, `surf-eventbus-redis-api` und `surf-eventbus-audit-api` per
`api(...)` weiter, damit sie auf dem Compile-Classpath des Consumers landen. `surf-eventbus-core`
tut dasselbe für die Implementierungen. Das ist genau das Paar, das
`SurfMicroserviceModule` in surf-microservice erwartet (`apiModule` / `runtimeModule`), und wer
feiner schneiden will, hängt weiterhin direkt an den einzelnen Modulen.

Der KSP-Prozessor bleibt eine eigene Koordinate: er gehört in die `ksp`-Konfiguration, nicht auf
den Compile-Classpath.

### `surf-circuitbreaker` zieht ein

Vier Quelldateien, keine Abhängigkeiten, ein einziger Consumer im Repository
(`BreakerGuardedRpc` und `RabbitConnectionImpl`), außerhalb des Repositories kein einziger. Als
eigenes veröffentlichtes Artefakt wäre es künftig `dev.slne.surf.eventbus:surf-circuitbreaker` —
eine Koordinate, die eine Herkunft behauptet, die für einen generischen Circuit Breaker keine
Rolle spielt.

Es zieht nach `surf-eventbus-common`, Package `dev.slne.surf.eventbus.common.circuitbreaker`.
Die Tests wandern mit; `NoRabbitMqDependencyTest` geht im Purity-Test von
`surf-eventbus-common` auf, der ohnehin dasselbe prüft.

### Package-Namen

Alles zieht unter `dev.slne.surf.eventbus`:

- `dev.slne.surf.eventbus.*` — der Bus, Events, Annotationen
- `dev.slne.surf.eventbus.common.*` — geteiltes Fundament, inklusive `…common.circuitbreaker`
- `dev.slne.surf.eventbus.rabbitmq.*` — was heute `dev.slne.surf.rabbitmq.*` ist, minus Event-API und Paket-API
- `dev.slne.surf.eventbus.redis.*` — was heute `dev.slne.surf.redis.*` ist
- `dev.slne.surf.eventbus.audit.*` — Audit-Vertrag und Microservice

Damit ändert jeder Consumer Import-Zeilen, auch der, der nur einen Cache benutzt. Der Wechsel
ist mechanisch — zwei Suchen-und-Ersetzen-Regeln —, trifft aber jedes Repository. Das ist der
Preis dafür, dass die Herkunft aus zwei Projekten nicht dauerhaft in den Package-Namen sichtbar
bleibt.

Die Artefaktnamen folgen den Packages: `surf-eventbus-rabbitmq-api` statt `surf-rabbitmq-api`,
voll qualifiziert `dev.slne.surf.eventbus:surf-eventbus-rabbitmq-api`.

## Öffentliche API

### Aufbau

```kotlin
val bus = SurfEventBus.builder("surf-factions", dataPath)
    .instanceName("lobby-3")            // optional; stabile Instanz-Identität
    .serializers(FactionsSerializers)   // gilt für CBOR (Rabbit) und JSON (Redis)
    .withRabbit()                       // RPC, Audit-Meldung
    .withRedis()                        // Events, Queries, Sync-Strukturen, Caches
    .build()

bus.subscribe(FactionCacheListener)
bus.registerService<FactionService>(FactionServiceImpl)
bus.registerService<PlayerLocator>(PlayerLocatorImpl)
bus.freezeAndConnect()
```

Der Builder übernimmt die Identitätsoptionen des heutigen `SurfRabbitApiBuilder`
(`serviceName`, `dataPath`, `instanceName`, `serializers`). `instanceName` bleibt Voraussetzung
dafür, dass dieser Prozess über `InstanceTarget` erreichbar ist.

`withRabbit()` und `withRedis()` schalten je einen Transport frei; mindestens einer ist
Pflicht. Verbindungsdaten kommen aus der Konfiguration (`SURF_EVENTBUS_RABBITMQ_*`,
`SURF_EVENTBUS_REDIS_*`, darunter die YAML-Schichten) — freigeschaltet heißt „dieser Prozess
benutzt das", nicht „hier stehen die Zugangsdaten".

Der Bus besitzt den Lebenszyklus beider Transports: ein `freeze`, ein `connect`, eine
Verbindung pro Transport pro Prozess.

### Die Verben

```kotlin
// Events — Redis, Broadcast
bus.publish(FactionDisbandedEvent(id))
bus.subscribe(FactionCacheListener)
bus.subscribe<FactionCacheListener>()            // löst das Kotlin-object auf

// Anbieten — für beide Vertragsarten dasselbe Verb
bus.registerService<FactionService>(FactionServiceImpl)
bus.registerService<PlayerLocator>(PlayerLocatorImpl)

// Adressierter Aufruf — RabbitMQ
val factions = bus.rpc<FactionService>()
val staging  = bus.rpc<FactionService>("surf-factions-staging")
val lobby3   = bus.rpc<PlayerService>(InstanceTarget("lobby-3"))

// Frage an alle — Redis
val moved = bus.query<PlayerLocator>().sendToServer(playerId, "lobby-3")
```

`registerService` bleibt für beide Vertragsarten dasselbe Verb: der generierte Descriptor weiß,
ob die Schnittstelle `@RpcService` oder `@QueryService` trägt, und der Bus verdrahtet
entsprechend die Rabbit-Serverseite oder das Redis-Abonnement. Registrieren ist in beiden Fällen
dieselbe Handlung — „ich kann das" —, und sie von der Vertragsart abhängig zu benennen würde
den Aufrufer eine Entscheidung wiederholen lassen, die schon in der Schnittstelle steht.

Proxies werden pro Kombination aus Schnittstelle und Ziel gecacht, damit
`bus.rpc<PlayerService>(InstanceTarget(id))` in einem Schleifenkörper stehen darf.

Wird ein Verb aufgerufen, dessen Transport nicht freigeschaltet ist, scheitert es laut und
nennt den fehlenden Builder-Aufruf:

```
IllegalStateException:
  bus.rpc<FactionService>() requires the RabbitMQ transport,
  but this bus was built without it.
  -> add .withRabbit() to SurfEventBus.builder(...)
```

Für Abonnements und angebotene Dienste passiert das nicht erst beim Zustellen, sondern in
`freeze()`.

### Kein Ziel am `publish`

Ein Event an genau eine Instanz gibt es nicht. Redis Pub/Sub kennt keine Adressierung — man
müsste einen Kanal pro Instanz einführen —, und in dem Moment, in dem man einen bestimmten
Empfänger meint, will man auch wissen, ob er es bekommen hat. Beides ist ein RPC-Aufruf mit
`InstanceTarget`. Ein `publish(event, target)` wäre ein Aufruf, der wie eine Benachrichtigung
aussieht und wie ein Auftrag gemeint ist.

### Die transporteigenen Flächen

Was keine gemeinsame Form hat, bleibt hinter einem benannten Zugang:

```kotlin
val online = bus.redis.createSyncSet<String>("online-players")
val identity = bus.rabbit.identity
```

`bus.rabbit` (`SurfRabbitApi`) und `bus.redis` (`RedisApi`) werfen dieselbe laute Ausnahme wie
oben, wenn der Transport nicht freigeschaltet ist.

Der Typname `SurfEventBus` bleibt, obwohl der Bus mehr als Events trägt: er folgt Artefakt und
Package-Wurzel, die beide `eventbus` heißen.

## Events

```kotlin
@Serializable
@BusEvent("faction.disbanded")
class FactionDisbandedEvent(val factionId: UUID) : SurfBusEvent()

bus.publish(FactionDisbandedEvent(id))
```

Das Topic steht am Typ, nicht am Aufrufort — ein Publisher kann dasselbe Event nicht unter
zwei Schlüsseln versenden. Topics sind punktgetrennt und wildcardfrei.

### Abonnements

```kotlin
object FactionCacheListener {

    @SurfSubscribe
    suspend fun onDisbanded(event: FactionDisbandedEvent) {
        cache.invalidate(event.factionId)
    }

    @SurfSubscribe(topic = "faction.#")
    suspend fun onAnyFactionEvent(event: FactionEvent) {
        cache.invalidateAll()
    }

    @SurfSubscribe(includeSelf = true)
    suspend fun onReload(event: ConfigReloadedEvent) {
        config.reload()
    }
}
```

`@SurfSubscribe(topic, includeSelf)`. Zwei Parameter, mehr nicht:

- **Kein `mode`.** Events sind Broadcast, weil Redis Pub/Sub nichts anderes kann. Ein Enum mit
  einem Wert wäre eine Lüge über Wahlfreiheit. Wer „genau eine Instanz, durable" braucht,
  schickt kein Event, sondern ruft eine `@FireAndForget`-Methode auf.
- **Kein `retry`.** Es gibt keine Wiederzustellung: Pub/Sub hat kein Ack, das man verweigern
  könnte. Ein gescheiterter Handler wird geloggt und auditiert, das Event ist weg. Das steht
  hier so deutlich, weil es die einzige Zusage ist, die schwächer als heute ist.

`topic` behält die AMQP-Semantik: `*` trifft genau ein Segment, `#` null oder mehr. Nicht, weil
noch ein Broker mitmatcht — das macht jetzt `EventTopics` lokal —, sondern weil diese Semantik
implementiert, unit-getestet und verstanden ist, und weil Redis' Glob-Semantik (`*` überquert
Punkte) die schlechtere von beiden ist.

`@SurfSubscribe(includeSelf = false)` ist der Default: ein Handler sieht keine Events, die sein
eigener Prozess publiziert hat. Alle rund zwanzig bestehenden `@OnRedisEvent`-Handler beginnen
mit `if (event.originatesFromThisClient()) return`, und die häufigste Ursache einer
Rückkopplungsschleife ist, sie zu vergessen. Der Default kodifiziert die bestehende Praxis.

## RPC: der adressierte Aufruf

```kotlin
@RpcService(service = "surf-factions")
interface FactionService {

    // Aufruf mit Antwort: der Aufrufer wartet und erfährt Fehler
    suspend fun findFaction(player: UUID): Faction?

    // Auftrag ohne Antwort: der Aufrufer wartet nicht und erfährt nichts
    @FireAndForget
    suspend fun rebuildIndex(factionId: UUID)
}
```

### Warum `service` an der Schnittstelle steht — und wann nicht

Es sind zwei verschiedene Namen, die nur zufällig gleich aussehen:

| | Bedeutung | Wo er steht |
|---|---|---|
| `builder("surf-factions", …)` | **wer ich bin.** Bestimmt, welche Queue dieser Prozess konsumiert | am Builder, einmal pro Prozess |
| `@RpcService(service = "surf-factions")` | **wen ich rufe.** Bestimmt den Routing-Key des Aufrufs | am Vertrag, einmal pro Schnittstelle |

Der aufrufende Prozess heißt anders als der gerufene Dienst — sonst wäre es kein verteilter
Aufruf. Der Builder-Name kann den Aufruf deshalb nicht adressieren.

Was daraus folgt:

- **Auf der Serverseite ist `service` bedeutungslos.** `registerService` hostet die
  Implementierung auf der Queue des registrierenden Prozesses; der Annotationswert wird nicht
  gelesen. Eine Schnittstelle, die in diesem Repository nur angeboten und nirgends geproxied
  wird, kann `@RpcService` ohne Argument tragen.
- **Auf der Clientseite ist er nötig, aber nur einmal.** Er steht am Vertrag statt an jedem
  `rpc(...)`-Aufruf, damit ein Tippfehler eine einzige Korrektur ist.
- **Geraten wird nicht.** Fehlt der Wert und wird ein Proxy erzeugt, ohne bei `rpc(service = …)`
  einen mitzugeben, scheitert das laut — mit genau der Meldung, die
  `RabbitRpcServiceImpl.createService` heute schon wirft. Auf den eigenen Dienst
  zurückzufallen wäre der schlimmere Weg: der Aufruf ginge in die eigene Queue, der eigene
  Prozess konsumierte ihn und fände die Methode nicht.

### `@FireAndForget`

Die einzige Neuerung am RPC-Vertrag. Sie ersetzt die untypisierte Paket-API vollständig:

| | ohne `@FireAndForget` | mit `@FireAndForget` |
|---|---|---|
| Aufrufer | wartet auf die Antwort | kehrt nach dem Publish zurück |
| Kein Dienst online | Timeout beim Aufrufer | Nachricht wartet in der durable Queue |
| Rückgabetyp | beliebig serialisierbar | muss `Unit` sein |
| Fehler im Handler | Ausnahme beim Aufrufer, danach Retry-Leiter | Retry-Leiter, danach Audit |
| Reply-Queue | wird benutzt | wird nicht benutzt |

Die Markierung steht an der Methode und nicht am Aufrufort, weil beide Seiten sie kennen müssen:
der Server darf nicht auf ein `respond()` warten, das nie kommt, und der Client darf nicht auf
eine Antwort warten, die nie gesendet wird. Am Rückgabetyp allein lässt sie sich nicht ablesen —
eine wartende Methode mit Rückgabetyp `Unit` ist etwas anderes und durchaus sinnvoll: „tu das,
und sag mir, wenn es schiefgeht".

Umsetzung: der KSP-Proxy ruft für eine `@FireAndForget`-Methode `send()` statt `sendRequest()`
auf. `RabbitRpcCall` trägt schon heute ein `RabbitTarget`, und `send()` existiert intern
bereits — die Änderung liegt im Generator und in der Descriptor-Form, nicht in der Topologie.

### Was damit verschwindet

`RabbitRequestPacket`, `RabbitResponsePacket`, `@RabbitHandler`, `respond()`,
`SurfRabbitApi.send()`, `registerRequestHandler()` und die Standard-Antwortpakete
(`StringResponsePacket`, `OptionalStringResponsePacket`, `PrimitiveResponse`,
`OptionalPrimitiveResponse`, `ArrayResponse`, `OptionalArrayResponse`) sowie deren Einträge im
Default-`SerializersModule`.

**Die Paket-Schicht selbst bleibt — intern.** `RpcCallRequestPacket` und
`RpcCallResponsePacket` sind `RabbitPacket`-Nachfahren, und darauf setzen Chunking,
Properties-Injektion und Versionsaushandlung auf. Was verschwindet, ist die Anforderung an
Anwendungscode, eigene Paketklassen zu schreiben.

## Query: die Frage an alle

Der Nachfolger von `RedisRequest`, und der Grund, warum er bleibt: es gibt Fragen, deren
Antwortender dem Fragenden unbekannt ist. Ein Microservice, der Datenbankarbeit macht, weiß
nicht, auf welchem Proxy ein Spieler hängt — er hat keine Spielerreferenzen. Ihn zu zwingen,
erst eine Zuordnung nachzusehen, die er nicht führt, wäre eine Zumutung mit dem falschen
Argument.

```kotlin
@QueryService
interface PlayerLocator {

    /** `null` heißt: nicht mein Spieler. */
    suspend fun sendToServer(player: UUID, server: String): Boolean?

    suspend fun currentServer(player: UUID): String?
}
```

Client:

```kotlin
val moved = bus.query<PlayerLocator>().sendToServer(playerId, "lobby-3")
    ?: return "Kein Proxy hat diesen Spieler."
```

Server:

```kotlin
object PlayerLocatorImpl : PlayerLocator {
    override suspend fun sendToServer(player: UUID, server: String): Boolean? {
        val connected = proxy.player(player) ?: return null   // Abstinenz
        return connected.moveTo(server)
    }
}
```

### Die vier Regeln

1. **`null` heißt Abstinenz, nicht „nein".** Wer nicht zuständig ist, antwortet nicht — es geht
   keine Nachricht raus. Deshalb muss jeder Rückgabetyp nullable sein; KSP lehnt alles andere
   ab. `Boolean?` unterscheidet damit drei Fälle: `true` erledigt, `false` zuständig aber
   gescheitert, `null` niemand zuständig.
2. **Die erste Antwort gewinnt.** Antworten mehrere, wird die erste geliefert und die übrigen
   verworfen. Dass das passieren *kann*, ist der Preis eines Broadcasts; dass Abstinenz die Regel
   ist, macht es unwahrscheinlich.
3. **Keine Antwort innerhalb des Timeouts ist `null`.** Kein `RequestTimeoutException` mehr.
   „Niemand ist zuständig" ist in einem Broadcast ein normaler Ausgang und keine Ausnahme — der
   Spieler ist vielleicht einfach offline. Ein Infrastrukturfehler (Redis nicht erreichbar) wirft
   weiterhin.
4. **Handler-Fehler reisen nicht zum Aufrufer.** Eine geworfene Ausnahme in einem Query-Handler
   wird lokal geloggt und auditiert, aber nicht als Antwort gesendet: ein anderer Prozess könnte
   noch korrekt antworten, und eine Fehlerantwort würde ihn überholen. Für den Aufrufer sieht ein
   gescheiterter Handler wie Abstinenz aus — deshalb ist der Audit-Eintrag hier die einzige Spur
   und entsprechend wichtig.

Timeout: `@QueryService(timeoutMillis = 5000)` am Vertrag, Default 5000 wie heute, überschreibbar
per `bus.query<T>(timeoutMillis = …)`.

### Kein `service` am `@QueryService`

Ein Query adressiert niemanden — es fragt alle, die den Vertrag kennen. Der Kanal ergibt sich
aus dem FQCN der Schnittstelle, ein Dienstname wäre sinnlos. Das ist der sichtbare Unterschied
zu `@RpcService` und der Grund, die beiden nicht in eine Annotation zu quetschen:

| | `@RpcService` | `@QueryService` |
|---|---|---|
| Ziel | ein benannter Dienst oder eine Instanz | alle, die den Vertrag anbieten |
| Rückgabetyp | beliebig, auch `Unit` mit `@FireAndForget` | immer nullable, nie `Unit` |
| Ohne Empfänger | wartet in der durable Queue | `null` nach dem Timeout |
| Nicht-idempotent | läuft einmal | läuft in jedem Prozess, der nicht abstiniert |
| Transport | RabbitMQ | Redis Pub/Sub |

`@FireAndForget` an einer Query-Methode wird von KSP abgelehnt: eine Frage ohne Antwort ist ein
Event.

### Kanäle statt globaler Bus

Zwei Verbesserungen gegenüber `RequestResponseBusImpl` (P8):

| | heute | neu |
|---|---|---|
| Frage | `surf-redis:requests`, alle Prozesse | `surf.eventbus.query.<vertrag-fqcn>`, nur Anbieter des Vertrags |
| Antwort | `surf-redis:responses`, alle Prozesse | `surf.eventbus.reply.<instanceId>`, nur der Fragende |

Damit empfängt ein Prozess nur, was ihn betrifft. Korrelation weiterhin über eine `correlationId`
im JSON-Envelope; der Fragende hält das `CompletableDeferred` und den Timeout, wie heute.

Der Dispatch benutzt denselben Hidden-Class-Invoker wie Events und RPC.

## Wire-Format und Routing

### Events und Queries über Redis

Ein Kanal pro Zweck, und **zwei Kanalfamilien für Events**, weil surf-redis 1.10.1 neben dem
JSON-Weg einen Binär-Codec-Weg hat, den dieser Entwurf erhält statt einzuebnen:

| Nachricht | Kanal | Kodierung |
|---|---|---|
| Event, Standardfall | `surf.eventbus.json.<topic>` | JSON, `kotlinx.serialization` |
| Event mit `BusEventCodec` | `surf.eventbus.bin.<topic>` | der Codec des Event-Typs |
| Query-Frage | `surf.eventbus.query.<vertrag-fqcn>` | JSON |
| Query-Antwort | `surf.eventbus.reply.<instanceId>` | JSON |

Envelope-Felder beim Event: `topic`, `type`, `originInstanceId`, `publishedAtEpochMs`, Payload.
Beim Query: `correlationId`, `callable`, `originInstanceId`, Argumente beziehungsweise
Rückgabewert. `originInstanceId` trägt beim Event die Selbstzustellungs-Filterung, beim Query
die Rückadresse.

Getrennte Präfixe statt einer Kanalfamilie mit Kodierungs-Diskriminator: ein Redisson-`Topic`
ist an genau einen Codec gebunden, und `surf.eventbus.*` als Muster darf die andere Familie
nicht mitfangen. Ein Wildcard-Abonnement zeichnet deshalb beide.

| Abonnement | Redis-Operation | Muster-Prüfung |
|---|---|---|
| wildcardfreies Topic, JSON | `SUBSCRIBE surf.eventbus.json.faction.disbanded` | keine nötig |
| wildcardfreies Topic, Codec | `SUBSCRIBE surf.eventbus.bin.faction.disbanded` | keine nötig |
| Topic mit Wildcard | `PSUBSCRIBE surf.eventbus.json.*` **und** `…bin.*` | lokal, mit `EventTopics` |
| Query-Vertrag | `SUBSCRIBE surf.eventbus.query.<fqcn>` | keine nötig |

Der Umweg über lokale Prüfung bei Wildcards ist unvermeidbar: Redis-Glob und die dokumentierte
Topic-Semantik unterscheiden sich, und die dokumentierte gewinnt.

### Der Binär-Codec bleibt

surf-redis 1.10.1 erlaubt einem Event-Typ, über sein Companion-Object einen `RedisEventCodec`
mitzubringen; solche Events laufen über einen eigenen Binärkanal und werden über eine stabile
`eventId` geroutet. Dazu gehört ein Codec-Fundament (`AbstractCodec`, ByteBuf-Erweiterungen,
VarInt/VarLong/UUID/String-Codecs) und ein JMH-Benchmark, der den Unterschied misst. Das ist eine
Leistungsentscheidung mit Messung dahinter und wird nicht wegvereinfacht.

Im Bus heißt die Schnittstelle `BusEventCodec` und liegt in `surf-eventbus-bus-api` — sie muss
dort liegen, weil ein `@BusEvent`-Typ sie deklariert und die Bus-API keinen Redis-Typ kennen
darf. Das Codec-Fundament und die Registry ziehen mit nach `surf-eventbus-common`
beziehungsweise `surf-eventbus-redis-core`. Die `eventId`-Routing-Ebene entfällt dabei: das
Topic aus `@BusEvent` ist der stabile Schlüssel, den `eventId` bisher liefern musste.

### RPC über RabbitMQ

Unverändert aus dem Topologie-Redesign, CBOR: `surf.rpc` als `direct`-Exchange,
`surf.service.<service>` (quorum, durable) für Aufrufe an einen Dienst,
`surf.instance.<instanceId>` (ephemer) für `InstanceTarget`, `surf.reply.<instanceId>`
(ephemer) für Antworten, Retry-Leiter 10 s/60 s/300 s über TTL-Tiers.

Dass `surf.instance.<instanceId>` `autoDelete` ist, ist für `InstanceTarget` genau richtig: ist
der gemeinte Prozess weg, ist der Auftrag gegenstandslos. Ein toter Proxy kann keinen Spieler
mehr verschieben, und die Nachricht auf seine Wiederkehr warten zu lassen wäre falsch.

Was aus der Topologie **verschwindet**: der `surf.events`-Exchange, `surf.events.shared.<service>`,
`surf.events.instance.<instanceId>`, `surf.dlx`, `surf.dlq.<service>` und `surf.unroutable`.

## Dispatch

Ein Weg für Events, Queries und RPC-Handler, in `surf-eventbus-common`:

- **Hidden-Class-Invoker** über `InvokerFactory` aus `surf-api-core` — der Weg, den surf-redis
  heute für Events geht und surf-rabbitmq für RPC-Handler.
- **Typhierarchie-bewusst** bei Events: ein Handler für `FactionEvent` erhält auch
  `FactionDisbandedEvent`. surf-redis' Beschränkung auf exakte Typen fällt weg — sie macht
  `@SurfSubscribe(topic = "faction.#")` mit einem Basistyp-Parameter erst benutzbar.
- **Alle passenden Abonnements laufen.** Ein Handler auf `faction.disbanded` und einer auf
  `faction.#` feuern beide.
- **Ein scheiternder Handler stoppt seine Nachbarn nicht.** Alle Treffer laufen; jeder Fehler
  wird einzeln geloggt und einzeln auditiert, mit Handler-Name und Stacktrace.
- **Unbekannter `type`** — kein Handler im Prozess registriert die Klasse: einmal warnen, einmal
  auditieren, verwerfen. Beides genau einmal pro Typ und Prozesslauf, damit ein Strom
  unbekannter Typen nicht zu einem Strom von Logzeilen und Audit-Zeilen wird.

Validiert wird bei der Registrierung, nicht bei der Zustellung: Parameterzahl, Parametertyp,
Muster-Syntax, freigeschalteter Transport, ein Anbieter pro Query-Vertrag und Prozess. Ein
fehlerhaftes Muster trifft sonst einfach nichts, und das einzige Symptom wäre ein Event, das nie
ankommt.

### Typauflösung bei Abonnements auf Basistypen

Auf dem Draht steht der **konkrete** Typname, registriert hat ein Handler unter Umständen nur
einen Basistyp: `@SurfSubscribe(topic = "faction.#") fun onAny(event: FactionEvent)`. Eine
Auflösung, die nur wörtlich registrierte Typen kennt, verwirft ein solches Event — das
dokumentierte polymorphe Abonnement wäre funktionslos. Genau diesen Fehler hat der heutige
Rabbit-Pfad: `KotlinSerializerNameCache.register(subscription.eventClass)` registriert den
Handler-Parametertyp, `deserializeEvent` sucht nach dem Wire-Namen, und bei einem Basistyp
treffen sich beide nie.

Auflösung in zwei Stufen: zuerst die Registry (billig, exakt, der Normalfall), dann
`Class.forName` über die Classloader der registrierten Listener — nicht über den eigenen, weil
Event-Typen auf Paper und Velocity im abonnierenden Plugin liegen. Geladen wird ausschließlich,
was `SurfBusEvent` erweitert; positive und negative Ergebnisse werden gecacht. Bleibt der Typ
unauflösbar, greift der dokumentierte Weg: warnen, auditieren, verwerfen.

## Lebenszyklus

Ein Modell für beide Transports, `suspend`, beschrieben in `surf-eventbus-common`:

```
build() → subscribe()* / registerService()* → freeze() → connect() → … → disconnect()
```

`freeze()` schließt die Registrierung und prüft sie: Handler müssen bekannt sein, bevor ein
Consumer startet, sonst trifft eine Nachricht einen halb registrierten Handler. Hier scheitert
auch, wer für einen nicht freigeschalteten Transport registriert hat.

`connect()` verbindet beide freigeschalteten Transports; scheitert einer, scheitert `connect()`
und der andere wird geschlossen. Ein Prozess, der halb verbunden weiterläuft, wäre schwerer zu
diagnostizieren als einer, der nicht startet.

surf-redis' blockierendes, Reactor-basiertes `connect()` wird auf `suspend` umgestellt. Das
betrifft die redis-spezifische API sichtbar (`RedisApi.connect()` ist heute `@Blocking`) und
ist Teil des harten Schnitts. Die Reactor-Interna (`Initializable.init(): Mono<Void>`) bleiben
intern erhalten; nur die Naht nach außen wird `suspend`.

**Redis-Sync-Strukturen bleiben an ihre Vorbedingung gebunden:** vor `freeze()` erstellen.
Diese Falle (die READMEs widmen ihr einen eigenen Abschnitt) bleibt bestehen, weil sie in der
Natur der Sache liegt; sie wird nur einmal statt zweimal dokumentiert.

## Konfiguration

Das vierstufige Modell aus surf-rabbitmq wird für alles verbindlich und zieht nach
`surf-eventbus-common`:

```
env > Plugin-YAML > globale YAML > Default
```

Die Env-Schicht selbst kommt nicht aus diesem Projekt, sondern aus surf-api-core; siehe
„Ein Mechanismus für beide Seiten".

### Alle Env-Variablen unter einem Präfix

Jede Variable heißt `SURF_EVENTBUS_*`. Der Transport bleibt im Namen, weil beide einen eigenen
Host, Port und ein eigenes Passwort brauchen — ein flaches `SURF_EVENTBUS_HOST` könnte nur einem
von beiden gehören.

| Bereich | Variablen | Heute |
|---|---|---|
| RabbitMQ-Verbindung | `SURF_EVENTBUS_RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_VHOST`, `_TIMEOUT` | `SURF_RABBITMQ_HOST` usw., 6 Variablen |
| RabbitMQ-Verhalten | `SURF_EVENTBUS_RABBITMQ_REQUEST_TIMEOUT_SECONDS`, `_PUBLISHER_POOL_SIZE`, `_SERVER_PREFETCH_COUNT`, `_PERSIST_REQUESTS`, `_PERSIST_RESPONSES`, `_OUTGOING_REQUEST_CHUNKING_ENABLED`, `_OUTGOING_RESPONSE_CHUNKING_ENABLED` | `SURF_RABBITMQ_*`, 7 Variablen |
| Redis-Verbindung | `SURF_EVENTBUS_REDIS_HOST`, `_PORT`, `_PASSWORD`, `_CLIENT_NAME` | `SURF_REDIS_*`, 4 Variablen |
| Queries | `SURF_EVENTBUS_QUERY_TIMEOUT_MILLIS` | neu |
| Audit | `SURF_EVENTBUS_AUDIT_SERVICE`, `_ENABLED`, `_MAX_PAYLOAD_BYTES`, `_RETENTION_DAYS` | neu |

### Ein Mechanismus für beide Seiten

Beide Transports lesen ihre Variablen heute unterschiedlich, und der bessere Weg steht schon in
surf-redis 1.10.1:

```kotlin
object RedisEnvironment {
    val SURF_REDIS_HOST by env.optional()
    val SURF_REDIS_PORT by env.optionalInt { require("Port must be between 0 and 65535") { it in 0..65535 } }
    val SURF_REDIS_PASSWORD by env.optional(sensitive = true)
}
```

Das ist `dev.slne.surf.api.core.environment.env` aus surf-api-core: Delegates mit Typwandlung,
Inline-Validierung, `named()` für abweichende Variablennamen und `sensitive = true`, das den Wert
aus Fehlermeldungen heraushält. surf-rabbitmq macht dasselbe von Hand — `RabbitMQEnvironmentVariables`
plus die rund 150 Zeilen `EnvironmentOverrideRabbitMQConfig` mit eigenen `text`/`integer`/`boolean`-Parsern
und ohne jede Sonderbehandlung für das Passwort.

Beim Umbenennen wird deshalb nicht umbenannt, sondern zusammengeführt: ein
`EventbusRabbitEnvironment` und ein `EventbusRedisEnvironment` mit `env`-Delegates und
`.named("SURF_EVENTBUS_…")`, Rabbits handgeschriebene Env-Schicht entfällt, und das Passwort ist
auf beiden Seiten `sensitive`.

Was Redis dagegen fehlt, ist die YAML-Schichtung: `RedisConfig` ist eine `SpongeYmlConfigClass`,
liest ausschließlich `config.yml` und legt per `overwriteFromEnv()` die Env-Schicht darüber —
zwei Schichten, keine globale YAML. Die globale Schicht kommt von der Rabbit-Seite dazu, und
damit gilt das vierstufige Modell tatsächlich für alles. `RedisCredentialsProvider` bleibt als
Erweiterungspunkt bestehen, baut seine URI aber aus der aufgelösten Konfiguration.

### Eine alte Variable ist ein Startfehler

Findet der Bus beim Start eine der 17 alten Variablen (13-mal `SURF_RABBITMQ_*`, 4-mal
`SURF_REDIS_*`), scheitert er und nennt den neuen Namen:

```
IllegalStateException:
  SURF_RABBITMQ_HOST is set but no longer read.
  -> rename it to SURF_EVENTBUS_RABBITMQ_HOST
```

Ohne diese Prüfung wäre der schlimmste Ausgang wahrscheinlich: eine vergessene Variable fällt
auf den Default `localhost` zurück, der Prozess startet scheinbar normal und verbindet sich mit
nichts — oder mit einem Entwicklungsbroker, der zufällig dort läuft.

Entfällt: `eventbus.provider` und `SURF_EVENTBUS_PROVIDER`. Welche Transports ein Prozess
benutzt, steht im Code am Builder, weil es keine Betriebsentscheidung mehr ist, sondern aus
dem folgt, was der Prozess aufruft.

## Audit statt Dead-Letter-Queue

### Warum

`surf.dlq.<service>` hält die gescheiterte Nachricht und verliert die Ursache. Der Ersatz hält
beides und ist abfragbar: ein Microservice nimmt Meldungen über RabbitMQ an und schreibt sie
in die Datenbank.

### Meldepfade

Auditiert wird jeder Pfad, auf dem eine Nachricht **lautlos** verschwinden würde:

| `kind` | Wann | Ersetzt |
|---|---|---|
| `HANDLER_FAILED` | ein RPC-Handler hat geworfen — jeder Versuch der Leiter meldet, der letzte mit `terminal = true` | `surf.dlq.<service>` |
| `UNROUTABLE` | der Broker gibt eine Nachricht per `basic.return` zurück | `surf.unroutable` |
| `UNDESERIALIZABLE` | Paket kann nicht deserialisiert werden (Poison Message) | Reject → DLQ |
| `CHUNK_SERIES_EXPIRED` | eine Chunk-Serie läuft im `RabbitPacketChunkAssembler` ab, ohne vollständig zu werden | nichts — war bisher spurlos |
| `UNKNOWN_EVENT_TYPE` | Event-Typ ist im Prozess nicht auflösbar; einmal pro Typ und Prozesslauf | nichts — war nur eine Logzeile |
| `EVENT_HANDLER_FAILED` | ein `@SurfSubscribe`-Handler hat geworfen | nichts — war nur eine Logzeile |
| `QUERY_HANDLER_FAILED` | ein Query-Handler hat geworfen. Für den Fragenden nicht von Abstinenz zu unterscheiden | nichts — war nur eine Logzeile |

**Nicht auditiert wird, was der Aufrufer selbst erfährt.** Ein `reject-publish` auf einer
vollen Service-Queue nackt den Publish, der Aufruf wirft, der Aufrufer entscheidet. Ebenso ein
Timeout bei einem wartenden RPC-Aufruf — und ebenso die Anfrage selbst, die danach in der Queue
per `expiration` verfällt: ihr Aufrufer hat den Timeout schon gesehen, eine zweite Spur wäre
Rauschen. Ein Query ohne Antwort ist ebenfalls kein Verlustpfad, sondern ein `null`.

Die letzten drei Zeilen sind der Grund, warum das Audit nicht nur eine Umleitung ist: die
Fehlerbehandlung auf dem Redis-Pfad war bisher ausschließlich ein Log. Sie ist es weiterhin —
aber zusätzlich eine Zeile mit Stacktrace, Handler und Payload, an derselben Stelle wie alles
andere.

### Transport der Meldung

Das Audit benutzt seine eigene Medizin: `surf-eventbus-audit-api` deklariert

```kotlin
@RpcService(service = "surf-eventbus-audit")
interface AuditService {
    @FireAndForget
    suspend fun report(report: AuditReport)
}
```

Die durable Service-Queue des Microservices trägt die Meldung, während er neu startet oder
deployt wird — genau die Eigenschaft, für die die DLQ da war. Und weil es ein
`@FireAndForget`-Aufruf ist, wartet der meldende Prozess nicht auf ihn.

Drei Schutzregeln, ohne die sich das Audit selbst auffressen könnte:

1. **Meldungen werden nie auditiert.** Sie gehen mit `mandatory = false` raus. Eine fehlende
   Audit-Queue führt so nicht zu einer `UNROUTABLE`-Meldung über eine Meldung.
2. **Der Audit-Dienst meldet nicht an sich selbst.** Ein Prozess, dessen `serviceName` der
   Audit-Dienst ist, protokolliert lokal und schickt nichts.
3. **Fehler auf dem Meldeweg bleiben lokal.** Scheitert der Publish, wird das geloggt; der
   ursprüngliche Pfad läuft weiter und scheitert nicht daran, dass das Audit nicht erreichbar
   war.

**Flutkontrolle.** Meldungen laufen über eine begrenzte Warteschlange im Prozess (256
Einträge). Läuft sie über, wird verworfen und alle 30 Sekunden eine Zeile mit der Anzahl der
verworfenen Meldungen geschrieben. Ein Ausfall, der zehntausend Nachrichten scheitern lässt,
darf nicht dadurch schlimmer werden, dass zehntausend Meldungen den Broker füllen.

**Prozesse ohne RabbitMQ.** Wer nur `withRedis()` freigeschaltet hat, kann nicht melden. Der
Bus warnt das beim Start einmal und setzt eine `AuditSink`-Implementierung ein, die nur loggt.

### Identität einer Nachricht

Die Retry-Leiter republisht dieselbe Nachricht bis zu viermal, jedes Mal möglicherweise in
einem anderen Prozess. Damit die Versuche in der Datenbank zusammenfinden, stempelt der
meldende Prozess beim **ersten** Fehlschlag einen Header `x-surf-audit-message-id`. Er
überlebt die Republishes, weil `RetryPublisher.withNextAttempt` die Header kopiert. Pfade ohne
Leiter (`UNROUTABLE`, `UNDESERIALIZABLE`, Event- und Query-Pfade) erzeugen pro Vorfall eine
frische ID.

### Tabellen

Drei Tabellen, Exposed über R2DBC, `AuditableLongIdTable` wie im Factions-Vorbild. Die
Aufteilung folgt daraus, dass eine Nachricht mehrfach scheitern kann: die Nutzlast steht
einmal, die Fehlschläge stehen einzeln.

**`eventbus_audit_messages`** — eine Zeile pro Nachricht

| Spalte | Zweck |
|---|---|
| `message_uuid` | Identität, unique. Zweite Meldung zur selben Nachricht fügt keine Zeile hinzu |
| `kind` | einer der Werte oben |
| `origin_service`, `origin_instance` | wessen Nachricht es war |
| `exchange`, `routing_key`, `origin_queue` | wo sie herkam; bei Events und Queries der Kanal |
| `message_type` | FQCN beziehungsweise Wire-Typname, nullable |
| `contract`, `callable` | Schnittstelle und Methode bei RPC- und Query-Aufrufen, nullable |
| `correlation_id` | Korrelation, nullable |
| `payload_encoding` | `CBOR` oder `JSON` |
| `payload_size_bytes`, `payload_truncated` | echte Größe und ob gekappt wurde |
| `payload` | die Nutzlast, gekappt auf `audit.max-payload-bytes` (256 KiB) |
| `first_seen_at` | Zeitpunkt des ersten Vorfalls |

**`eventbus_audit_failures`** — eine Zeile pro Fehlschlag

| Spalte | Zweck |
|---|---|
| `message_id` | Referenz auf `eventbus_audit_messages` |
| `attempt` | 1-basiert; `0` für Pfade ohne Leiter |
| `terminal` | `true` beim letzten Versuch — die Zeile, die früher die DLQ war |
| `retry_tier` | in welchem Tier der nächste Versuch wartet, nullable |
| `handler` | `Klasse#Methode`, nullable |
| `exception_class`, `exception_message`, `stacktrace` | die Ursache; gekappt auf 8 bzw. 32 KiB |
| `reported_by_service`, `reported_by_instance` | wer gemeldet hat |
| `failed_at` | Zeitpunkt |

Index auf `(message_id, attempt)`.

**`eventbus_audit_headers`** — eine Zeile pro AMQP-Header

| Spalte | Zweck |
|---|---|
| `message_id` | Referenz |
| `name`, `value` | Header, Wert gekappt auf 4 KiB |

Unique auf `(message_id, name)`. Header sind offen und tragen die Forensik: Versuchszähler,
`x-death`, Sender-Version, Chunk-Metadaten.

### Schreibverhalten: niemals hart scheitern

Die Implementierung von `AuditService.report` tut genau das:

1. Nachrichtenzeile einfügen, bei bekannter `message_uuid` überspringen
2. Fehlschlagzeile einfügen
3. Header einfügen, nur beim ersten Mal

Alles in einer `suspendTransaction`. Um den ganzen Block liegt ein `catch (Throwable)`, das
auf `SEVERE` mit vollständigem Inhalt der Meldung loggt — die Konsole bleibt die Spur, wenn
die Datenbank es nicht ist — und danach normal zurückkehrt, sodass die Nachricht geackt wird.
Kein Nack, kein Rethrow: `@FireAndForget` heißt ohnehin, dass niemand auf eine Antwort wartet,
und eine geworfene Ausnahme würde die Meldung nur auf die Retry-Leiter legen.

Der Grund ist Belastbarkeit gegen die eigene Infrastruktur: eine nicht erreichbare Datenbank
darf nicht dazu führen, dass sich Meldungen in der Audit-Queue stauen, bis sie die
Broker-Grenze reißt.

### Retention

Beim Start und danach täglich löscht der Microservice Nachrichtenzeilen, deren
`first_seen_at` älter als `audit.retention-days` (30) ist, samt abhängiger Zeilen. Eine
Tabelle, die jeden Fehlschlag der Flotte aufnimmt, braucht eine Obergrenze, und das Löschen
gehört zum Dienst, nicht in ein Runbook.

### Der Microservice

Nach dem Muster von `surf-factions-microservice`, gebaut mit `dev.slne.surf.microservice`:

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
    }

    override suspend fun onDisable() {
        bus.disconnect()
        databaseApi.shutdown()
    }
}
```

Er schaltet nur `withRabbit()` frei: er nimmt Meldungen an und schreibt sie weg, er hört keine
Events und beantwortet keine Queries.

Die Typen liegen in drei Schichten, damit niemand mehr sieht, als er braucht:

| Typ | Modul | Warum dort |
|---|---|---|
| `AuditReport`, `AuditKind`, `AuditSink` | `surf-eventbus-bus-api` | Event- und Query-Dispatch melden auch, und `-bus-core` soll dafür keinen Broker-Typ sehen. Es kennt nur `AuditSink.report(AuditReport)` |
| `AuditService` | `surf-eventbus-audit-api` | Der RPC-Vertrag, der den Report trägt. Kennt beide Seiten |
| Tabellen, Repository, `AuditServiceImpl` | `surf-eventbus-audit-microservice` | Die einzige Stelle mit Datenbank-Abhängigkeit |

`surf-eventbus-rabbitmq-core` implementiert `AuditSink` über einen `AuditService`-Proxy; der Bus
verdrahtet sie, wenn `withRabbit()` freigeschaltet ist.

## Was wo bleibt

| Bereich | Bleibt bei | Grund |
|---|---|---|
| RPC (`rpc<T>()`, `@RpcService`, `@FireAndForget`) | RabbitMQ | Zustellung an genau eine Instanz, garantiert und persistent |
| Ziele (`ServiceTarget`, `InstanceTarget`) | RabbitMQ | Setzen durable beziehungsweise per-Instanz-Queues voraus |
| Retry-Leiter | RabbitMQ | Setzt Broker-Queues voraus |
| Events (`publish`, `@SurfSubscribe`) | Redis | Broadcast ohne Persistenz ist genau, was ein Event ist |
| Queries (`query<T>()`, `@QueryService`) | Redis | Broadcast mit Rückkanal; Persistenz wäre hier sogar falsch |
| Sync-Strukturen, Caches, Lua | Redis | Verteilte Datenstrukturen, kein Messaging |
| Circuit Breaker | `surf-eventbus-common` | Transportfrei, aber kein eigenes Artefakt mehr wert |
| KSP | `surf-eventbus-ksp` | Ein Prozessor für beide Vertragsarten |

### Warum ein Query kein RPC über Redis ist

Der Unterschied ist nicht die Implementierung, sondern die Zusage. Ein RPC-Aufruf sagt „genau
einer tut das"; über Pub/Sub wäre das eine Lüge, weil jeder zuhörende Prozess den Handler
ausführt. Ein Query sagt „wer zuständig ist, antwortet" — und macht die Abstinenz zur
Pflichtübung im Rückgabetyp, damit die Mehrfachausführung sichtbar bleibt statt versteckt.

Deshalb bleibt `rpc<T>()` rabbit-gebunden. Echte RPC-Parität über Redis wäre nur über **Redis
Streams** machbar: Consumer-Groups, `XREADGROUP`, `XACK`, `XAUTOCLAIM`,
Pending-Entry-Verwaltung und Trimming — ein größeres Subsystem als der ganze restliche Bus. Die
Aufgabenteilung nimmt ihm den Anlass: wer RPC braucht, schaltet `withRabbit()` frei.

Die Tür bleibt offen: eine Streams-Implementierung könnte später durable Events und RPC über
Redis nachliefern, ohne die API zu ändern.

### Wann `rpc`, wann `query`

| Situation | Verb |
|---|---|
| Ich kenne den Dienst, der es tun soll | `rpc<T>()` |
| Ich kenne die Instanz, die es tun soll | `rpc<T>(InstanceTarget(id))` |
| Es muss verlässlich passieren, auch wenn gerade niemand läuft | `rpc<T>()` auf `@FireAndForget` |
| Ich weiß nicht, wer zuständig ist, und will eine Antwort | `query<T>()` |
| Alle sollen es erfahren, niemand muss antworten | `publish(event)` |

### Wofür `InstanceTarget` da ist

`InstanceTarget` hat heute **keinen Consumer** — es ist Teil der 2.0-Fläche, die noch nicht in
Benutzung ist. Sein Anwendungsfall existiert aber produktiv, nur als Broadcast gebaut: surf-cores
`ExecuteCommandServerRequest` („führe `/x` auf lobby-03 aus") und `ShutdownServerRequest`
(„fahre lobby-03 runter") nennen beide einen Zielserver und werden an die ganze Flotte gesendet,
wo jeder Prozess prüft, ob er gemeint ist.

Das ist der Fall: **eine Operation auf einem namentlich bekannten Server.** Nicht lobby-01, das
lobby-03 etwas erzählen will — der Aufrufer ist ein Proxy, ein Adminwerkzeug oder ein
Microservice, und die Adresse steht bereits im Befehl. Für lobby-zu-lobby-Verkehr ist es das
falsche Werkzeug; wer dort landet, sollte den Zustand eher in eine Redis-Struktur legen.

Warum nicht einfach ein Query, wo das doch jetzt existiert:

| | `query<T>()` | `rpc<T>(InstanceTarget(id))` |
|---|---|---|
| Nachrichten | ein Publish, jeder Prozess deserialisiert und verwirft | ein Publish, ein Empfänger |
| Ziel ist offline | `null`, nach dem vollen Timeout | sofortiger `UNROUTABLE`-Fehler mit Namen |
| Ziel gibt es nicht (Tippfehler) | `null`, nicht von „offline" zu unterscheiden | sofortiger Fehler |
| Aufwand | wächst mit der Flottengröße | konstant |

Der entscheidende Unterschied ist die zweite Zeile. „Fahre lobby-03 runter" muss unterscheiden
können zwischen „getan", „lobby-03 ist nicht verbunden" und „der Handler ist kaputt". Ein Query
liefert für die letzten beiden dasselbe `null`, und zwar erst nach fünf Sekunden. Für einen
Operatorbefehl ist das die falsche Antwort.

Der Preis ist gering: eine ephemere Queue pro Prozess, die bereits deklariert wird, und eine
Variante an einem `sealed interface`. Fällt die Begründung weg, fällt mit `InstanceTarget` auch
`surf.instance.<instanceId>` aus der Topologie — dann bliebe `instanceName` nur noch für
`originInstanceId` und die Reply-Queue nötig.

## Build, Shading, Koordinaten

- **Gruppe** für alle Module: `dev.slne.surf.eventbus`. **Version** 2.0.0.
- **`rootProject.name`** wird `surf-eventbus`. Das GitHub-Repository umbenennen ist ein
  manueller Schritt außerhalb dieses Plans.
- **Verschachtelte Module** in `settings.gradle.kts`
  (`include("surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api")`), wie in surf-factions und
  surf-microservice.
- **Netty** konvergiert auf 4.2.16: RabbitMQ pinnt 4.2.16 nach dem Pom des amqp-clients, Redis
  pinnt 4.2.15 nach dem Pom von Redisson 4.6.1. Ein Patch Unterschied — aber die Konvergenz
  bedeutet, dass Redisson gegen eine Version läuft, die es selbst nicht testet. Der Kommentar am
  Pin sagt das, und ein Redisson-Sprung, der Netty weiterzieht, entscheidet die Richtung neu.
  Eine Relocation-Basis: `dev.slne.surf.eventbus.shaded.io.netty`. Der
  `META-INF/native`-Mangling-Block und die Relocation-Liste (Kryo, Reactor, Redisson, Jodd,
  ByteBuddy, Objenesis, SnakeYAML) existieren danach einmal.
- **Ein Plattform-Plugin pro Plattform** statt zwei. Es enthält beide Transports geshadet.
  Der Preis ist ein größeres Jar; der Gegenwert ist ein Plugin statt zwei auf jedem Server und
  eine Netty-Kopie statt zwei. Verbindungen werden weiterhin faul aufgebaut: ein Bus ohne
  `withRedis()` erzeugt keinen Redisson-Client.
- **Der Audit-Microservice** wird über `dev.slne.surf.microservice` gebaut
  (`withMicroserviceApi()`) und hängt für Broker und Bus an `surf-eventbus-api` /
  `surf-eventbus-core` dieses Repositories, nicht über `withRabbitModule(...)`: dessen
  `RabbitModule`-Enumeration nennt noch die 1.6.x-Module (`client-api`, `common-api`,
  `server-api`, `surf-rabbitmq-server`), die es nach dem Topologie-Redesign nicht mehr gibt.
- **ABI-Dumps** (`api/*.api`) werden für alle Module neu erzeugt; die Binary-Compatibility-
  Validierung bleibt aktiv. Die Aggregate haben keine eigene Fläche und damit keinen Dump.
- **`TransportInfo`** zieht unverändert mit um: die io_uring/epoll/kqueue/NIO-Erkennung, die in
  1.10.1 den früheren `IoUringRedissonPatcher` ersetzt hat. Sie ist die einzige Stelle, die
  Netty-Transportklassen direkt anfasst, und deshalb die erste, die eine falsche
  Relocation-Basis bemerkt.
- **Ein Standalone-Modul** statt zwei: `StandaloneRedisInstance` und
  `StandaloneRabbitMqInstance` tun dasselbe — einen Instanznamen und einen Konfigurationspfad
  setzen und die Instanz laden — und werden zu einem `surf-eventbus-standalone`
  zusammengeführt, das der Audit-Microservice und jeder künftige Microservice benutzt.

## Was an der API entfällt

Die Fläche dieses Projekts ändert sich hart; ein gemischter Betrieb alt/neu funktioniert nicht.
Vollständige Liste, damit der Umbau planbar ist:

| Entfällt | Ersatz |
|---|---|
| `dev.slne.surf.rabbitmq.*` | `dev.slne.surf.eventbus.rabbitmq.*` |
| `dev.slne.surf.redis.*` | `dev.slne.surf.eventbus.redis.*` |
| `dev.slne.surf.circuitbreaker.*` | `dev.slne.surf.eventbus.common.circuitbreaker.*` |
| Koordinaten `dev.slne.surf.rabbitmq:*`, `dev.slne.surf.redis:*` | `dev.slne.surf.eventbus:surf-eventbus-*` |
| `RedisEvent`, `@OnRedisEvent`, `RedisEventBus`, `RedisApi.publishEvent()`, `RedisApi.subscribeToEvents()` | `SurfBusEvent`, `@SurfSubscribe`, `bus.publish()`, `bus.subscribe()` |
| `RedisEvent.originatesFromThisClient()` | Default `includeSelf = false`; sonst `SurfBusEvent.originInstanceId` |
| `RedisEvent.timestamp` | `SurfBusEvent.publishedAtEpochMs` |
| `RedisRequest`, `RedisResponse`, `@HandleRedisRequest`, `RequestResponseBus`, `RequestTimeoutException` | `@QueryService`-Vertrag, `bus.query<T>()`, `bus.registerService<T>(impl)`, `null` statt Timeout-Ausnahme |
| `RabbitEventPacket`, `@RabbitEvent`, `@RabbitSubscribe`, `SubscriptionMode` | `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe` (auf Redis) |
| `RabbitRequestPacket`, `RabbitResponsePacket`, `@RabbitHandler`, `respond()` | `@RpcService`-Methoden, für den antwortlosen Fall mit `@FireAndForget` |
| `StringResponsePacket` und die übrigen Standard-Antwortpakete | Rückgabetypen der Vertragsmethode |
| `SurfRabbitApi.send()`, `registerRequestHandler()`, `publish()`, `registerListener()` | `bus.rpc<T>()`, `bus.publish()`, `bus.subscribe()` |
| Koordinate `surf-rabbitmq-ksp` | `surf-eventbus-ksp` |
| `RedisApi.connect()` blockierend | `suspend`, über `bus.connect()` |
| `surf.dlq.<service>`, `surf.unroutable`, `surf.dlx` | Audit-Tabellen |
| `surf-redis:requests`, `surf-redis:responses` | `surf.eventbus.query.<fqcn>`, `surf.eventbus.reply.<instanceId>` |
| `SURF_RABBITMQ_*` (13), `SURF_REDIS_*` (4) | `SURF_EVENTBUS_RABBITMQ_*`, `SURF_EVENTBUS_REDIS_*`. Eine gesetzte alte Variable ist ein Startfehler |
| `RabbitMQEnvironmentVariables`, `EnvironmentOverrideRabbitMQConfig` | `env`-Delegates aus surf-api-core, wie surf-redis sie schon benutzt |
| `RedisEventCodec`, `RedisEventCodecRegistrar`, `eventId` | `BusEventCodec` in `surf-eventbus-bus-api`; das Topic aus `@BusEvent` ersetzt `eventId` |
| `surf-redis:events`, `surf-redis:events:binary` | `surf.eventbus.json.<topic>`, `surf.eventbus.bin.<topic>` |
| `eventbus.provider`, `SURF_EVENTBUS_PROVIDER` | `withRabbit()` / `withRedis()` am Builder |

**Der Umbau der Consumer ist nicht Teil dieses Vorhabens.** Kein anderes Repository wird
angefasst, und es entsteht auch kein Migrationsleitfaden — die Tabelle oben ist die
Beschreibung der neuen Fläche, nicht eine Arbeitsanweisung an fremde Projekte.

## Verifikation

RabbitMQ bringt eine Testsuite mit Testcontainers mit. surf-redis hat inzwischen sieben
Testklassen — Codec-Rundläufe, `EventCodecRegistry` samt Lincheck-Concurrency-Test,
`BulkMutationScriptTest` — sowie einen JMH-Benchmark für den Event-Transport. Was dort fehlt, ist
**jeder Test gegen einen echten Redis**: alles Vorhandene läuft ohne Server. Genau die Lücke
schließen die beiden folgenden Suiten, denn Events und Queries wandern vollständig auf diesen
Transport.

Die bestehenden Redis-Tests wandern unverändert mit; der Lincheck-Test der Codec-Registry ist
dabei der wertvollste, weil die Registry im Bus zusätzlich vom Event-Dispatch gelesen wird.

### Event-Suite (Redis, Testcontainers)

| # | Test | Erwartung |
|---|---|---|
| 1 | Broadcast, drei Instanzen, ein Event | alle drei verarbeiten |
| 2 | Wildcardfreies Topic | trifft nur passende Abonnenten |
| 3 | `*` | trifft genau ein Segment |
| 4 | `#` | trifft null oder mehr Segmente |
| 5 | Zwei überlappende Muster | beide Handler feuern |
| 6 | Typhierarchie | Handler auf Basistyp erhält Subtyp |
| 7 | `includeSelf = false` | unterdrückt das eigene Event |
| 8 | `includeSelf = true` | liefert es |
| 9 | Ein scheiternder Handler | blockiert seine Nachbarn nicht |
| 10 | Event ohne Abonnenten | kein Fehler |
| 11 | Alle Abonnenten offline | Event verfällt — dokumentierte Eigenschaft, kein Bug |
| 12 | Erfundener `type`, per Rohclient publiziert | nicht zugestellt, Consumer lebt weiter, genau ein Audit-Eintrag |
| 12a | Event mit `BusEventCodec` | läuft über `surf.eventbus.bin.<topic>`, kommt binär an, Rundlauf stimmt |
| 12b | Wildcard-Abonnement, ein JSON- und ein Codec-Event auf passenden Topics | beide werden zugestellt |
| 12c | Codec auf einer Seite registriert, auf der anderen nicht | Empfänger warnt und verwirft, ein Audit-Eintrag, Consumer lebt weiter |

### Query-Suite (Redis, Testcontainers)

| # | Test | Erwartung |
|---|---|---|
| 13 | Drei Anbieter, einer antwortet, zwei abstinieren | Aufrufer bekommt genau diese Antwort |
| 14 | Zwei antworten | erste gewinnt, zweite wird verworfen, kein Fehler |
| 15 | Alle abstinieren | `null` nach dem Timeout, keine Ausnahme |
| 16 | Kein Anbieter registriert | `null` nach dem Timeout |
| 17 | Ein Handler wirft, ein anderer antwortet korrekt | Aufrufer bekommt die korrekte Antwort, ein `QUERY_HANDLER_FAILED`-Eintrag |
| 18 | Ein Handler wirft, kein anderer antwortet | `null`, ein Audit-Eintrag |
| 19 | Prozess ohne den Vertrag | empfängt den Kanal nicht (kein Abonnement) |
| 20 | Antwortkanal | nur der fragende Prozess empfängt die Antwort |
| 21 | Nicht-nullbarer Rückgabetyp im Vertrag | KSP-Fehler zur Compile-Zeit |
| 22 | `@FireAndForget` an einer Query-Methode | KSP-Fehler zur Compile-Zeit |
| 23 | Zwei Anbieter desselben Vertrags im selben Prozess | `freeze()` scheitert |

### RPC-Suite (RabbitMQ, Testcontainers)

| # | Test | Erwartung |
|---|---|---|
| 24 | Wartender Aufruf, drei Instanzen des Dienstes | genau eine verarbeitet, Aufrufer bekommt deren Antwort |
| 25 | `@FireAndForget`-Aufruf | Aufrufer kehrt vor der Verarbeitung zurück |
| 26 | `@FireAndForget`, Dienst offline, danach Start | Auftrag überlebt in der durable Queue |
| 27 | Wartender Aufruf, Dienst offline | Timeout beim Aufrufer, keine Meldung im Audit |
| 28 | `InstanceTarget` auf eine von drei Instanzen | genau diese verarbeitet |
| 29 | `InstanceTarget` auf eine tote Instanz | Nachricht verfällt, `UNROUTABLE`-Eintrag |
| 30 | `@FireAndForget` mit Rückgabetyp ≠ `Unit` | KSP-Fehler zur Compile-Zeit |
| 31 | `@RpcService` ohne `service`, Proxy ohne Argument | lauter Fehler mit beiden Auswegen im Text |
| 32 | Proxy-Cache | zweimal `rpc<T>(InstanceTarget(x))` liefert dieselbe Instanz |

### Transport-Freischaltung

| # | Test | Erwartung |
|---|---|---|
| 33 | `bus.publish()` oder `bus.query<T>()` ohne `withRedis()` | lauter Fehler, nennt `.withRedis()` |
| 34 | `@SurfSubscribe` oder `@QueryService` registriert ohne `withRedis()` | `freeze()` scheitert, nennt Handler und Builder-Aufruf |
| 35 | `bus.rpc<T>()` ohne `withRabbit()` | lauter Fehler, nennt `.withRabbit()` |
| 36 | Builder ohne jeden Transport | `build()` scheitert |
| 37 | Nur `withRedis()`, ein Handler scheitert | Startwarnung „Audit nicht erreichbar", Fehler nur im Log |

### Audit-Suite (RabbitMQ + Datenbank, Testcontainers)

| # | Test | Erwartung |
|---|---|---|
| 38 | Handler-Fehler unter Rabbit | eine Nachrichtenzeile, vier Fehlschlagzeilen, die letzte `terminal` |
| 39 | Datenbank nicht erreichbar | `SEVERE`-Log mit vollem Inhalt, Nachricht wird geackt, keine Wiederzustellung, Microservice lebt weiter |
| 40 | Unroutable Publish | Audit-Eintrag `UNROUTABLE`; `surf.unroutable` existiert nicht mehr |
| 41 | Undeserialisierbares Paket | Audit-Eintrag `UNDESERIALIZABLE`, kein Requeue |
| 42 | Abgelaufene Chunk-Serie | Audit-Eintrag `CHUNK_SERIES_EXPIRED` |
| 43 | Redis-Event-Handler scheitert | Audit-Eintrag `EVENT_HANDLER_FAILED` mit Handler und Stacktrace |
| 44 | Meldung an einen abwesenden Audit-Dienst | kein `basic.return`, keine Meldung über die Meldung, Aufrufer unbeeinflusst |
| 45 | Der Audit-Dienst selbst scheitert | keine Meldung an sich selbst, nur Log |
| 46 | Flutkontrolle | über 256 gestaute Meldungen werden verworfen und gezählt gemeldet |
| 47 | Retention | Zeilen älter als `audit.retention-days` sind nach dem Lauf weg, jüngere nicht |
| 48 | Payload über `max-payload-bytes` | gekappt gespeichert, `payload_truncated = true`, echte Größe erhalten |
| 49 | Frisch deklarierte `surf.service.*` | trägt die neuen Argumente, kein `x-dead-letter-exchange` |

### Unit-Tests, ohne Broker

- `EventTopics`: Muster-Validierung und Matching (bestehend, wandert mit)
- Registry: Parameterzahl, Parametertyp, Muster-Syntax, Transport-Freischaltung, ein Anbieter
  pro Query-Vertrag
- Fehlermeldungstexte der Freischaltungsprüfung, weil sie Teil der API sind
- KSP: Descriptor-Form für `@FireAndForget` und `@QueryService`, Ablehnung eines Rückgabetyps
  ≠ `Unit` beim einen und eines nicht-nullbaren beim anderen
- Config-Layering `env > Plugin-YAML > globale YAML > Default`, für RabbitMQ **und** neu für Redis
- Startprüfung auf alte `SURF_RABBITMQ_*`-Variablen: jede der 13 löst einen Fehler aus, der den
  neuen Namen nennt
- Envelope-Serialisierung JSON (Event, Frage, Antwort), Rundlauf; RPC-Paket-Serialisierung CBOR
- `AuditReport`-Rundlauf inklusive Kappungsgrenzen
- Flutkontrolle: Warteschlangengrenze, Zählung, Wiederaufnahme
- CircuitBreaker: die bestehenden vier Testklassen, unverändert bis auf Package
- Die sieben bestehenden Redis-Testklassen, unverändert bis auf Package: Codec-Rundläufe,
  `EventCodecRegistry` samt Lincheck-Test, `BulkMutationScriptTest`
- Der JMH-Benchmark des Event-Transports zieht mit um und bleibt lauffähig
- `surf-eventbus-common` referenziert weder RabbitMQ- noch Redis- noch Redisson-Typen
  (Nachfolger von `SharedPackagePurityTest` und `NoRabbitMqDependencyTest`)

### Bestehende Tests

Die RabbitMQ-Suite (Topologie, RPC, Competing Consumers, Retry, Chunking, Broker-Neustart,
Queue-Overflow, Unroutable, Failure-Szenarien) läuft weiter, mit drei Anpassungen: Erwartungen
auf `surf.dlq.*` und `surf.unroutable` werden Erwartungen an Audit-Zeilen; `FireAndForgetTest`
und alle Tests mit eigenen `RabbitRequestPacket`-Klassen werden auf `@RpcService`-Verträge mit
`@FireAndForget` umgeschrieben; die Event-Tests (`EventDeliveryTest`,
`EventSubscriptionRegistryTest`, `EventTopicsTest`) wandern auf den Bus beziehungsweise in die
Event-Suite.

### Bekannte Einschränkung

Auf dieser Maschine ist kein Docker-Daemon erreichbar. Integrationstests werden vollständig
erstellt; ihre Ausführung erfordert einen laufenden Daemon. Solange ungeprüft, wird der Status
als „nicht verifiziert" berichtet, nicht als bestanden.

## Umsetzung in Etappen

Jede Etappe endet übersetzbar mit lauffähigen Tests.

| # | Etappe | Inhalt |
|---|---|---|
| 1 | Umbenennen und umhängen | Module zu `surf-eventbus-rabbitmq/…` und `surf-eventbus-ksp`, Packages zu `dev.slne.surf.eventbus.rabbitmq.*`, Gruppe, `rootProject.name`, Version, KSP-Ausgabe, ABI-Dumps. Rein mechanisch |
| 2 | `surf-eventbus-common` | Fundament herausziehen: Dispatch, Serializer-Caches, Config-Layering, Plattform-Proxies. Rabbits handgeschriebene Env-Schicht durch `env`-Delegates aus surf-api-core ersetzen, Namen zu `SURF_EVENTBUS_RABBITMQ_*`, Startprüfung auf alte Namen. `surf-circuitbreaker` eingliedern, Purity-Test zusammenführen |
| 3 | surf-redis absorbieren | Erst Bestandsaufnahme von v1.10.1 (Codec-Fundament, Stream-Sync, `TransportInfo`, Standalone-Modul, sieben Testklassen), dann Module als `surf-eventbus-redis/…` hereinziehen, Packages zu `dev.slne.surf.eventbus.redis.*`, Env-Namen zu `SURF_EVENTBUS_REDIS_*`, Netty auf 4.2.16, Shading und ABI-Dumps zusammenführen. Grüner Build, unverändertes Verhalten, alle bestehenden Tests grün |
| 4 | `surf-eventbus-bus-api` | `SurfEventBus`, `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe`, `@QueryService`, `EventTopics`, Builder, `AuditReport`/`AuditKind`/`AuditSink` |
| 5 | `surf-eventbus-bus-core` | Registry, Dispatcher, Envelope, Lebenszyklus, Freischaltungsprüfung. Vollständige broker-freie Unit-Abdeckung über die `EventTransport`- und `QueryTransport`-Nähte |
| 6 | Redis-Event-Transport | Kanalfamilien `surf.eventbus.json.<topic>` und `…bin.<topic>`, exaktes und Muster-Abonnement, JSON-Envelope, `BusEventCodec` mit Registry aus 1.10.1 übernommen (`eventId` fällt weg, das Topic ersetzt sie). `RedisEvent`, `@OnRedisEvent`, `RedisEventBus` löschen. Testcontainers-Redis neu aufgesetzt |
| 7 | Rabbit-Event-Teil löschen | `RabbitEventPacket`, `@RabbitEvent`, `@RabbitSubscribe`, `surf.events`, Event-Queues, Event-Retry, `EventDispatcher`, `EventSubscriptionRegistry` |
| 8 | `@FireAndForget` | Annotation, Descriptor-Form, KSP-Generierung, `rpc<T>(target)` mit Proxy-Cache |
| 9 | Paket-API löschen | `RabbitRequestPacket`, `RabbitResponsePacket`, `@RabbitHandler`, `respond()`, `SurfRabbitApi.send()`, `registerRequestHandler()`, Standard-Antwortpakete. Bestehende Tests auf RPC-Verträge umschreiben. Die interne Paket-Schicht bleibt |
| 10 | `@QueryService` | KSP-Generierung mit Nullable-Validierung, Kanäle pro Vertrag und pro Instanz, Korrelation, Timeout auf `null`, Abstinenz. `RedisRequest`, `RedisResponse`, `@HandleRedisRequest`, `RequestResponseBus` löschen |
| 11 | Redis-Lebenszyklus und -Konfiguration | Alle vier `@Blocking`-Stellen von `RedisApi` auf `suspend`, Einhängen in `bus.connect()`. `RedisConfig` von zwei Schichten (`config.yml` plus `overwriteFromEnv()`) auf das vierstufige Layering heben |
| 12 | Audit-Meldeweg | `surf-eventbus-audit-api` mit `AuditService`, `AuditSink`-Implementierung, Identitäts-Header, Flutkontrolle, Schutzregeln. `surf.dlx`, `surf.dlq.*`, `surf.unroutable` und ihre Queue-Arguments entfernen |
| 13 | Audit-Microservice | Drei Tabellen, Repository, `AuditServiceImpl` mit `catch (Throwable)`, Retention, `@AutoService`, Shadow-Main |
| 14 | Aggregate und Plattform | `surf-eventbus-api` und `surf-eventbus-core`, ein Paper-, ein Velocity-Plugin, ein Standalone-Modul aus `StandaloneRedisInstance` und `StandaloneRabbitMqInstance` |
| 15 | Testsuiten | Event-Suite, Query-Suite, RPC-Suite, Freischaltungstests, Audit-Suite |
| 16 | Dokumentation | README zusammenführen, Koordinaten- und Versionswechsel, neue Fläche beschreiben |

Reihenfolge nicht beliebig: 8 vor 9 und 10 nach 4, damit nichts gelöscht wird, bevor der Ersatz
steht; 6 vor 7 aus demselben Grund auf der Event-Seite; 12 vor 13, damit der Microservice gegen
einen existierenden Vertrag gebaut wird; 15 nach 13, weil die Audit-Tests beide Enden brauchen.

## Risiken

| Risiko | Umgang |
|---|---|
| Events verlieren jede Durability: wer offline ist, verpasst sie, und es gibt keine Wiederzustellung | Ist die Zusage, nicht ein Fehler — im README an erster Stelle. Alles, was nicht verloren gehen darf, ist ein `@FireAndForget`-Aufruf. Der Audit-Eintrag macht den Verlust nachträglich sichtbar |
| Die Paket-API zu löschen ist der größte Bruch dieses Vorhabens | Etappe 8 liefert den Ersatz vor der Löschung; Etappe 9 schreibt die eigenen Tests um und belegt damit, dass jedes bisherige Muster ausdrückbar ist |
| Ein Query-Handler, der zuständig ist und wirft, sieht für den Aufrufer wie Abstinenz aus | Genau dafür `QUERY_HANDLER_FAILED` im Audit. Steht als eigener Test in der Query-Suite |
| `null` als Abstinenz kollidiert mit `null` als legitimem Ergebnis | Der Vertrag muss so entworfen sein, dass „nichts gefunden" von „nicht zuständig" unterscheidbar ist — bei `Boolean?` durch `false`, sonst durch einen Ergebnistyp. Steht in der README-Anleitung zum `@QueryService` |
| Zwei Prozesse antworten auf dieselbe Frage und beide haben Nebenwirkungen | Abstinenz ist die Regel, nicht die Ausnahme; der Vertrag gehört so geschnitten, dass genau ein Prozess zuständig ist. Wo das nicht garantierbar ist, ist `rpc` mit `InstanceTarget` das richtige Werkzeug |
| Die Env-Variablen werden umbenannt; eine vergessene fällt auf `localhost` zurück und der Prozess verbindet sich scheinbar erfolgreich mit nichts | Startprüfung: jede gesetzte `SURF_RABBITMQ_*`-Variable ist ein Startfehler, der den neuen Namen nennt. Jede Deployment-Definition (Compose, k8s, systemd) muss mit dem Rollout mitziehen |
| `x-dead-letter-exchange` fällt aus den Queue-Arguments. Eine bestehende `surf.service.*` neu zu deklarieren scheitert mit `PRECONDITION_FAILED (406)` — Argumente sind Teil der Queue-Identität | Vor dem Rollout müssen die alten `surf.service.*`-Queues gelöscht (oder der Vhost neu aufgesetzt) werden. Gehört zum harten Schnitt, steht in der README-Rolloutnotiz, und Test 49 prüft die neuen Argumente |
| Ein Jar mit beiden Transports wird groß und die Shading-Bäume kollidieren (Netty, Reactor, Kryo) | Etappe 3 führt die Zusammenführung isoliert und vor jeder Bus-Arbeit durch, mit grünem Build als Abschlusskriterium |
| Redis hat Unit-Tests, aber keinen einzigen gegen einen echten Server; Verhalten am Draht ist unbelegt | Etappe 3 verschiebt ausschließlich, ohne Änderung, und nimmt die sieben bestehenden Testklassen mit. Absicherung am Draht entsteht in Etappe 6, 10 und 15 |
| surf-redis ist von 1.5.0 auf 1.10.1 gelaufen: Stream-basierte Sync-Strukturen, Codec-Fundament, `TransportInfo`, Standalone-Modul. Ein Spec, das gegen 1.5.0 geschrieben ist, plant Arbeit, die es nicht mehr gibt | Der Stand ist lokal auf `master` (v1.10.1) gezogen und dieser Entwurf dagegen korrigiert. Etappe 3 beginnt mit einer Bestandsaufnahme, bevor etwas verschoben wird |
| Das Audit ist best effort: keine Zeile ist keine Garantie, dass nichts passiert ist | Das lokale Log bleibt die primäre Spur. Verworfene Meldungen werden gezählt und gemeldet |
| Die Audit-Tabellen wachsen unbegrenzt | Retention im Dienst, Default 30 Tage, Payload gekappt |
| Die Aggregat-Module verstecken, was ein Consumer wirklich benutzt | Sie reichen nur `-api`-Module weiter; die Implementierungen bleiben in `surf-eventbus-core` und damit `runtimeOnly` |
| Ohne Docker bleibt ein Teil der Absicherung unausgeführt | Wird als „nicht verifiziert" berichtet, nicht als bestanden |

## Nicht Bestandteil

- **Der Umbau der Consumer.** Kein fremdes Repository wird angefasst, und es entsteht kein
  Migrationsleitfaden. Der Umbau geschieht manuell.
- **Durable Events und echtes RPC über Redis Streams.** Consumer-Groups,
  Pending-Entry-Verwaltung, `XAUTOCLAIM`, Trimming und eine Stream-Fehlerbehandlung wären ein
  größeres Subsystem als der übrige Bus. Die Aufgabenteilung hält die Tür offen: eine spätere
  Implementierung liefert beides nach, ohne die API zu ändern.
- **Sammeln mehrerer Query-Antworten.** `bus.query<T>()` liefert die erste. Ein
  `queryAll<T>(): List<T>` wäre eine eigene Zusage („warte den Timeout immer aus") und wird erst
  gebaut, wenn es einen Fall dafür gibt.
- **Eine Oberfläche über den Audit-Tabellen.** Die Datenbank ist die Schnittstelle; SQL genügt,
  um nachzusehen.
- **Änderungen an surf-microservice**, insbesondere die veraltete `RabbitModule`-Enumeration und
  ein mögliches `withEventbusModule()`.
- **Umbenennung des GitHub-Repositories** und Abschaltung von surf-redis. Manuelle Schritte.
- **Wire-Kompatibilität** zu surf-rabbitmq 1.6.x oder surf-redis 1.5.x.
