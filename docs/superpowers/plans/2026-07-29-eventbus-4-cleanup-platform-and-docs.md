# surf-eventbus Plan 4: Aufräumen, Plattform, Dokumentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ein Repository, ein Plugin pro Plattform, eine Event-API, ein README und ein
Migrationsleitfaden — surf-redis kann abgeschaltet werden.

**Architecture:** Der Provider kommt aus der Konfiguration, aufgelöst über dieselbe
vierstufige Kette wie alles andere. Die alte Redis-Event-API entfällt vollständig. Die vier
Plattform-Module werden zu zwei: ein Paper-Plugin und ein Velocity-Plugin, die beide
Transport-APIs bereitstellen und nur die konfigurierte Verbindung aufbauen.

**Tech Stack:** Gradle mit `dev.slne.surf.api.gradle.paper-plugin` und `…velocity`, Paper- und
Velocity-Plugin-Bootstrap, YAML-Konfiguration, Kotlin ABI Validation.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-surf-eventbus-design.md`
- Voraussetzung: Pläne 1–3 abgeschlossen
- Harter Schnitt: keine Deprecation-Schicht, kein Parallelbetrieb alter und neuer Event-API
- Der Provider ist eine Fleet-Entscheidung und gehört in die **globale** Konfiguration
- Ein Prozess, der eine Transport-API nie anfasst, baut deren Verbindung nicht auf
- Packages bleiben `dev.slne.surf.rabbitmq.*` und `dev.slne.surf.redis.*` — Consumer von RPC,
  `RedisRequest`, Sync-Strukturen und Caches ändern keine Import-Zeile
- Änderungen in fremden Repositories sind **nicht** Bestandteil. Lieferbestandteil ist der
  Leitfaden

---

### Task 1: Provider aus der Konfiguration

**Files:**
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/config/EventBusProviderConfig.kt`
- Create: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/config/EventBusProviderConfigTest.kt`
- Modify: `surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/SurfEventBusBuilderImpl.kt`

**Interfaces:**
- Consumes: nichts
- Produces: `object EventBusProviderConfig` mit
  `fun resolve(env: (String) -> String?, globalYaml: () -> String?, default: String? = null): String?`
  und `const val ENV_KEY = "SURF_EVENTBUS_PROVIDER"`, `const val YAML_KEY = "eventbus.provider"`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.common.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventBusProviderConfigTest {

    @Test
    fun `the environment wins over the yaml`() {
        assertEquals(
            "redis",
            EventBusProviderConfig.resolve(
                env = { if (it == EventBusProviderConfig.ENV_KEY) "redis" else null },
                globalYaml = { "rabbit" }
            )
        )
    }

    @Test
    fun `the yaml is used when the environment is silent`() {
        assertEquals(
            "rabbit",
            EventBusProviderConfig.resolve(env = { null }, globalYaml = { "rabbit" })
        )
    }

    @Test
    fun `a blank environment value is treated as absent`() {
        // An empty variable is what a container platform leaves behind for an unset secret;
        // treating it as a value would fail the start for no reason.
        assertEquals(
            "rabbit",
            EventBusProviderConfig.resolve(env = { "  " }, globalYaml = { "rabbit" })
        )
    }

    @Test
    fun `nothing configured resolves to null so the classpath can decide`() {
        assertNull(EventBusProviderConfig.resolve(env = { null }, globalYaml = { null }))
    }

    @Test
    fun `the value is trimmed`() {
        assertEquals(
            "redis",
            EventBusProviderConfig.resolve(env = { " redis " }, globalYaml = { null })
        )
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-common:test --tests '*EventBusProviderConfigTest*'`
Expected: FAIL — `Unresolved reference: EventBusProviderConfig`

- [ ] **Step 3: Implementieren**

```kotlin
package dev.slne.surf.eventbus.common.config

/**
 * Resolves which provider a process runs on.
 *
 * Follows the same precedence as every other setting in this project — environment above
 * YAML — and deliberately stops there: unlike a timeout, the provider has no sensible
 * built-in default, because guessing it would mean guessing the delivery guarantee. With
 * nothing configured this returns `null` and the single transport on the classpath decides;
 * with two, the start fails.
 *
 * It belongs in the **global** configuration rather than a per-plugin one: two providers in
 * one fleet are two disjoint event universes.
 */
object EventBusProviderConfig {

    const val ENV_KEY = "SURF_EVENTBUS_PROVIDER"
    const val YAML_KEY = "eventbus.provider"

    fun resolve(
        env: (String) -> String?,
        globalYaml: () -> String?,
        default: String? = null
    ): String? = sequenceOf(env(ENV_KEY), globalYaml(), default)
        .map { it?.trim() }
        .firstOrNull { !it.isNullOrEmpty() }
}
```

- [ ] **Step 4: Im Builder verwenden**

In `SurfEventBusBuilderImpl.build()` vor der Auswahl den Konfigurationswert ergänzen, falls
`configuredProvider` nicht gesetzt wurde:

```kotlin
        val configured = configuredProvider ?: EventBusProviderConfig.resolve(
            env = System::getenv,
            globalYaml = { null } // supplied by the platform layer via configuredProvider
        )
```

und `TransportSelector.select(available, provider, configured)` aufrufen. Der YAML-Zweig
bleibt der Plattformschicht überlassen, die als einzige weiß, wo die globale Datei liegt —
sie ruft `configuredProvider(...)` auf.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(eventbus): resolve the provider from environment and configuration"
```

---

### Task 2: Alte Redis-Event-API entfernen

**Files:**
- Delete: `surf-redis-api/src/main/kotlin/dev/slne/surf/redis/event/` (drei Dateien)
- Delete: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/event/RedisEventBusImpl.kt`
- Delete: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/event/RedisEventInvoker.kt`
- Delete: `surf-redis-core/src/main/java/dev/slne/surf/redis/invoker/RedisEventInvokerTemplate.java`
- Modify: `surf-redis-api/src/main/kotlin/dev/slne/surf/redis/RedisApi.kt`
- Modify: `surf-redis-api/src/main/kotlin/dev/slne/surf/redis/RedisComponentProvider.kt`
- Modify: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/RedisComponentProviderImpl.kt`

**Interfaces:**
- Consumes: nichts
- Produces: `RedisApi` ohne `eventBus`, `publishEvent`, `subscribeToEvents`; `connect`,
  `freezeAndConnect`, `disconnect` als `suspend` und `@InternalRedisAPI`;
  `RedisComponentProvider` ohne `createEventBus` und ohne `injectOriginId(RedisEvent)`

- [ ] **Step 1: Event-Typen löschen**

```bash
git rm -r surf-redis-api/src/main/kotlin/dev/slne/surf/redis/event
git rm surf-redis-core/src/main/kotlin/dev/slne/surf/redis/event/RedisEventBusImpl.kt \
       surf-redis-core/src/main/kotlin/dev/slne/surf/redis/event/RedisEventInvoker.kt \
       surf-redis-core/src/main/java/dev/slne/surf/redis/invoker/RedisEventInvokerTemplate.java
```

`RedisRequestHandlerInvokerTemplate.java` und `RedisInvokerLookupProvider.java` bleiben — das
Request/Response-System benutzt sie weiter.

- [ ] **Step 2: RedisApi aufräumen**

In `RedisApi.kt`:
- Das Feld `eventBus` und seinen `initializables.put(eventBus, Unit)` im `init`-Block
  entfernen; `requestResponseBus` bleibt
- `publishEvent(event: RedisEvent)` und `subscribeToEvents(listener: Any)` entfernen
- In `disconnect()` die Zeile `eventBus.close()` entfernen
- Den Import von `dev.slne.surf.redis.event.RedisEvent` und `…event.RedisEventBus` entfernen
- Den KDoc-Absatz „event distribution via [eventBus]" und das `subscribeToEvents`-Beispiel im
  Klassen-KDoc durch einen Verweis auf den Bus ersetzen:

```kotlin
 * `RedisApi` owns the underlying Redisson clients and wires up the Redis-specific surf features:
 * - request/response messaging via [requestResponseBus]
 * - replicated in-memory data structures ([SyncList], [SyncSet], [SyncMap], [SyncValue])
 * - simple cache helpers ([SimpleRedisCache], [SimpleSetRedisCache])
 *
 * Events do **not** live here: they go through `dev.slne.surf.eventbus.SurfEventBus`, which
 * owns this instance's lifecycle. Obtain this api with `bus.transport<RedisApi>()`.
```

- Lebenszyklus auf `suspend` umstellen und als intern markieren:

```kotlin
    @InternalRedisAPI
    suspend fun connect(): RedisApi = apply {
        require(isFrozen()) { "Redis client must be frozen before connecting" }
        require(!isConnected()) { "Redis client already initialized" }

        withContext(Dispatchers.IO) {
            // Redisson's own setup and the Reactor-based component initialization are
            // blocking; keeping them off the caller's dispatcher is the whole point of the
            // suspend boundary.
            connectBlocking()
        }
    }

    @Blocking
    private fun connectBlocking() { /* the previous body of connect() */ }

    @InternalRedisAPI
    suspend fun freezeAndConnect(): RedisApi = apply {
        freeze()
        connect()
    }

    @InternalRedisAPI
    suspend fun disconnect() {
        withContext(Dispatchers.IO) { disconnectBlocking() }
    }

    @Blocking
    private fun disconnectBlocking() { /* the previous body of disconnect() */ }
```

`freeze()` bleibt synchron und öffentlich — es ist reine Zustandsprüfung.

- [ ] **Step 3: Provider-Schnittstelle aufräumen**

In `RedisComponentProvider.kt`:
- `fun createEventBus(redisApi: RedisApi): RedisEventBus` entfernen
- `fun injectOriginId(event: RedisEvent)` entfernen; `injectOriginId(request: RedisRequest)`
  bleibt
- Importe von `RedisEvent` und `RedisEventBus` entfernen

In `RedisComponentProviderImpl.kt` die `createEventBus`-Überschreibung und die Importe von
`RedisEventBus`/`RedisEventBusImpl` entfernen.

- [ ] **Step 4: Auf verbleibende Referenzen prüfen**

Run: `grep -rn -e 'RedisEvent' -e '@OnRedisEvent' -e 'publishEvent' -e 'subscribeToEvents' --include='*.kt' --include='*.java' . | grep -v '/build/' | grep -v '^./docs/'`
Expected: keine Treffer

- [ ] **Step 5: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 6: ABI-Dump aktualisieren**

Run: `./gradlew updateLegacyAbi && git diff -- surf-redis-api/api/surf-redis-api.api | head -60`
Expected: `RedisEvent`, `RedisEventBus`, `OnRedisEvent`, `publishEvent`, `subscribeToEvents`
verschwinden; `connect`/`disconnect` erscheinen als `suspend`. Alles andere bleibt.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(redis)!: remove the transport-specific event API

Events go through SurfEventBus, which also owns the RedisApi lifecycle.
Sync structures, caches and request/response are untouched."
```

---

### Task 3: Ein Plattform-Plugin pro Plattform

**Files:**
- Create: `surf-platform-paper/build.gradle.kts`
- Create: `surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper/PaperBootstrap.kt`
- Create: `surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper/PaperMain.kt`
- Create: `surf-platform-velocity/build.gradle.kts`
- Create: `surf-platform-velocity/src/main/kotlin/dev/slne/surf/eventbus/platform/velocity/VelocityMain.kt`
- Move: die vier `*Instance*`-Implementierungen aus den bisherigen Plattform-Modulen
- Delete: `surf-rabbitmq-paper/`, `surf-rabbitmq-velocity/`, `surf-redis-paper/`,
  `surf-redis-velocity/`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `RabbitMQInstance`, `RedisInstance`, `EventBusProviderConfig`
- Produces: die Plugins `surf-platform-paper` und `surf-platform-velocity`, die beide
  Transport-Instanzen per `@AutoService` bereitstellen und den konfigurierten Provider aus der
  globalen YAML lesen

- [ ] **Step 1: Neue Module anlegen**

`surf-platform-paper/build.gradle.kts`:

```kotlin
plugins {
    id("dev.slne.surf.api.gradle.paper-plugin")
}

surfPaperPluginApi {
    mainClass("dev.slne.surf.eventbus.platform.paper.PaperMain")
    bootstrapper("dev.slne.surf.eventbus.platform.paper.PaperBootstrap")
    foliaSupported(true)
    authors.addAll("twisti")
}

dependencies {
    api(projects.surfEventbusApi)
    api(projects.surfEventbusCore)
    api(projects.surfRabbitmqCore)
    api(projects.surfRedisCore)
}
```

`surf-platform-velocity/build.gradle.kts`:

```kotlin
plugins {
    id("dev.slne.surf.api.gradle.velocity")
}

velocityPluginFile {
    main = "dev.slne.surf.eventbus.platform.velocity.VelocityMain"
    authors = listOf("twisti")
}

dependencies {
    api(projects.surfEventbusApi)
    api(projects.surfEventbusCore)
    api(projects.surfRabbitmqCore)
    api(projects.surfRedisCore)
    api(projects.surfEventbusCommon)
}
```

In `settings.gradle.kts` die vier alten Plattform-Includes durch zwei ersetzen:

```kotlin
include("surf-platform-paper")
include("surf-platform-velocity")
```

- [ ] **Step 2: Instanz-Implementierungen übernehmen**

Die Instanzen bleiben inhaltlich unverändert; nur ihr Modul und ihr Package ändern sich. Die
`@AutoService`-Registrierungen tragen weiterhin `RabbitMQInstance` beziehungsweise
`RedisInstance`, sodass beide Transports ihre Plattformdaten wie bisher finden.

```bash
mkdir -p surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper
mkdir -p surf-platform-velocity/src/main/kotlin/dev/slne/surf/eventbus/platform/velocity

git mv surf-rabbitmq-paper/src/main/kotlin/dev/slne/surf/rabbitmq/paper/PaperRabbitMqInstance.kt \
       surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper/PaperRabbitMqInstance.kt
git mv surf-redis-paper/src/main/kotlin/dev/slne/surf/redis/RedisInstanceImpl.kt \
       surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper/PaperRedisInstance.kt
git mv surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/VelocityRabbitMqInstance.kt \
       surf-platform-velocity/src/main/kotlin/dev/slne/surf/eventbus/platform/velocity/VelocityRabbitMqInstance.kt
git mv surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/VelocityRedisInstanceImpl.kt \
       surf-platform-velocity/src/main/kotlin/dev/slne/surf/eventbus/platform/velocity/VelocityRedisInstance.kt
```

In jeder verschobenen Datei die `package`-Zeile auf das neue Package setzen und den
Klassennamen an den neuen Dateinamen anpassen (`RedisInstanceImpl` → `PaperRedisInstance`,
`VelocityRedisInstanceImpl` → `VelocityRedisInstance`).

- [ ] **Step 3: Bootstrap und Main zusammenführen**

Die bisherigen `PaperBootstrap`/`PaperMain` beider Projekte laden jeweils ihre Instanz. Die
zusammengeführte Fassung lädt beide **faul**: nur der konfigurierte Provider baut beim Start
eine Verbindung auf, alles andere entsteht erst bei Benutzung.

`surf-platform-paper/src/main/kotlin/dev/slne/surf/eventbus/platform/paper/PaperMain.kt` —
die beiden bisherigen `PaperMain`-Rümpfe werden in einer Klasse vereint. Die Reihenfolge ist
dabei wesentlich: `RedisInstance.load()` und der RabbitMQ-Gegenpart initialisieren
Netty-Eventloops und lesen Konfiguration; beides muss vor dem ersten Bus-Aufbau eines anderen
Plugins geschehen. Die vorhandene Struktur (Bootstrap richtet ein, Main aktiviert) wird
beibehalten:

```kotlin
package dev.slne.surf.eventbus.platform.paper

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.common.config.EventBusProviderConfig
import org.bukkit.plugin.java.JavaPlugin

/**
 * Provides both transports to every plugin on this server.
 *
 * One plugin instead of the previous two, and therefore one Netty copy instead of two. Both
 * transport APIs are available; a connection is only built for what is actually used, so a
 * server that never touches Redis pays nothing for it being on the classpath.
 */
class PaperMain : JavaPlugin() {

    companion object {
        private val log = logger()
    }

    override fun onEnable() {
        val configured = readConfiguredProvider()

        log.atInfo().log(
            "surf-eventbus platform enabled; configured provider: %s",
            configured ?: "<none - the single transport on the classpath decides>"
        )
    }

    override fun onDisable() {
        // Each transport instance disables what it started; see the disable() methods the
        // moved instances brought with them.
    }

    /**
     * Reads `eventbus.provider` from this plugin's own config, which is the global layer for
     * every plugin on the server.
     */
    private fun readConfiguredProvider(): String? = EventBusProviderConfig.resolve(
        env = System::getenv,
        globalYaml = { config.getString(EventBusProviderConfig.YAML_KEY) }
    )
}
```

Der ermittelte Wert wird über eine plattformseitige Halterung an den Builder gegeben, damit
Consumer ihn nicht selbst setzen müssen. Dazu in `surf-eventbus-core` eine kleine
Hilfsstruktur ergänzen:

`surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/PlatformProviderHint.kt`:

```kotlin
package dev.slne.surf.eventbus

/**
 * Where the platform layer puts the configured provider id so the builder can find it.
 *
 * Set once by the platform plugin before any consumer builds a bus. A plain holder rather than
 * a service, because at this point in startup the only thing that exists is the platform.
 */
@InternalEventBus
object PlatformProviderHint {
    @Volatile
    var configuredProviderId: String? = null
}
```

In `SurfEventBusBuilderImpl.build()` wird die Kette damit:

```kotlin
        val configured = configuredProvider
            ?: PlatformProviderHint.configuredProviderId
            ?: EventBusProviderConfig.resolve(env = System::getenv, globalYaml = { null })
```

und `PaperMain.onEnable` setzt `PlatformProviderHint.configuredProviderId = configured`.

Analog `VelocityMain`, ausgehend von den beiden bisherigen Velocity-Mains.

- [ ] **Step 4: Alte Module löschen**

```bash
git rm -r surf-rabbitmq-paper surf-rabbitmq-velocity surf-redis-paper surf-redis-velocity
```

- [ ] **Step 5: Build ausführen**

Run: `./gradlew build shadowJar -PskipIntegration`
Expected: PASS. Zwei Plugin-Jars entstehen statt vier.

- [ ] **Step 6: Ein Jar auf beide Transports prüfen**

Run: `unzip -l surf-platform-paper/build/libs/*-all.jar | grep -c -e 'dev/slne/surf/eventbus/libs/com/rabbitmq' -e 'dev/slne/surf/eventbus/libs/redisson'`
Expected: eine Zahl > 0 für beide Präfixe — beide Transports sind relociert im selben Jar.

Run: `unzip -l surf-platform-paper/build/libs/*-all.jar | grep -c 'io/netty'`
Expected: `0` — unrelocatiertes Netty im Jar wäre der Fehler, den die Relocation verhindert.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(platform)!: merge the four platform plugins into two

One plugin per platform, both transports available, one Netty copy."
```

---

### Task 4: Standalone-Betrieb für Microservices auf beiden Transports

Microservices laufen heute ausschließlich auf RabbitMQ: `StandaloneRabbitMqInstance` existiert,
ein Redis-Gegenstück nicht. Damit ein Microservice den Provider tatsächlich wählen kann, fehlt
genau dieses Gegenstück.

**Files:**
- Create: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/StandaloneRedisInstance.kt`
- Create: `surf-eventbus-test/src/test/kotlin/dev/slne/surf/eventbus/parity/StandaloneProviderTest.kt`

**Interfaces:**
- Consumes: `RedisInstance`
- Produces: `@AutoService(RedisInstance::class) class StandaloneRedisInstance : RedisInstance()`
  mit `override val dataPath: Path` und
  `override fun tryExtractPluginNameFromClass(clazz: Class<*>): String`

- [ ] **Step 1: Failing test schreiben**

```kotlin
package dev.slne.surf.eventbus.parity

import dev.slne.surf.eventbus.Provider
import dev.slne.surf.eventbus.SurfEventBus
import dev.slne.surf.eventbus.testing.BusFixture
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals

/**
 * A standalone process — a microservice, no Paper, no Velocity — must work on either provider.
 *
 * Without this, "pick a provider" would silently mean "pick RabbitMQ" for every microservice,
 * because only RabbitMQ had a standalone instance.
 */
@RequiresDocker
class StandaloneProviderTest {

    @ParameterizedTest
    @EnumSource(Provider::class)
    fun `a standalone process can publish and receive`(provider: Provider) = runBlocking {
        val listener = BroadcastListener()
        val service = BusFixture.uniqueService("standalone")
        val bus: SurfEventBus = BusFixture.bus(provider, service, listener)

        try {
            val publisher = BusFixture.bus(provider, BusFixture.uniqueService("pub"))
            publisher.publish(ParityPlainEvent("x"))

            BusFixture.awaitCondition("the standalone process receives the event") {
                listener.count.get() == 1
            }

            assertEquals(1, listener.count.get())
            publisher.disconnect()
        } finally {
            bus.disconnect()
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-test:test --tests '*StandaloneProviderTest*'`
Expected: FAIL für `REDIS` — `requiredService<RedisInstance>` findet keine Implementierung
außerhalb eines Plattform-Plugins. Für `RABBIT` grün, weil `StandaloneRabbitMqInstance`
existiert.

- [ ] **Step 3: Implementieren**

```kotlin
package dev.slne.surf.redis

import com.google.auto.service.AutoService
import java.nio.file.Path

/**
 * The Redis platform instance for a process without Paper or Velocity — a microservice, or a
 * test JVM.
 *
 * Mirrors `StandaloneRabbitMqInstance`. Without it a standalone process could only ever run
 * on RabbitMQ, which would make the provider choice a fiction for every microservice.
 */
@AutoService(RedisInstance::class)
class StandaloneRedisInstance : RedisInstance() {

    override val dataPath: Path = Path.of(
        System.getProperty("surf.redis.dataPath") ?: "."
    )

    /**
     * A standalone process is one application, so the plugin name is the same for every
     * caller: the first two package segments below `dev.slne.surf`, or the simple class name.
     */
    override fun tryExtractPluginNameFromClass(clazz: Class<*>): String =
        clazz.packageName
            .removePrefix("dev.slne.surf.")
            .split('.')
            .firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: clazz.simpleName
}
```

Sollte `StandaloneRabbitMqInstance` beim Laden im selben JVM mit dieser Klasse in Konflikt
geraten (beide sind `@AutoService`, aber für **verschiedene** Schnittstellen), ist das
unproblematisch: `requiredService<RabbitMQInstance>` und `requiredService<RedisInstance>`
laden getrennt.

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-test:test --tests '*StandaloneProviderTest*'`
Expected: PASS für beide Provider (mit Docker). Ohne Docker: übersprungen, Status „nicht
verifiziert".

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(redis): add a standalone instance so microservices can pick either provider"
```

---

### Task 5: README zusammenführen und Migrationsleitfaden schreiben

**Files:**
- Modify: `README.md` (vollständige Neufassung)
- Create: `docs/MIGRATION-2.0.md`

**Interfaces:**
- Consumes: alles
- Produces: ein README, das beide Transports und den Bus beschreibt; ein Leitfaden mit
  Vorher/Nachher pro Muster und der Liste betroffener Dateien

- [ ] **Step 1: README neu fassen**

Struktur, die sich aus dem Bestand ergibt — die vorhandenen README-Inhalte beider Projekte
werden übernommen, nicht neu erfunden:

1. **Was das ist** — ein Event-Bus mit wählbarem Provider, plus zwei transportgebundene
   Extras
2. **Provider wählen** — `eventbus.provider`, `SURF_EVENTBUS_PROVIDER`, Fleet-Entscheidung,
   kein Mischbetrieb
3. **Events** — `@BusEvent`, `SurfBusEvent`, `@SurfSubscribe`, die Modus-Tabelle aus dem
   bisherigen surf-rabbitmq-README (`SHARED`/`BROADCAST`), Muster-Syntax
4. **`includeSelf`** — Default `false` und warum
5. **Capability-Matrix** — die Tabelle aus der Spec, samt der beiden Fehlermeldungen im
   Wortlaut
6. **Was bei Störungen passiert** — die Tabelle aus dem bisherigen surf-rabbitmq-README,
   erweitert um eine Redis-Spalte
7. **RabbitMQ-spezifisch** — RPC, Fire-and-Forget, `InstanceTarget`, Retry, DLQ,
   `SURF_RABBITMQ_*`-Variablen, Coolify-Beispiel (aus dem bisherigen README übernehmen)
8. **Redis-spezifisch** — Sync-Strukturen, Caches, Request/Response, die Fallstricke-Liste
   aus dem bisherigen surf-redis-README (Sync-Strukturen vor `freeze()`, eventual consistency,
   kein Blocking in Handlern, einmal antworten)
9. **Architekturrichtlinie** — keine Microservice-Ketten (aus dem bisherigen README)
10. **Verweis** auf `docs/MIGRATION-2.0.md`

- [ ] **Step 2: Migrationsleitfaden schreiben**

`docs/MIGRATION-2.0.md` mit:

**Koordinaten**

```
dev.slne.surf.redis:surf-redis-api:1.5.0      -> dev.slne.surf.eventbus:surf-redis-api:2.0.0
dev.slne.surf.rabbitmq:surf-rabbitmq-api:1.6.2 -> dev.slne.surf.eventbus:surf-rabbitmq-api:2.0.0
                                               +  dev.slne.surf.eventbus:surf-eventbus-api:2.0.0
                                               +  dev.slne.surf.eventbus:surf-eventbus-core:2.0.0
```

**Events**

```kotlin
// vorher
@Serializable
class PlayerJoinedEvent(val playerId: UUID) : RedisEvent()

class PlayerListener {
    @OnRedisEvent
    fun onJoin(event: PlayerJoinedEvent) {
        if (event.originatesFromThisClient()) return
        …
    }
}

redisApi.publishEvent(PlayerJoinedEvent(id))
redisApi.subscribeToEvents(PlayerListener())

// nachher
@Serializable
@BusEvent("player.joined")
class PlayerJoinedEvent(val playerId: UUID) : SurfBusEvent()

class PlayerListener {
    // includeSelf ist standardmäßig false - die manuelle Prüfung entfällt
    @SurfSubscribe(mode = SubscriptionMode.BROADCAST)
    fun onJoin(event: PlayerJoinedEvent) { … }
}

bus.publish(PlayerJoinedEvent(id))
bus.registerListener(PlayerListener())
```

**Lebenszyklus**

```kotlin
// vorher
abstract class RedisService {
    val redisApi = RedisApi.create()
    fun connect() { register(); redisApi.freezeAndConnect() }
    fun disconnect() = redisApi.disconnect()
}

// nachher - der Bus besitzt den Lebenszyklus
abstract class RedisService {
    val bus = SurfEventBus.builder("surf-core", dataPath).build()
    val redisApi get() = bus.transport<RedisApi>()

    suspend fun connect() { register(); bus.freezeAndConnect() }
    suspend fun disconnect() = bus.disconnect()
}
```

**Modus-Wahl.** Der Default `SHARED` ist für einen Handler, der bisher `@OnRedisEvent` war,
**falsch**: Redis broadcastete immer. Jeder migrierte Handler braucht deshalb explizit
`mode = SubscriptionMode.BROADCAST`, sonst ändert sich sein Verhalten von „jede Instanz" zu
„genau eine". Auf dem Redis-Provider scheitert der Start in diesem Fall ohnehin — das ist der
Sinn des Capability-Modells —, auf dem Rabbit-Provider **nicht**. Diese Zeile ist der
riskanteste Teil der Migration und gehört in jedes Review.

**Was unverändert bleibt:** Sync-Strukturen, Caches, `RedisRequest`/`@HandleRedisRequest`,
RPC, `@RabbitHandler`, `send()` — gleiche Packages, gleiche Signaturen.

**Betroffene Dateien** (Stand 2026-07-29, ermittelt über den Workspace):

| Repository | Dateien mit `@OnRedisEvent` |
|---|---|
| `surf-core` | 5, plus der handgeschriebene verteilte Bus (`SurfEventBus.fire()`, `SurfEventFireRedisEvent`, `LocalSurfEventBusListener`) — ersatzlos zu löschen und durch den echten Bus zu ersetzen |
| `surf-punish-redis` | 6 |
| `surf-transaction` | 3 |
| `surf-friends` | 2 |
| `surf-chat` | 1 |
| `surf-maintenance` | 1 |
| `surf-tab` | 1 |

Ermittlungsbefehl zur Wiederholung:

```bash
rg -l --glob '*.kt' '@OnRedisEvent' | sed 's|/src/.*||' | sort | uniq -c
```

**Kein Mischbetrieb.** Wire-Format und API ändern sich beide. Alle Dienste müssen gemeinsam
deployt werden; eine alte und eine neue Instanz desselben Dienstes können nicht
nebeneinander laufen.

- [ ] **Step 3: Beispiele gegen den Code prüfen**

Jeder Kotlin-Block im README und im Leitfaden wird gegen die tatsächlichen Signaturen
geprüft — `SurfEventBus.builder`, `transport<T>()`, `@SurfSubscribe`-Parameter,
`RedisApi.createSyncSet`. Ein README-Beispiel, das nicht kompiliert, ist schlimmer als keines.

Run: `grep -n 'redisApi.publishEvent\|@OnRedisEvent\|RabbitEventPacket\|@RabbitSubscribe' README.md docs/MIGRATION-2.0.md`
Expected: Treffer ausschließlich in den „vorher"-Blöcken des Leitfadens

- [ ] **Step 4: Commit**

```bash
git add README.md docs/MIGRATION-2.0.md
git commit -m "docs: merge both readmes and write the 2.0 migration guide"
```

---

### Task 6: Abschluss

**Files:**
- Modify: `surf-rabbitmq-api/api/surf-rabbitmq-api.api`, `surf-redis-api/api/surf-redis-api.api`
- Modify: `settings.gradle.kts` (Aufräumen)

**Interfaces:**
- Consumes: alles
- Produces: ein veröffentlichungsfähiger Stand

- [ ] **Step 1: Modulliste prüfen**

Run: `./gradlew projects`
Expected genau diese Module:

```
surf-eventbus-common
surf-eventbus-api
surf-eventbus-core
surf-circuitbreaker
surf-rabbitmq-api
surf-rabbitmq-core
surf-rabbitmq-ksp
surf-redis-api
surf-redis-core
surf-platform-paper
surf-platform-velocity
surf-rabbitmq-test        (nur außerhalb CI)
surf-eventbus-test        (nur außerhalb CI)
```

- [ ] **Step 2: ABI-Dumps final aktualisieren**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`
Expected: PASS

- [ ] **Step 3: Vollständigen Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

Run: `./gradlew build` (mit Docker-Daemon)
Expected: PASS. Ohne Daemon wird der Status der Integrationstests als **„nicht verifiziert"**
berichtet, nicht als „bestanden".

- [ ] **Step 4: Veröffentlichung lokal probieren**

Run: `./gradlew publishToMavenLocal`
Expected: PASS. Prüfen, dass unter
`~/.m2/repository/dev/slne/surf/eventbus/` alle Module in Version `2.0.0` liegen.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "chore: finalize the 2.0 module layout and abi dumps"
```

- [ ] **Step 6: Offene manuelle Schritte festhalten**

Diese Schritte liegen außerhalb dessen, was aus diesem Repository heraus möglich ist, und
werden dem Anwender berichtet statt stillschweigend übergangen:

1. GitHub-Repository von `surf-rabbitmq` nach `surf-eventbus` umbenennen
2. Die 8 Consumer-Repositories nach `docs/MIGRATION-2.0.md` migrieren und **gemeinsam**
   deployen
3. `eventbus.provider` in der globalen Konfiguration der Flotte setzen
4. Nach erfolgreichem Rollout das Repository `surf-redis` archivieren — die Historie seiner
   Module liegt durch den Subtree-Import in Plan 1 auch hier

---

## Definition of Done

- Eine Event-API im gesamten Repository; `RedisEvent`, `@OnRedisEvent`, `RabbitEventPacket`
  und `@RabbitSubscribe` existieren nicht mehr
- Zwei Plattform-Plugins statt vier, beide Transports in einem Jar, ein Netty
- Ein Microservice kann beide Provider benutzen
- `eventbus.provider` und `SURF_EVENTBUS_PROVIDER` werden ausgewertet
- README beschreibt Bus, beide Provider und die Capability-Matrix;
  `docs/MIGRATION-2.0.md` nennt jedes betroffene Repository
- `./gradlew build -PskipIntegration` und `./gradlew publishToMavenLocal` grün
- Die vier verbleibenden manuellen Schritte sind benannt und dem Anwender berichtet
