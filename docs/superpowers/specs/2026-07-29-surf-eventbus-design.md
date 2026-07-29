# surf-eventbus: Vereinheitlichter Event-Bus mit wählbarem Provider

**Datum:** 2026-07-29
**Status:** Entwurf zur Freigabe
**Ausgangslage:** surf-rabbitmq 1.6.2 auf Branch `feat/topology-redesign` (Redesign zu 2.0
implementiert), surf-redis 1.5.0
**Branch:** `feat/surf-event-bus`, abgezweigt von `feat/topology-redesign`

## Auftrag

Ein Event-Bus, in dem man Events publiziert und broadcastet und lediglich einen **Provider**
wählt — RabbitMQ oder Redis Pub/Sub. surf-redis wird dabei vollständig in dieses Repository
absorbiert; das Repository surf-redis wird danach abgeschaltet.

Die bestehende Topologie-Spec (`2026-07-29-rabbitmq-topology-redesign-design.md`) hat dieses
Vorhaben unter dem Namen `surf-broker` vorgedacht und die broker-neutralen Packages bereits
so geschnitten, dass sie extrahierbar sind — inklusive `SharedPackagePurityTest` als
maschineller Absicherung. Dieser Entwurf löst das ein, mit einer Abweichung: nicht als
Extraktion in ein drittes Projekt, sondern als Zusammenführung in dieses.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Ort | Ein Repository. surf-redis zieht als Module herein, sein Repository wird danach gelöscht |
| Umfang der Vereinheitlichung | **Nur Events.** RPC/Fire-and-Forget bleiben rabbit-spezifisch, Sync-Strukturen/Caches/Request-Response bleiben redis-spezifisch — sichtbar, nicht wegabstrahiert |
| `SHARED` unter Redis | Capability-Modell. Der Redis-Provider kann es nicht und lässt den Start scheitern, statt eine Garantie vorzutäuschen |
| Migration | Harter Schnitt. `RedisEvent`/`@OnRedisEvent`/`RedisEventBus` entfallen, alle Consumer wandern gemeinsam |
| Namen | Artefakt `surf-eventbus`, Gruppe `dev.slne.surf.eventbus`, Typen `SurfEventBus`, `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe` |
| Provider-Wahl | Konfiguration, nicht Code. Fleet-weit einheitlich |
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
| Format | CBOR | JSON, snake_case |
| Fehlerbehandlung | Retry-Leiter 10 s/60 s/300 s, danach DLQ | Log |

Ein Event von einem Transport auf den anderen zu heben heißt heute: Basisklasse tauschen,
Annotation tauschen, Handler-Signatur anpassen, Selbstzustellung neu bedenken.

### P2 — Dreifach dupliziertes Fundament

Belegte Duplikate (Stand 2026-07-29):

- `JavaPluginLoaderProxy` — identisch bis auf das `package`-Statement
- `SerializedPluginDescriptionProxy` — identisch bis auf das `package`-Statement
- `KotlinSerializerCache` — Unterschied in einem Variablennamen
- Der Netty-`META-INF/native`-Mangling-Block in `build.gradle.kts` — Zeichen für Zeichen gleich
- Lifecycle `freeze → connect → disconnect` — zweimal getrennt implementiert, einmal
  `suspend`, einmal blockierend über Reactor
- Zwei Plattform-Plugin-Paare (Paper und Velocity), die dasselbe tun: eine Instanz bereitstellen

### P3 — Jeder Server trägt zwei Plugins und zwei Netty-Versionen

surf-rabbitmq pinnt Netty 4.2.16, surf-redis 4.2.10, beide relocaten nach eigenem Ziel. Ein
Server, der beides nutzt — und das ist der Normalfall — lädt zwei Plattform-Plugins, zwei
Netty-Kopien und zwei Shading-Bäume.

### P4 — surf-core hat den Bus schon von Hand nachgebaut

```kotlin
// surf-core: SurfEventBus.fire() -> Redis -> LocalSurfEventBusListener -> fireLocal()
fun fire(event: SurfEvent) {
    CoreInstance.redisApi.publishEvent(SurfEventFireRedisEvent(event))
}
```

Ein verteilter Event-Bus in 60 Zeilen, ohne Topics, ohne Modi, ohne Fehlerbehandlung,
dispatchend über `KClass`-Reflection auf exakten Typ. Genau die Lücke, die dieses Vorhaben
schließt.

## Zielarchitektur

### Module

| Modul | Inhalt | Abhängig von |
|---|---|---|
| `surf-eventbus-common` | Broker-neutrales Fundament: Handler-Discovery und Hidden-Class-Dispatch, Serializer-Caches, Config-Layering `env > plugin YAML > global YAML > Default`, Plattform-Reflection-Proxies | — |
| `surf-circuitbreaker` | unverändert, weiterhin ohne Transport-Abhängigkeit | — |
| `surf-eventbus-api` | `SurfEventBus`, `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe`, `SubscriptionMode`, `EventTopics`, `TransportCapability`, SPI `EventTransport`/`EventTransportFactory` | `surf-eventbus-common` |
| `surf-eventbus-core` | Subscription-Registry, Dispatcher, Envelope, Provider-Auswahl, Capability-Validierung | `surf-eventbus-api` |
| `surf-rabbitmq-api` | `SurfRabbitApi`: RPC, Fire-and-Forget, Identität, Topologie-Konfiguration. **Ohne** Event-API | `surf-eventbus-common` |
| `surf-rabbitmq-core` | RabbitMQ-Implementierung **plus** `RabbitEventTransport` | `surf-rabbitmq-api`, `surf-eventbus-api`, `surf-circuitbreaker` |
| `surf-rabbitmq-ksp` | unverändert | — |
| `surf-redis-api` | `RedisApi`: Sync-Strukturen, Caches, Request/Response. **Ohne** Event-API | `surf-eventbus-common` |
| `surf-redis-core` | Redisson-Implementierung **plus** `RedisEventTransport` | `surf-redis-api`, `surf-eventbus-api` |
| `surf-platform-paper` | **ein** Paper-Plugin, stellt beide Transport-APIs bereit | alle Core-Module |
| `surf-platform-velocity` | **ein** Velocity-Plugin, ebenso | alle Core-Module |
| `surf-eventbus-test` | Integrationstests inklusive Provider-Parity-Suite | alles |

Keine Zyklen: die Transport-Module kennen `surf-eventbus-api`, nicht `-core`. `-core` wählt
den Transport zur Laufzeit über `ServiceLoader`.

### Package-Namen

Bewusst **unverändert** für alles außer Events:

- `dev.slne.surf.eventbus.*` — der Bus (neu)
- `dev.slne.surf.eventbus.common.*` — geteiltes Fundament (aus beiden Projekten zusammengeführt)
- `dev.slne.surf.rabbitmq.*` — wie heute, minus Event-API
- `dev.slne.surf.redis.*` — wie heute, minus Event-API

Damit ändern Consumer von RPC, Sync-Strukturen und Caches **keine einzige Import-Zeile**; sie
tauschen nur die Dependency-Koordinate. Der harte Schnitt trifft ausschließlich Events.

## Öffentliche API

### Aufbau

```kotlin
val bus = SurfEventBus.builder("surf-factions", dataPath)
    .provider(Provider.RABBIT)          // optional; Default kommt aus der Konfiguration
    .instanceName("lobby-3")            // optional; stabile Instanz-Identität
    .serializers(FactionsSerializers)
    .build()

bus.registerListener(FactionCacheListener)
bus.freezeAndConnect()
```

Der Builder übernimmt die Identitätsoptionen des heutigen `SurfRabbitApiBuilder`
(`serviceName`, `dataPath`, `instanceName`, `serializers`) und gibt sie an den gewählten
Transport weiter. `instanceName` bleibt Voraussetzung für `InstanceTarget` auf der
rabbit-spezifischen API.

Der Bus besitzt den Lebenszyklus des Transports: ein `freeze`, ein `connect`, eine Verbindung
pro Prozess. Die transportspezifische API desselben Prozesses — und damit derselben Verbindung
— ist über einen typisierten Zugang erreichbar:

```kotlin
val rabbit = bus.transport<SurfRabbitApi>()      // scheitert laut, wenn Provider != RABBIT
val factions = rabbit.rpc<FactionService>()

val redis = bus.transport<RedisApi>()            // scheitert laut, wenn Provider != REDIS
val online = redis.createSyncSet<String>("online-players")
```

`transport<T>()` ist die **einzige** Stelle, an der der Transport im Code sichtbar wird. Sie
ist absichtlich unbequem und typgebunden: wer sie benutzt, verlässt die Portabilität, und das
soll man am Aufrufort sehen.

### Events

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

    // Default: genau eine Instanz verarbeitet, durable
    @SurfSubscribe
    suspend fun onDisbanded(event: FactionDisbandedEvent) {
        database.deleteFaction(event.factionId)
    }

    // Jede Instanz verarbeitet, ephemer
    @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
    suspend fun onReload(event: ConfigReloadedEvent) {
        config.reload()
    }

    // Weiteres Muster als das Topic des Events selbst
    @SurfSubscribe(topic = "faction.#", mode = SubscriptionMode.BROADCAST)
    suspend fun onAnyFactionEvent(event: FactionEvent) {
        cache.invalidateAll()
    }
}
```

`@SurfSubscribe(topic, mode, retry, includeSelf)`. Semantik von `topic`, `mode` und `retry`
übernimmt unverändert das heutige `@RabbitSubscribe` — `*` trifft genau ein Segment, `#` null
oder mehr, `SHARED` ist Default, weil der Fehlerfall eines versehentlichen `BROADCAST`
(n-fache Ausführung mit Seiteneffekt) teurer ist als der umgekehrte.

## Capability-Modell

Ein Transport deklariert, was er leisten kann. Der Bus prüft beim `freeze()` jedes
Abonnement gegen diese Deklaration.

| Capability | RabbitMQ | Redis Pub/Sub |
|---|---|---|
| `BROADCAST` | ja | ja |
| `SHARED` | ja — durable Quorum-Queue pro Service | **nein** |
| `RETRY` | ja — 10 s/60 s/300 s | **nein** |
| `DEAD_LETTER` | ja — `surf.dlq.<service>` | **nein** |

Zwei verschiedene Reaktionen, nach Schadensart getrennt:

**Fehlende `SHARED`-Fähigkeit ist ein Startfehler.** Der Modus entscheidet, *wie oft* ein
Handler läuft. Ein stillschweigend nach `BROADCAST` degradierter Handler würde auf acht
Instanzen achtmal in die Datenbank schreiben; ein stillschweigend verworfenes Event verschwindet
ohne Spur. Beides darf nicht passieren:

```
IllegalStateException:
  Handler StatsListener#onBanned uses mode SHARED,
  but provider REDIS supports only BROADCAST.
  SHARED requires a durable per-service queue.
  -> use provider RABBIT, or change the
     handler to BROADCAST if duplicate
     execution on every instance is safe.
```

**Fehlendes `RETRY` ist eine Startwarnung.** `retry = true` ist der Default und stünde
deshalb an praktisch jedem Handler; ein Fehler wäre unbrauchbar. Der Schaden ist außerdem
kleiner: es geht nicht um die Anzahl der Ausführungen, sondern nur darum, was nach einem
Handler-Fehler passiert. Der Bus nennt beim Start jeden betroffenen Handler einmal namentlich:

```
WARNING: provider REDIS does not support RETRY or DEAD_LETTER.
  A failing handler is logged and the event is gone.
  Affected handlers: StatsListener#onJoin, TabListener#onQuit
```

Die Capability-Deklaration ist der Angelpunkt dieses Entwurfs: sie macht die
Zustellgarantie zu einer geprüften Eigenschaft statt zu einer Zeile Dokumentation. Die
Topologie-Spec hat eine gemeinsame `sendRequest()`-Abstraktion abgelehnt, weil sie „am
Aufrufort verbergen würde, welche Garantie gilt". Genau dagegen steht das Capability-Modell.

## Wire-Format und Routing

Der Envelope ist logisch gleich, seine Kodierung gehört dem Transport:

| Feld | Zweck |
|---|---|
| `topic` | Routing-Schlüssel, aus `@BusEvent` |
| `type` | FQCN der Event-Klasse, für die Deserialisierung beim Empfänger |
| `originInstanceId` | wer publiziert hat — Grundlage der Selbstzustellungs-Filterung |
| `publishedAtEpochMs` | Diagnose, ersetzt `RedisEvent.timestamp` |
| Payload | das serialisierte Event |

### RabbitMQ

Unverändert die Topologie aus dem Redesign: `surf.events` als `topic`-Exchange, Routing-Key =
Topic, `surf.events.shared.<service>` (quorum, durable) für `SHARED`,
`surf.events.instance.<instanceId>` (ephemer) für `BROADCAST`, Retry-Leiter und
`surf.dlq.<service>` dahinter. Kodierung CBOR. Das Muster-Matching macht der Broker.

### Redis Pub/Sub

Ein Event wird auf **einen** Kanal publiziert: `surf.eventbus.<topic>`. Abonniert wird
abhängig vom Muster:

| Abonnement | Redis-Operation | Muster-Prüfung |
|---|---|---|
| wildcardfrei (`faction.disbanded`) | `SUBSCRIBE surf.eventbus.faction.disbanded` | keine nötig |
| mit Wildcard (`faction.#`) | `PSUBSCRIBE surf.eventbus.*` | lokal, mit AMQP-Semantik |

Der Umweg über lokale Prüfung bei Wildcards ist unvermeidbar: Redis-Glob und AMQP-Topics
haben unterschiedliche Semantik — Redis' `*` überquert Punkte, AMQPs `*` trifft genau ein
Segment. Nur lokale Prüfung ergibt auf beiden Transports **dasselbe** Ergebnis, und identisches
Verhalten ist der Sinn der Übung. Der häufige wildcardfreie Fall zahlt diesen Preis nicht: er
abonniert seinen exakten Kanal und der Broker filtert.

Beide Transports benutzen dieselbe Matcher-Implementierung (`EventTopics` aus
`surf-eventbus-api`), die schon heute die Broker-Semantik nachbildet und unit-getestet ist.

### Provider-Mischbetrieb

**Gibt es nicht.** Zwei Provider in einer Flotte bedeuten zwei disjunkte Event-Universen — ein
Event aus dem Redis-Teil erreicht den Rabbit-Teil nie. Der Provider ist deshalb eine
Fleet-Entscheidung und gehört in die globale Konfiguration, nicht in die Plugin-Konfiguration.
Ein Prozess, dessen Provider von der globalen Vorgabe abweicht, protokolliert das beim Start
auf `WARNING` mit beiden Werten.

## Selbstzustellung

`@SurfSubscribe(includeSelf = false)` ist der Default: ein Handler sieht keine Events, die
sein eigener Prozess publiziert hat. Begründung: alle rund zwanzig bestehenden
`@OnRedisEvent`-Handler beginnen mit genau dieser Zeile —

```kotlin
if (event.originatesFromThisClient()) return
```

— und die häufigste Ursache einer Rückkopplungsschleife ist, sie zu vergessen. Der Default
kodifiziert die bestehende Praxis. `SurfBusEvent.originInstanceId` bleibt lesbar, wer
Selbstzustellung will, setzt `includeSelf = true`.

**`includeSelf` gilt nur für `BROADCAST`.** In Kombination mit `SHARED` wird es bei der
Registrierung abgelehnt. Grund: eine `SHARED`-Nachricht wurde aus einer geteilten Queue
entnommen. Sie lokal zu verwerfen hieße, sie zu quittieren, ohne sie zu verarbeiten — sie ist
dann für die gesamte Flotte verloren, nicht bloß für diese Instanz.

## Dispatch

Ein Weg für beide Transports, in `surf-eventbus-common`:

- **Hidden-Class-Invoker** über `InvokerFactory` aus `surf-api-core` — der Weg, den surf-redis
  heute für Events geht und surf-rabbitmq für Request-Handler. surf-rabbitmqs
  `EventDispatcher` dispatcht Events heute per `Method.invoke`; er wird auf den Invoker-Pfad
  gezogen.
- **Typhierarchie-bewusst**, wie heute bei RabbitMQ: ein Handler für `FactionEvent` erhält
  auch `FactionDisbandedEvent`. surf-redis' Beschränkung auf exakte Typen fällt weg — sie
  macht `@SurfSubscribe(topic = "faction.#")` mit einem Basistyp-Parameter erst benutzbar.
- **Alle passenden Abonnements laufen.** Ein Handler auf `faction.disbanded` und einer auf
  `faction.#` feuern beide.
- **Ein scheiternder Handler stoppt seine Nachbarn nicht.** Der erste Fehler wird nach dem
  Durchlauf aller Handler geworfen, damit der Transport über Retry entscheiden kann. Er reist
  in einer `EventHandlingFailure`, die die Retry-Absicht mitführt: der Bus kennt die
  `retry`-Flags der Abonnements, der Transport kennt die Wiederzustellung. Ein einziges
  nicht-idempotentes Abonnement unter den Treffern hebt Retry für **alle** auf — sie kommen als
  eine Nachricht, und den idempotenten Handler neben dem nicht-idempotenten erneut laufen zu
  lassen ist keine Option.
- **Unbekannter `type`** — kein Handler im Prozess registriert die Klasse: einmal warnen,
  quittieren, verwerfen. Nicht requeuen; eine durable `SHARED`-Queue würde die Nachricht sonst
  endlos erneut zustellen. Die Warnung ist die einzige Spur einer veralteten Binding-Leiche und
  bleibt deshalb laut.

Validiert wird bei der Registrierung, nicht bei der Zustellung: Parameterzahl, Parametertyp,
Muster-Syntax, Modus/Capability, `includeSelf`/Modus-Kombination. Ein fehlerhaftes Muster
bindet auf dem Broker beschwerdefrei und trifft dann nichts — das einzige Symptom wäre ein
Event, das nie ankommt.

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
was `SurfBusEvent` erweitert; positive und negative Ergebnisse werden gecacht, damit ein Strom
unbekannter Typen nicht zu einem Strom fehlschlagender Klassensuchen wird. Bleibt der Typ
unauflösbar, greift der dokumentierte Weg: einmal warnen, quittieren, verwerfen.

## Lebenszyklus

Ein einziges Modell für beide Transports, `suspend`, in `surf-eventbus-common` beschrieben:

```
build() → registerListener()* → freeze() → connect() → … → disconnect()
```

`freeze()` prüft Capabilities und schließt die Registrierung — Handler müssen bekannt sein,
bevor der Consumer startet, sonst trifft eine Nachricht einen halb registrierten Handler.

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

Neu:

| Schlüssel | Env | Default |
|---|---|---|
| `eventbus.provider` | `SURF_EVENTBUS_PROVIDER` | der einzige auf dem Classpath gefundene Transport |

Liegen beide Transport-Module auf dem Classpath und ist nichts konfiguriert, scheitert der
Start und nennt beide gefundenen Provider. Raten wäre hier falsch: die Wahl entscheidet über
die Zustellgarantie.

Die Redis-Verbindungsdaten behalten `RedisCredentialsProvider`, die RabbitMQ-Verbindungsdaten
ihre `SURF_RABBITMQ_*`-Variablen; beide unverändert.

## Was transportgebunden bleibt

Bewusst **nicht** vereinheitlicht, mit Begründung:

| Bereich | Bleibt bei | Grund |
|---|---|---|
| RPC (`rpc<T>()`, `@RpcService`, KSP-Proxies) | RabbitMQ | Zustellung an genau eine Instanz, garantiert und persistent. Redis broadcastet Requests und die erste Antwort gewinnt — eine gemeinsame Signatur verbärge das |
| Fire-and-Forget (`send()`, `InstanceTarget`) | RabbitMQ | Braucht eine durable Queue pro Ziel |
| Request/Response (`RedisRequest`, `@HandleRedisRequest`) | Redis | Andere Semantik als RPC: Broadcast, erste Antwort gewinnt. Bleibt für die bestehenden Consumer erhalten |
| Sync-Strukturen, Caches, Lua | Redis | Verteilte Datenstrukturen, kein Messaging |
| Retry-Leiter, DLQ, Unroutable-Audit | RabbitMQ | Setzen Broker-Queues voraus |
| Circuit Breaker | transportneutral | Schon heute ein eigenes Modul ohne Transport-Abhängigkeit |

## Build, Shading, Koordinaten

- **Gruppe** für alle Module: `dev.slne.surf.eventbus`. **Version** 2.0.0.
- **`rootProject.name`** wird `surf-eventbus`. Das GitHub-Repository umbenennen ist ein
  manueller Schritt außerhalb dieses Plans.
- **Netty** konvergiert auf 4.2.16 (der höhere der beiden Pins, von RabbitMQ vorgegeben);
  Redisson läuft gegen dieselbe Version. Eine Relocation-Basis:
  `dev.slne.surf.eventbus.shaded.io.netty`. Der `META-INF/native`-Mangling-Block existiert
  danach einmal.
- **Ein Plattform-Plugin pro Plattform** statt zwei. Es enthält beide Transports geshadet.
  Der Preis ist ein größeres Jar; der Gegenwert ist ein Plugin statt zwei auf jedem Server und
  eine Netty-Kopie statt zwei. Verbindungen werden weiterhin faul aufgebaut: ein Prozess, der
  `RedisApi` nie anfasst, erzeugt keinen Redisson-Client.
- **ABI-Dumps** (`api/*.api`) beider Projekte werden neu erzeugt; die Binary-Compatibility-
  Validierung bleibt für beide aktiv.
- **`IoUringRedissonPatcher`** zieht unverändert mit um, inklusive seiner Netty-Annahmen.

## Migration

Harter Schnitt, ein gemeinsames Deployment. Wire-Format und API der Events ändern sich;
gemischter Betrieb alt/neu funktioniert nicht.

### Entfällt

| Typ | Ersatz |
|---|---|
| `dev.slne.surf.redis.event.RedisEvent` | `dev.slne.surf.eventbus.event.SurfBusEvent` |
| `@OnRedisEvent` | `@SurfSubscribe(mode = BROADCAST, includeSelf = ...)` |
| `RedisEventBus`, `RedisApi.publishEvent()`, `RedisApi.subscribeToEvents()` | `SurfEventBus.publish()`, `SurfEventBus.registerListener()` |
| `RedisEvent.originatesFromThisClient()` | Default `includeSelf = false`; sonst `SurfBusEvent.originInstanceId` |
| `RedisEvent.timestamp` | `SurfBusEvent.publishedAtEpochMs` |
| `RabbitEventPacket`, `@RabbitEvent`, `@RabbitSubscribe` | `SurfBusEvent`, `@BusEvent`, `@SurfSubscribe` |
| `SurfRabbitApi.publish()`, `SurfRabbitApi.registerListener()` | `SurfEventBus` |

`@RabbitSubscribe` hat **keine** externen Consumer — die Event-API des Redesigns ist noch nicht
in Benutzung. Der Migrationsaufwand liegt vollständig auf der Redis-Seite.

### Betroffene Repositories

Event-Listener (`@OnRedisEvent`, rund zwanzig Dateien): `surf-core`, `surf-transaction`,
`surf-punish-redis`, `surf-tab`, `surf-friends`, `surf-chat`, `surf-maintenance`.

Dazu `surf-core`s handgeschriebener verteilter Bus (`SurfEventBus.fire()`,
`SurfEventFireRedisEvent`, `LocalSurfEventBusListener`), der durch den echten Bus ersetzt wird.

Nur Koordinaten-Wechsel, kein Code: alle Consumer von RPC, `RedisRequest`, Sync-Strukturen und
Caches — Packages bleiben identisch.

**Diese Änderungen liegen in fremden Repositories und sind nicht Teil dieses Plans.**
Lieferbestandteil hier ist ein Migrationsleitfaden mit Vorher/Nachher pro Muster sowie die
Liste der betroffenen Dateien.

## Verifikation

RabbitMQ bringt eine Testsuite mit Testcontainers mit; surf-redis hat **keinen einzigen Test**.
Das ist beim Zusammenführen kein Nebenaspekt: ohne Redis-Tests ist die Behauptung
„beide Provider verhalten sich gleich" unbelegt.

### Provider-Parity-Suite

Der Kern der Absicherung: **eine** Testsuite, parametrisiert über beide Transports, gegen
echte Broker in Testcontainers.

| # | Test | Rabbit | Redis |
|---|---|---|---|
| 1 | `BROADCAST`, drei Instanzen, ein Event | alle drei | alle drei |
| 2 | Wildcardfreies Topic trifft nur passende Abonnenten | ja | ja |
| 3 | `*` trifft genau ein Segment | ja | ja |
| 4 | `#` trifft null oder mehr Segmente | ja | ja |
| 5 | Zwei überlappende Muster, beide Handler feuern | ja | ja |
| 6 | Typhierarchie: Handler auf Basistyp erhält Subtyp | ja | ja |
| 7 | `includeSelf = false` unterdrückt das eigene Event | ja | ja |
| 8 | `includeSelf = true` liefert es | ja | ja |
| 9 | Ein scheiternder Handler blockiert seine Nachbarn nicht | ja | ja |
| 10 | Ein Event ohne Abonnenten ist kein Fehler | ja | ja |

Test 9 der ursprünglichen Fassung — „unbekannter `type` wird verworfen, nicht requeued" —
lässt sich nicht über die Bus-API stellen: die Typauflösung findet jede Klasse, die im selben
JVM auf dem Classpath liegt. Er wird deshalb je Transport mit dem Rohclient gestellt: eine
Nachricht mit erfundenem Typnamen wird direkt auf Exchange beziehungsweise Kanal publiziert,
und geprüft wird zweierlei — sie wird nicht zugestellt, und der Consumer lebt danach weiter.
Das ist die Lage nach einem Deploy, in dem ein Dienst einen Event-Typ gelöscht hat, dessen
durables Binding noch steht.

Transportspezifisches Verhalten und Fehlermeldungen, ebenfalls getestet:

| # | Test | Erwartung |
|---|---|---|
| 11 | `SHARED` unter Redis | `freeze()` scheitert mit Handler-, Modus- und Provider-Nennung |
| 12 | `SHARED` unter Rabbit, drei Instanzen | genau eine verarbeitet |
| 13 | `SHARED` unter Rabbit, alle Instanzen offline, dann Neustart | Event überlebt |
| 14 | `BROADCAST`, alle Instanzen offline | Event verfällt, auf beiden Transports |
| 15 | `retry = true` unter Redis | Startwarnung nennt die betroffenen Handler |
| 16 | Handler-Fehler unter Rabbit | volle Retry-Leiter, danach DLQ |
| 17 | `includeSelf` mit `SHARED` | Ablehnung bei der Registrierung |
| 18 | Beide Transports auf dem Classpath, kein `eventbus.provider` | Start scheitert, nennt beide |

### Unit-Tests, ohne Broker

- `EventTopics`: Muster-Validierung und Matching (bestehend, wandert mit)
- Registry: Parameterzahl, Parametertyp, Modus/Capability, `includeSelf`/Modus
- Capability-Validierung: Fehlermeldungstexte, weil sie Teil der API sind
- Provider-Auswahl über alle Kombinationen von Classpath-Inhalt und Konfiguration
- Config-Layering `env > Plugin-YAML > globale YAML > Default`
- Envelope-Serialisierung, CBOR und JSON, Rundlauf
- `surf-eventbus-common` referenziert weder RabbitMQ- noch Redis-Typen (Nachfolger von
  `SharedPackagePurityTest`, erweitert um Redisson)
- `surf-circuitbreaker` bleibt frei von Transport-Typen (bestehend)

### Bestehende Tests

Die RabbitMQ-Suite (Topologie, RPC, Competing Consumers, Retry, Chunking, Broker-Neustart,
Queue-Overflow, Unroutable, Failure-Szenarien) läuft unverändert weiter. Die Event-Tests
(`EventDeliveryTest`, `EventSubscriptionRegistryTest`, `EventTopicsTest`) werden auf den Bus
umgeschrieben und gehen in die Parity-Suite ein.

### Bekannte Einschränkung

Auf dieser Maschine ist kein Docker-Daemon erreichbar. Integrationstests werden vollständig
erstellt; ihre Ausführung erfordert einen laufenden Daemon. Solange ungeprüft, wird der Status
als „nicht verifiziert" berichtet, nicht als bestanden.

## Umsetzung in Etappen

Jede Etappe endet übersetzbar mit lauffähigen Tests.

| # | Etappe | Inhalt |
|---|---|---|
| 1 | `surf-eventbus-common` | Fundament zusammenführen: Dispatch, Serializer-Caches, Config-Layering, Plattform-Proxies. Purity-Test erweitert. Reines Verschieben, kein Verhaltenswechsel |
| 2 | surf-redis absorbieren | Module hereinziehen, Packages unverändert, Netty konvergieren, Shading und ABI-Dumps zusammenführen. Grüner Build, unverändertes Verhalten |
| 3 | `surf-eventbus-api` | Typen, Annotationen, `EventTopics`, `TransportCapability`, SPI. Muster-Validierung und Matching unit-getestet |
| 4 | `surf-eventbus-core` | Registry, Dispatcher, Envelope, Provider-Auswahl, Capability-Validierung. Vollständige broker-freie Unit-Abdeckung dieser vier Bausteine |
| 5 | Rabbit-Transport | `surf.events`-Topologie hinter `EventTransport`, Event-API aus `surf-rabbitmq-api` entfernen. Bestehende Event-Tests auf den Bus umschreiben |
| 6 | Redis-Transport | Kanäle, exaktes und Muster-Abonnement, Capability-Deklaration. Testcontainers-Redis neu aufgesetzt |
| 7 | Parity-Suite | Tests 1–18 parametrisiert über beide Transports |
| 8 | Redis-Event-API entfernen | `RedisEvent`, `@OnRedisEvent`, `RedisEventBus` löschen; `RedisApi.connect()` auf `suspend` |
| 9 | Plattform-Module vereinen | Ein Paper-, ein Velocity-Plugin. Standalone-Instanz für Microservices auf beiden Transports |
| 10 | Dokumentation | README zusammenführen, Migrationsleitfaden, Koordinaten- und Versionswechsel |

## Risiken

| Risiko | Umgang |
|---|---|
| Ein Jar mit beiden Transports wird groß und die Shading-Bäume kollidieren (Netty, Reactor, Kryo) | Etappe 2 führt die Zusammenführung isoliert und vor jeder Event-Arbeit durch, mit grünem Build als Abschlusskriterium |
| Redis hat heute keine Tests; unbekanntes Verhalten kann beim Verschieben brechen | Etappe 2 verschiebt ausschließlich, ohne Änderung. Verhaltensabsicherung entsteht in Etappe 6/7 |
| `RedisApi.connect()` von blockierend auf `suspend` bricht Aufrufer | Teil des harten Schnitts, im Migrationsleitfaden geführt |
| Die Parity-Suite braucht zwei Container-Typen; Laufzeit der CI steigt | Broker-freie Unit-Tests bleiben die schnelle Schleife; Parity-Tests laufen nur mit verfügbarem Docker |
| Ohne Docker bleibt ein Teil der Absicherung unausgeführt | Wird als „nicht verifiziert" berichtet, nicht als bestanden |

## Nicht Bestandteil

- **`SHARED` über Redis Streams.** Consumer-Groups, Pending-Entry-Verwaltung, `XAUTOCLAIM`,
  Trimming und eine Stream-DLQ wären ein größeres Subsystem als der übrige Event-Bus. Das
  Capability-Modell hält die Tür offen: eine spätere Implementierung erweitert die
  Capability-Menge des Redis-Transports, ohne die API zu ändern.
- **Vereinheitlichung von Request/Response.** RPC bleibt rabbit-, `RedisRequest` redis-seitig.
- **Änderungen in den Consumer-Repositories.** Eigene Vorhaben; hier entsteht der Leitfaden.
- **Umbenennung des GitHub-Repositories** und Abschaltung von surf-redis. Manuelle Schritte.
- **Wire-Kompatibilität** zu surf-rabbitmq 1.6.x oder surf-redis 1.5.x.
