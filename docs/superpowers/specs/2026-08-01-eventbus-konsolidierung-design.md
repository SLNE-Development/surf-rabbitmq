# surf-eventbus Konsolidierung — Design

**Datum:** 2026-08-01
**Vorgänger:** `2026-07-29-surf-eventbus-design.md`, Pläne 1–4

## Warum

Die vier Pläne haben surf-rabbitmq und surf-redis zu einem Projekt zusammengeführt. Das Ergebnis
kompiliert (`compileKotlin compileTestKotlin` grün), aber die Naht ist überall sichtbar: zwei
parallele Vertragsmodelle für dasselbe Konzept, zwei KSP-Prozessoren, drei
`@RequiresOptIn`-Marker, zwei Konfigurationsstile, und Pakete, deren Namen aus der alten
Modulstruktur stammen statt aus der neuen.

Dieses Dokument beschreibt, wie die Fläche zu **einer** API wird, und welche Reste aus Plan 4
dabei mit erledigt werden.

## Ausgangslage

Zwei Module tragen alles:

| Modul | Inhalt |
|---|---|
| `surf-eventbus-api` | Bus-API, RabbitMQ-API, Redis-API, Audit-Vertrag, Circuit Breaker |
| `surf-eventbus-core` | Bus-Implementierung, RabbitMQ-Transport, Redis-Transport |

Dazu `surf-eventbus-ksp` und drei Plattform-Module. `surf-eventbus-test` wurde am 2026-08-01
gelöscht: seine drei Module zeigten auf die veraltete `RabbitModule`-Enumeration und auf ein
Plugin `surf-rabbitmq-paper`, das es nicht mehr gibt, und `settings.gradle.kts` schloss sie auf
CI aus — die Unit- und Docker-Tests in `surf-eventbus-core` decken dasselbe ab und laufen
tatsächlich.

## Was gebaut wird

### 1. Ein Vertragsmodell für `@QueryService` und `@RpcService`

Heute existiert jede Idee zweimal:

| Query | RPC |
|---|---|
| `QueryServiceDescriptor` | `RabbitRpcServiceDescriptor` |
| `QueryCallable` | `RabbitRpcCallable` |
| `QueryParameter` | `RabbitRpcParameter` |
| `QueryInvoker` | `RabbitRpcInvoker` |
| `QueryCallableDefault`, `QueryParameterDefault` | `RabbitRpcCallableDefault`, `RabbitRpcParameterDefault` |
| `QueryParametersSerializer` | `CallableParametersSerializer` |
| `QuerySerializerCache` | `RpcSerializerCache` |
| — (roher `KType`) | `RabbitRpcType`, `RabbitRpcTypeDefault`, `RabbitRpcTypeKrpc` |

`QuerySerializerCache` und `RpcSerializerCache` sind dieselbe Klasse mit einem anderen
Typparameter. `QueryParametersSerializer` und `CallableParametersSerializer` unterscheiden sich
in sechs Zeilen.

Ein neues Paket `dev.slne.surf.eventbus.service` hält, was beide teilen:

```kotlin
package dev.slne.surf.eventbus.service

@InternalEventBusApi
interface ServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, ServiceCallable<Service>>

    fun getCallable(name: String): ServiceCallable<Service>?
}

@InternalEventBusApi
interface ServiceCallable<Service : Any> {
    val name: String
    val returnType: ServiceType
    val invoker: ServiceInvoker<Service>
    val parameters: Array<out ServiceParameter>

    /** Ob dieser Aufruf `@FireAndForget` ist: keine Antwort wird gesendet oder erwartet. */
    val fireAndForget: Boolean
}

@InternalEventBusApi
interface ServiceParameter {
    val name: String
    val type: ServiceType
    val isOptional: Boolean

    /** Annotationen mit Ziel [AnnotationTarget.VALUE_PARAMETER]. */
    val annotations: List<Annotation>
}

@InternalEventBusApi
interface ServiceType {
    val kType: KType

    /** Annotationen mit Ziel [AnnotationTarget.TYPE]. */
    val annotations: List<Annotation>
}

@InternalEventBusApi
fun interface ServiceInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
```

Jeder Transport erbt und ergänzt genau das, worin er sich unterscheidet — die Art, eine
Client-Instanz zu bauen:

```kotlin
interface QueryServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    val timeoutMillis: Long
    fun createInstance(instanceId: String, json: Json, transport: QueryTransport): Service
}

interface RpcServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    /** Der Dienst aus `@RpcService(service = ...)`, oder leer, wenn `rpc(...)` ihn verlangt. */
    val defaultService: String
    fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service
}
```

`ServiceType` trägt `kType` **und** Annotationen — die Form der RPC-Seite. Der Query-Pfad
benutzt heute den rohen `KType` und verliert dadurch `@Contextual` und
`@Serializable(with = ...)` an Parametern; mit `ServiceType` bekommt er beides.

`ParametersSerializer` und `ServiceSerializerCache` ersetzen die je zwei Klassen. Die
RPC-Varianten gewinnen, weil sie Obermengen sind: optionale Parameter und kontextuelle
Serializer-Auflösung.

`KotlinSerializerNameCache` bleibt eigenständig — es sucht nach Klassenname für den
Event-Codec-Pfad und ist keine Dublette, sondern ein anderer Zugriff.

**Für Nutzer ändert sich nichts an den Annotationen.** `@QueryService` und `@RpcService` bleiben
getrennt: sie geben verschiedene Zusagen und haben verschiedene Validierungsregeln.

### 2. Ein KSP-Prozessor

Heute laufen zwei Prozessoren nebeneinander, mit je eigenem `Names`, `ClassNames`,
`MemberNames`, `Types.anyNullableArray`, eigener Modellfabrik und eigenem Descriptor-Codegen —
1616 Zeilen zusammen.

```
dev.slne.surf.eventbus.ksp
  ServiceProcessor              ein Lauf über beide Annotationen
  ServiceProcessorProvider
  model/
    ServiceModel, ServiceFunctionModel, ServiceParameterModel
    ServiceModelFactory         gemeinsames Einlesen und Validieren
    QueryRules                  suspend, nullable, kein @FireAndForget
    RpcRules                    suspend, @FireAndForget erlaubt
  codegen/
    Names, ClassNames, MemberNames, Types
    CallableCodegen, ParameterCodegen, TypeCodegen, InvokerCodegen
    QueryDescriptorCodegen, QueryClientCodegen
    RpcDescriptorCodegen, RpcClientCodegen
```

Die `META-INF/services`-Datei nennt danach **einen** Provider statt zwei.

### 3. Pakete

Der `.api`-Infix verschwindet, damit RabbitMQ aussieht wie Redis. `common` als Sammelbegriff
verschwindet, weil er nichts benennt. `internal` unter `api` in einem `-api`-Modul verschwindet,
weil `@InternalEventBusApi` diese Aussage bereits trägt.

**`surf-eventbus-api`:**

| Heute | Danach |
|---|---|
| `rabbitmq/api/**` | `rabbitmq/**` |
| `rabbitmq/api/internal/config/**` | `rabbitmq/config/**` |
| `rabbitmq/api/internal/RabbitMQInstance.kt` | entfällt (siehe 5) |
| `rabbitmq/api/rpc/{callable,descriptor,invoker,type}/**` | `service/**` (geteilt) + `rabbitmq/rpc/**` |
| `core/envelope/EventEnvelope.kt` | `transport/EventEnvelope.kt` |
| `common/circuitbreaker/**` | `circuitbreaker/**` |
| `common/serialization/**` | `serialization/**` |
| `common/platform/**` | `platform/**` |
| `common/config/LegacyEnvironmentGuard.kt` | `config/LegacyEnvironmentGuard.kt` |
| `redis/**` | unverändert |

**`surf-eventbus-core`:** vier Geschwisternamen aus der alten Modulstruktur —
`rabbitmq/common/`, `rabbitmq/core/`, `rabbitmq/shared/`, `rabbitmq/rpc/` — werden zu flachen
Sachpaketen:

```
core/          SurfEventBusImpl, SurfEventBusBuilderImpl, SurfEventBusFactoryImpl,
               RedisTransportLocator, RedisTransportProvider
core/{audit,dispatch,query,registry}/
rabbitmq/{audit,connection,consumer,health,packet,publisher,retry,rpc,
          serialization,topology}/
redis/{bus,cache,config,credentials,sync,util}/
```

Dateinamen folgen der Kotlin-Konvention: `byte-buf-extensions.kt` → `ByteBufExtensions.kt`,
`codec-extension.kt` → `CodecExtensions.kt`, `redis/util/util.kt` → `RedisUtils.kt`,
`exception/{api,connection,packet,protocol,request,serialization}.kt` →
`{Api,Connection,Packet,Protocol,Request,Serialization}Exceptions.kt`.

Der Umbau ist ABI-brechend. Version 2.0 ist ohnehin nicht kompatibel zu 1.6.x, deshalb kostet er
nichts, was nicht schon bezahlt ist — aber die ABI-Dumps werden neu erzeugt und der
Rollout-Hinweis nennt ihn.

### 4. Ein Marker statt drei

| Heute | Aussage |
|---|---|
| `InternalEventBusApi` | ERROR, drei Ziele, ohne `@InternalAPIMarker` |
| `InternalRabbitMQ` | Standard-Level, sechs Ziele, mit `@InternalAPIMarker` |
| `InternalRedisAPI` | ERROR, kein `@Target`, Text nennt „surf-redis" |

Danach: **`@InternalEventBusApi`**, ERROR, `@InternalAPIMarker`, alle sechs Ziele. Der
`abiValidation`-Filter in `surf-eventbus-api/build.gradle.kts` und die drei `optIn`-Zeilen im
Root-Build nennen danach eine Annotation.

### 5. Symmetrie zwischen den Transporten

Was ein Transport hat und der andere nicht, wird entweder für beide gebaut oder für keinen.

**Konfiguration.** `GlobalRabbitMQConfig` (223 Zeilen) und `PluginRabbitMQConfig` (201 Zeilen)
sind dieselben vierzehn Felder mit derselben KDoc, zweimal; sie unterscheiden sich nur darin,
dass die eine `String`/`Int` benutzt und die andere `StringOrDefault`/`IntOr.Default`. Danach
gibt es **eine** `RabbitMQConfig` mit durchgehenden Sentinel-Typen und zwei Companions für die
beiden YAML-Orte. Die Standardwerte leben an einer Stelle.

`CommonRabbitMQConfig` bekommt Kotlin-Properties statt `getHost()`/`isPersistRequests()`, damit
sie sich liest wie `RedisConfig`.

Redis bekommt die vierte Schicht wirklich: `redisConfig` ruft heute
`resolveRedisConfig(global = …, plugin = null)` — die Plugin-Schicht ist verdrahtet, aber nie
gefüllt.

`SURF_EVENTBUS_AUDIT_SERVICE` hängt heute an der RabbitMQ-Konfiguration, obwohl das Audit eine
Bus-Angelegenheit ist. Es zieht in eine Bus-Konfigurationsschicht.

**Credentials.** `RedisCredentialsProvider` ist eine `ServiceLoader`-Naht mit genau einer
Implementierung; RabbitMQ baut seine Zugangsdaten direkt aus der Konfiguration. Danach liegen
beide unter `credentials/` mit gemeinsamem Obertyp, beide `@AutoService`, beide aus der
Konfiguration vorbelegt — die Naht existiert, damit ein Betreiber einen Secret-Store
einhängen kann, und das gilt für beide Transporte oder für keinen.

Dabei wird ein Fehler behoben: `RedisCredentialsProviderImpl` baut `redis://<passwort>@host`.
In einer URI steht vor dem `@` ohne Doppelpunkt der **Benutzername**, nicht das Passwort.
Richtig ist `redis://:<passwort>@host`.

**Instanzen.** `RabbitMQInstance` ist ein Interface im api-Modul mit einem `dataPath`.
`RedisInstance` ist eine abstrakte Klasse im core-Modul, die nebenbei eine Netty-Eventloop, zwei
Scheduler und einen Executor aufbaut. Beide beschreiben dasselbe: „die Plattform, in der dieser
Prozess läuft".

Danach: **eine** SPI `dev.slne.surf.eventbus.platform.EventBusInstance` im api-Modul mit
`dataPath`, `getResourceAsStream` und `tryExtractPluginName`. Die Redis-Laufzeitobjekte ziehen
in ein `RedisRuntime` im core-Modul, wo Verhalten hingehört. Ein Plattform-Modul implementiert
danach **ein** Interface statt zwei — genau der Grund, warum Paper und Velocity heute eine
Rabbit-Instanz haben und keine Redis-Instanz.

`StandaloneLifecycleHook` ist heute Rabbit-only und zieht auf die Bus-Ebene.

**Ausnahmen.** RabbitMQ hat eine Hierarchie unter `SurfRabbitException`, Redis hat ein einzelnes
`RedisCodecException`, der Bus hat nichts. Danach: `SurfEventBusException` als Wurzel im
api-Modul, `SurfRabbitException` und ein neues `SurfRedisException` darunter.

**Testinfrastruktur.** `RequiresDocker` existiert zweimal, wortgleich, in
`rabbitmq/common/testing` und `redis/testing`. Danach einmal.

### 6. Hacks

**Handgeschriebene `META-INF/services`.** `FakeStandaloneLifecycleHook` ist von Hand
registriert, weil `@AutoService` neben `surfEventbusKsp` auf derselben `kspTest`-Aufgabe einen
KSP2-Fehler auslöst (`KaInvalidLifetimeOwnerAccessException`). Die Ursache wird angegangen statt
umgangen: die Tests brauchen den `ServiceLoader` gar nicht. `SurfEventBusBuilder` hat mit
`withRedis(event, query)` bereits eine Testnaht; der Standalone-Hook und die Plattform-Instanz
bekommen dieselbe, und die Doubles werden übergeben statt gefunden. Lässt sich der KSP2-Fehler
danach nicht vermeiden, bleibt die Datei — aber der Kommentar nennt dann eine Reproduktion und
eine Entscheidung, nicht ein Achselzucken.

**`FakeRedisInstance` wird gelöscht.** Die Klasse wird nirgends referenziert, und ihre KDoc
beschreibt eine Registrierung in `META-INF/services`, die es nicht gibt.

**`AggregateCompletenessTest` wird gelöscht.** Er wurde geschrieben, um zu beweisen, dass ein
Aggregat-Modul seine Geschwistermodule weiterreicht. Bei einem Modul prüft er, dass Klassen in
ihrem eigenen Modul liegen, und kann nicht fehlschlagen. An seine Stelle tritt ein Test, der
etwas aussagt: **keine mit `@InternalEventBusApi` markierte Deklaration steht im ABI-Dump**.

**`PackageNamingTest` und `RelocationBaseTest`** laufen über `Path.of("..")` und hängen damit
daran, dass das Arbeitsverzeichnis ein Modulordner ist. Sie suchen die Wurzel danach an einer
Markierungsdatei.

**Zwei offene `TODO`s** werden aufgelöst: die Behandlung von
`SurfRabbitProtocolVersionMismatchException` in `RabbitListenerHandlerManager` und der Hinweis
zur Audit-Queue in `RabbitConnectionImpl`.

**`AuditReports.kt`** aus Plan 4 Task 1 Step 9 fehlt: die fünf Meldepfade wurden ohne den
gemeinsamen Helfer verdrahtet. Er entsteht nach.

**`.github/workflows/publish.yml`** nennt `surf-rabbitmq-velocity-*-all.jar` und
`surf-rabbitmq-paper-*-all.jar` — Artefaktnamen, die es nicht mehr gibt. Der Workflow lädt
heute nichts hoch, ohne dass es auffällt.

### 7. Reste aus Plan 4

| Plan-4-Task | Stand | Hier |
|---|---|---|
| 1 Audit-Vertrag und Meldeweg | fertig | nur `AuditReports.kt` fehlt |
| 2 Audit-Microservice | **blockiert** | wird als blockiert weitergetragen, mit einem Schritt, der die Blockade neu prüft |
| 3 Aggregat-Module | abgewichen | die Module wurden physisch verschmolzen statt aggregiert; die Entscheidung bleibt, der Test dazu wird ersetzt |
| 4 Plattform-Module vereinen | **offen** | vollständig |
| 5 Container-Suiten | **offen** | vollständig, in `surf-eventbus-core/src/test` |
| 6 Dokumentation | **offen** | vollständig |

**Task 2** bleibt blockiert: `surf-database-r2dbc` (2.3.0 und 2.3.1) shaded seine
Exposed-Abhängigkeiten, ohne die eingebetteten Kotlin-`@Metadata`-Annotationen mitzuziehen;
`suspendTransaction`, `insert`, `select` und `deleteWhere` sind aus Kotlin-Quellcode nicht
auflösbar. Der Meldeweg funktioniert bereits — die Meldungen haben nur noch kein Ziel.

**Task 4** ist die sichtbarste Lücke: Paper und Velocity liefern je eine
`*RabbitMqInstance` und **keine** `RedisInstance`. Auf beiden Plattformen ist der Redis-Transport
damit nicht benutzbar, obwohl `SurfEventBus.publish` und `query` ihn brauchen.
`StandaloneRabbitMqInstance` liegt außerdem im core-Modul statt im Standalone-Plattform-Modul.
Nach der Vereinheitlichung aus Abschnitt 5 implementiert jedes Plattform-Modul eine
`EventBusInstance` und beide Transporte laufen überall.

**Task 5** weicht bewusst vom ursprünglichen Ort ab: Plan 4 wollte die Suiten in
`surf-eventbus-test`, das am 2026-08-01 gelöscht wurde. Sie entstehen in
`surf-eventbus-core/src/test`, neben `RabbitBrokerExtension`, das dort bereits liegt und auf CI
gebaut wird.

**Task 6** ersetzt eine README, die noch `surf-rabbitmq` heißt und `SURF_RABBITMQ_*` als
Umgebungsvariablen dokumentiert, obwohl sie seit Plan 1 `SURF_EVENTBUS_RABBITMQ_*` heißen.

## Reihenfolge

Die Abhängigkeiten der Umbauten legen sie fest:

1. **Marker vereinheitlichen** — berührt jede Datei, die eine der drei Annotationen nennt, und
   sollte deshalb vor den Umbenennungen passieren, nicht danach.
2. **Vertragsmodell vereinheitlichen** — die größte inhaltliche Änderung, und sie muss vor dem
   KSP-Umbau kommen, weil der Prozessor auf die Typen zeigt.
3. **KSP-Prozessor zusammenlegen.**
4. **Pakete und Dateinamen umbenennen** — mechanisch, aber breit; nach den inhaltlichen
   Änderungen, damit nicht dieselbe Datei zweimal umzieht.
5. **Symmetrie herstellen** — Konfiguration, Credentials, Instanzen, Ausnahmen.
6. **Hacks auflösen.**
7. **Plattform-Module vereinen** (Plan-4 Task 4) — braucht die `EventBusInstance` aus 5.
8. **Container-Suiten** (Plan-4 Task 5).
9. **Dokumentation und ABI** (Plan-4 Task 6).

## Wie geprüft wird

Jeder Schritt endet grün oder gar nicht:

- `./gradlew compileKotlin compileTestKotlin` nach jedem Umbenennungsschritt.
- `./gradlew test` nach jedem inhaltlichen Schritt. Docker-Tests werden übersprungen — **auf
  dieser Maschine ist kein Docker-Daemon erreichbar**, und keine Behauptung in dieser Arbeit
  darf so tun, als seien sie gelaufen.
- `./gradlew :surf-eventbus-ksp:test` deckt den zusammengelegten Prozessor ab; die
  kotlin-compile-testing-Fälle laufen ohne Container.
- `./gradlew updateLegacyAbi` und `checkLegacyAbi` nach dem Paketumbau und am Ende.
- `./gradlew :surf-eventbus-platform:surf-eventbus-platform-paper:shadowJar`, danach die
  Netty-Zählung aus Plan 4 Task 4 Step 5: genau eine Kopie von `ByteBuf.class`.

Ein eigener Test hält den Zustand fest, den dieses Design herstellt: kein Paket heißt `common`,
kein Paket unter `rabbitmq` heißt `api`, und keine Datei trägt einen kebab-case-Namen.

## Bewusst nicht hier

- **Die Module wieder aufspalten.** Die physische Verschmelzung zu `-api` und `-core` bleibt.
  Sie weicht von Plan 4 Task 3 ab, ist aber die Struktur, die gewünscht ist.
- **`@QueryService` und `@RpcService` zu einer Annotation machen.** Sie geben verschiedene
  Zusagen; ein gemeinsamer Name würde das verstecken.
- **Der Audit-Microservice.** Blockiert, siehe oben.
- **Die Korrektur der `RabbitModule`-Enumeration in surf-microservice.** Fremdes Repository.
- **Die Umbenennung des GitHub-Repositories** von `surf-rabbitmq` auf `surf-eventbus`.
- **Der Umbau der Consumer.**

## Risiken

| Risiko | Umgang |
|---|---|
| Der Paketumbau ist breit und kann still etwas übersehen | ein Test verbietet die alten Paketnamen; ABI-Dump wird neu erzeugt und gelesen |
| Das vereinheitlichte Vertragsmodell ändert generierten Code | die KSP-Tests laufen vor und nach dem Umbau; ein Round-Trip-Test über einen echten Proxy bleibt bestehen |
| Der KSP2-Fehler lässt sich nicht auflösen | dann bleibt die handgeschriebene Datei, aber begründet und reproduzierbar dokumentiert |
| Docker fehlt auf dieser Maschine | Container-Tests werden geschrieben, nicht ausgeführt, und als „nicht verifiziert" gemeldet |
| `surf-database-r2dbc` bleibt kaputt | der Audit-Microservice bleibt blockiert; der Meldeweg funktioniert bereits ohne ihn |
