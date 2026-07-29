# surf-eventbus Plan 1: Fundament und Absorption von surf-redis

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** surf-redis liegt als Modul in diesem Repository, das geteilte Fundament existiert
einmal statt zweimal, und der Build ist grün — ohne jede Verhaltensänderung.

**Architecture:** Diese Etappe verschiebt und dedupliziert ausschließlich. Kein Event-Bus,
keine neue API, keine geänderte Semantik. Neues Modul `surf-eventbus-common` nimmt die
broker-neutralen Bausteine beider Projekte auf; surf-redis wird per `git subtree` mit Historie
importiert; Netty, Shading und ABI-Validierung werden zusammengeführt.

**Tech Stack:** Kotlin/JVM, Gradle Kotlin DSL mit `dev.slne.surf.api.gradle.*`-Konventionen,
JUnit 5, Testcontainers, Shadow, Kotlin ABI Validation, kotlinx.serialization.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-29-surf-eventbus-design.md`
- Gruppe aller Module: `dev.slne.surf.eventbus`. Version: `2.0.0` (in `gradle.properties`)
- `rootProject.name = "surf-eventbus"`
- Package-Namen bleiben unverändert: `dev.slne.surf.rabbitmq.*`, `dev.slne.surf.redis.*`.
  Neu ist ausschließlich `dev.slne.surf.eventbus.*`
- Netty konvergiert auf `4.2.16.Final` — der höhere der beiden bisherigen Pins
- Eine Netty-Relocation-Basis: `dev.slne.surf.eventbus.shaded.io.netty`
- Eine Bibliotheks-Relocation-Basis: `dev.slne.surf.eventbus.libs.`
- In dieser Etappe ändert sich **kein** öffentliches Verhalten. Jede Task endet mit
  `./gradlew build -PskipIntegration` grün
- Ohne Docker-Daemon ist der Status von Integrationstests „nicht verifiziert", nicht „bestanden"

---

### Task 1: Repository-Identität auf surf-eventbus umstellen

**Files:**
- Modify: `settings.gradle.kts:13`
- Modify: `gradle.properties:4`
- Modify: `build.gradle.kts:20-23`
- Modify: `gradle/libs.versions.toml:3`

**Interfaces:**
- Consumes: nichts
- Produces: Gruppe `dev.slne.surf.eventbus`, Version `2.0.0`, `rootProject.name` =
  `surf-eventbus`, Netty-Version `4.2.16.Final` als einziger Pin

- [ ] **Step 1: Projektnamen und Version setzen**

In `settings.gradle.kts` Zeile 13:

```kotlin
rootProject.name = "surf-eventbus"
```

In `gradle.properties` die Versionszeile ersetzen:

```properties
version=2.0.0
```

- [ ] **Step 2: Gruppe umstellen**

In `build.gradle.kts` den `allprojects`-Block:

```kotlin
allprojects {
    group = "dev.slne.surf.eventbus"
    version = findProperty("version") as String
}
```

- [ ] **Step 3: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS. Die Koordinaten sind reine Metadaten; kein Quellcode referenziert sie.

- [ ] **Step 4: Commit**

```bash
git add settings.gradle.kts gradle.properties build.gradle.kts
git commit -m "chore: rename project to surf-eventbus and open the 2.0 line"
```

---

### Task 2: Modul surf-eventbus-common anlegen und Serializer-Caches zusammenführen

Die beiden `KotlinSerializerCache`-Klassen unterscheiden sich in einem Parameternamen
(`clazz` gegen `type`). Sie werden zu einer, im neuen Modul.

**Files:**
- Create: `surf-eventbus-common/build.gradle.kts`
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCache.kt`
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerNameCache.kt`
- Create: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCacheTest.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/shared/serialization/KotlinSerializerCache.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/shared/serialization/KotlinSerializerNameCache.kt`
- Modify: `settings.gradle.kts` — `include("surf-eventbus-common")`
- Modify: `surf-rabbitmq-core/build.gradle.kts` — `api(projects.surfEventbusCommon)`

**Interfaces:**
- Consumes: nichts
- Produces:
  - `dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache<T>(module: SerializersModule, type: Class<T>) : ClassValue<KSerializer<T>?>`
    mit `companion object { inline operator fun <reified T> invoke(module: SerializersModule): KotlinSerializerCache<T> }`
  - `dev.slne.surf.eventbus.common.serialization.KotlinSerializerNameCache<T>` mit
    `fun register(type: Class<out T>)` und `fun get(className: String): KSerializer<T>?`
    — Signaturen unverändert gegenüber `surf-rabbitmq-core`

- [ ] **Step 1: Modul-Build-Datei anlegen**

`surf-eventbus-common/build.gradle.kts`:

```kotlin
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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

In `settings.gradle.kts` nach `include("surf-circuitbreaker")` ergänzen:

```kotlin
include("surf-eventbus-common")
```

- [ ] **Step 2: Failing test schreiben**

`surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCacheTest.kt`:

```kotlin
package dev.slne.surf.eventbus.common.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.EmptySerializersModule
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

abstract class Base

@Serializable
class Child(val value: String) : Base()

class Unrelated

class KotlinSerializerCacheTest {

    private val cache = KotlinSerializerCache<Base>(EmptySerializersModule())

    @Test
    fun `resolves a serializer for a subtype of the bound type`() {
        assertNotNull(cache.get(Child::class.java))
    }

    @Test
    fun `returns null for a type outside the bound hierarchy`() {
        assertNull(cache.get(Unrelated::class.java))
    }

    @Test
    fun `caches the resolved serializer per class`() {
        assertSame(cache.get(Child::class.java), cache.get(Child::class.java))
    }
}
```

- [ ] **Step 3: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-eventbus-common:test`
Expected: FAIL — `Unresolved reference: KotlinSerializerCache`

- [ ] **Step 4: Beide Klassen verschieben**

```bash
mkdir -p surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization
git mv surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/shared/serialization/KotlinSerializerCache.kt \
       surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCache.kt
git mv surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/shared/serialization/KotlinSerializerNameCache.kt \
       surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerNameCache.kt
```

In beiden Dateien die `package`-Zeile ersetzen durch:

```kotlin
package dev.slne.surf.eventbus.common.serialization
```

In `KotlinSerializerCache.kt` den Konstruktorparameter zur Vereinheitlichung mit der
Redis-Variante auf `type` umbenennen (der Rumpf muss dann `this.type` verwenden, weil
`computeValue` einen Parameter gleichen Namens hat):

```kotlin
class KotlinSerializerCache<T>(
    private val module: SerializersModule,
    private val type: Class<T>
) : ClassValue<KSerializer<T>?>() {
    @Suppress("UNCHECKED_CAST")
    override fun computeValue(type: Class<*>): KSerializer<T>? {
        if (!this.type.isAssignableFrom(type)) return null
        return module.serializerOrNull(type) as? KSerializer<T>
    }

    companion object {
        inline operator fun <reified T> invoke(
            module: SerializersModule,
        ): KotlinSerializerCache<T> = KotlinSerializerCache(module, T::class.java)
    }
}
```

- [ ] **Step 5: Abhängigkeit und Importe nachziehen**

In `surf-rabbitmq-core/build.gradle.kts` im ersten `dependencies`-Block als erste Zeile:

```kotlin
    api(projects.surfEventbusCommon)
```

Alle Importe umschreiben:

```bash
grep -rl 'dev.slne.surf.rabbitmq.shared.serialization' --include='*.kt' --include='*.java' . \
  | xargs sed -i 's/dev\.slne\.surf\.rabbitmq\.shared\.serialization/dev.slne.surf.eventbus.common.serialization/g'
```

- [ ] **Step 6: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-common:test build -PskipIntegration`
Expected: PASS, drei neue Tests grün

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move the serializer caches into surf-eventbus-common"
```

---

### Task 3: Rabbit-spezifischen Invoker aus dem neutralen Package holen

`shared/dispatch/HandlerTemplate.java` importiert `RabbitRequestPacket` und implementiert
`RabbitListenerHandler` — es liegt in einem Package, das laut `SharedPackagePurityTest`
broker-neutral sein soll. Der Test hat das nie gemerkt, weil er nur `.kt`-Dateien liest. Die
Datei ist rabbit-spezifisch und zieht dorthin; der Test lernt `.java`.

**Files:**
- Move: `surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/shared/dispatch/HandlerTemplate.java`
  → `surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/listener/invoker/HandlerTemplate.java`
- Move: `surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/shared/dispatch/HandlerMethodHandleProvider.java`
  → `surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/listener/invoker/HandlerMethodHandleProvider.java`
- Modify: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/shared/SharedPackagePurityTest.kt`

**Interfaces:**
- Consumes: nichts
- Produces: `dev.slne.surf.rabbitmq.listener.invoker.HandlerTemplate` und
  `HandlerMethodHandleProvider` (Klassennamen und Member unverändert)

- [ ] **Step 1: Purity-Test verschärfen (failing test)**

`SharedPackagePurityTest.kt` vollständig ersetzen:

```kotlin
package dev.slne.surf.rabbitmq.shared

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * Keeps the broker-neutral packages free of transport types.
 *
 * These packages are shared between the RabbitMQ and the Redis transport. A single import of
 * either would couple them, and nothing else in the build would notice.
 */
class SharedPackagePurityTest {

    private val neutralPackages = listOf(
        "shared/serialization",
        "shared/dispatch",
        "shared/config",
        "platform"
    )

    private val forbidden = listOf(
        "com.rabbitmq",
        "RabbitMQ",
        "RabbitPacket",
        "RabbitClient",
        "RabbitRequestPacket",
        "RabbitListenerHandler",
        "org.redisson",
        "Redisson"
    )

    @Test
    fun `broker-neutral packages do not reference a transport`() {
        val roots = listOf("kotlin", "java").map { language ->
            Path.of("src", "main", language, "dev", "slne", "surf", "rabbitmq")
        }

        val offenders = roots.flatMap { root ->
            neutralPackages
                .map(root::resolve)
                .filter(Files::exists)
                .flatMap { dir ->
                    Files.walk(dir).asSequence()
                        .filter { it.extension == "kt" || it.extension == "java" }
                        .mapNotNull { file ->
                            val text = file.readText()
                            forbidden.firstOrNull { text.contains(it) }
                                ?.let { "$file references '$it'" }
                        }
                        .toList()
                }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "These packages must stay transport-neutral so both transports can share " +
                        "them:\n" + offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen und Fehlschlag bestätigen**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*SharedPackagePurityTest*'`
Expected: FAIL — nennt `HandlerTemplate.java references 'RabbitRequestPacket'`. Das ist die
Verletzung, die der alte Test nicht sehen konnte.

- [ ] **Step 3: Dateien verschieben**

```bash
mkdir -p surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/listener/invoker
git mv surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/shared/dispatch/HandlerTemplate.java \
       surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/listener/invoker/HandlerTemplate.java
git mv surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/shared/dispatch/HandlerMethodHandleProvider.java \
       surf-rabbitmq-core/src/main/java/dev/slne/surf/rabbitmq/listener/invoker/HandlerMethodHandleProvider.java
```

In beiden Dateien die `package`-Zeile ersetzen durch:

```java
package dev.slne.surf.rabbitmq.listener.invoker;
```

Referenzen nachziehen:

```bash
grep -rl 'dev.slne.surf.rabbitmq.shared.dispatch' --include='*.kt' --include='*.java' . \
  | xargs sed -i 's/dev\.slne\.surf\.rabbitmq\.shared\.dispatch/dev.slne.surf.rabbitmq.listener.invoker/g'
```

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS, Purity-Test grün

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: move the rabbit handler invoker out of the neutral package

The purity test only read .kt files and therefore never saw that
HandlerTemplate.java imports RabbitRequestPacket. It now reads .java too
and forbids Redisson as well."
```

---

### Task 4: surf-redis mit Historie importieren

**Files:**
- Create (importiert): `surf-redis-api/`, `surf-redis-core/`, `surf-redis-paper/`,
  `surf-redis-velocity/`
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`

**Interfaces:**
- Consumes: nichts
- Produces: die Gradle-Projekte `:surf-redis-api`, `:surf-redis-core`, `:surf-redis-paper`,
  `:surf-redis-velocity` mit unveränderten Packages `dev.slne.surf.redis.*`

- [ ] **Step 1: Subtree importieren**

`git subtree` behält die Historie, damit sie den Abschaltvorgang von surf-redis überlebt.

```bash
git remote add surf-redis S:/Workspaces/surf-redis
git fetch surf-redis version/26.1
git subtree add --prefix=.import-surf-redis surf-redis version/26.1
```

- [ ] **Step 2: Module herausziehen, Rest verwerfen**

```bash
git mv .import-surf-redis/surf-redis-api      surf-redis-api
git mv .import-surf-redis/surf-redis-core     surf-redis-core
git mv .import-surf-redis/surf-redis-paper    surf-redis-paper
git mv .import-surf-redis/surf-redis-velocity surf-redis-velocity
git rm -r --quiet .import-surf-redis
git commit -m "chore: lift the surf-redis modules to the repository root"
```

Verworfen werden dabei bewusst: `settings.gradle.kts`, `build.gradle.kts`,
`gradle.properties`, `gradlew`, `gradle/`, `LICENSE` und `README.md` von surf-redis. Der
README-Inhalt wird in Plan 4 zusammengeführt; er ist über die importierte Historie
weiterhin erreichbar.

- [ ] **Step 3: Redisson-Version in den Katalog aufnehmen**

`gradle/libs.versions.toml` — `redisson` ergänzen, `netty` auf den höheren Pin setzen:

```toml
[versions]
amqp-client = "5.34.0" # Update netty version if needed
netty = "4.2.16.Final" # Keep in sync with https://github.com/rabbitmq/rabbitmq-java-client/blob/main/pom.xml
redisson = "4.3.0"
surf-microservice = "2.+"
junit = "5.11.4"
coroutines = "1.10.2"
testcontainers = "1.21.3"

[libraries]
amqp-client = { module = "com.rabbitmq:amqp-client", version.ref = "amqp-client" }
netty-bom = { group = "io.netty", name = "netty-bom", version.ref = "netty" }
redisson = { group = "org.redisson", name = "redisson", version.ref = "redisson" }
surf-microservice = { group = "dev.slne.surf.microservice", name = "surf-microservice-api-microservice", version.ref = "surf-microservice" }
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-core = { module = "org.testcontainers:testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter" }
testcontainers-rabbitmq = { module = "org.testcontainers:rabbitmq" }
```

- [ ] **Step 4: Module einbinden**

In `settings.gradle.kts` nach `include("surf-eventbus-common")`:

```kotlin
include("surf-redis-api")
include("surf-redis-core")
include("surf-redis-paper")
include("surf-redis-velocity")
```

- [ ] **Step 5: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS. Erwartbare Stolpersteine und ihre Behandlung:
- `surf-redis-api/build.gradle.kts` ruft `surfCoreApi { withApiValidation() }` — bleibt, das
  Gradle-Plugin ist dasselbe wie hier
- Der `optIn`-Block in der Wurzel-`build.gradle.kts` setzt nur
  `dev.slne.surf.rabbitmq.api.InternalRabbitMQ`; die Redis-Module brauchen zusätzlich
  `dev.slne.surf.redis.util.InternalRedisAPI`. Das behebt Task 5, hier genügt es, den Fehler
  zu sehen und mit Task 5 fortzufahren, falls er auftritt

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml
git commit -m "build: wire the imported surf-redis modules into the build"
```

---

### Task 5: Opt-ins, Shading und Netty in einem Wurzel-Build zusammenführen

Der `META-INF/native`-Mangling-Block existiert in beiden Projekten zeichengleich. Nach dem
Import steht er einmal in der Wurzel und deckt beide Transports ab.

**Files:**
- Modify: `build.gradle.kts` (vollständige `subprojects`-Neufassung)

**Interfaces:**
- Consumes: die Module aus Task 4
- Produces: eine Relocation-Basis `dev.slne.surf.eventbus.libs.`, eine Netty-Basis
  `dev.slne.surf.eventbus.shaded.`, beide Internal-Annotationen als projektweite Opt-ins

- [ ] **Step 1: subprojects-Block ersetzen**

In `build.gradle.kts` den gesamten `subprojects { … }`-Block ersetzen:

```kotlin
val nettyRelocationBase = "dev.slne.surf.eventbus.shaded." // must not contain "lib"
val mangledPrefix: String = nettyRelocationBase
    .replace("_", "_1")
    .replace(".", "_")

subprojects {
    if (name.contains("surf-rabbitmq-test")) return@subprojects

    afterEvaluate {
        extensions.findByType<KotlinJvmExtension>()?.apply {
            compilerOptions {
                optIn.add("dev.slne.surf.rabbitmq.api.InternalRabbitMQ")
                optIn.add("dev.slne.surf.redis.util.InternalRedisAPI")
            }
        }

        tasks.withType<ShadowJar> {
            exclude("kotlin/**")
            exclude("kotlinx/**")
            exclude("org/jetbrains/**")
            exclude("org/intellij/**")
            exclude("org/slf4j/**")
            exclude("io/ktor/**")

            val base = "dev.slne.surf.eventbus.libs."

            // RabbitMQ transport
            relocate("com.rabbitmq", base + "com.rabbitmq")

            // Redis transport
            relocate("com.esotericsoftware", base + "kryo")
            relocate("io.reactivex", base + "reactivex")
            relocate("javax.cache", base + "javax.cache")
            relocate("jodd", base + "jodd")
            relocate("net.bytebuddy", base + "bytebuddy")
            relocate("org.objenesis", base + "objenesis")
            relocate("org.redisson", base + "redisson")
            relocate("org.yaml", base + "yaml")

            // Shared by both transports
            relocate("io.netty", nettyRelocationBase + "io.netty")

            doLast {
                val jar = archiveFile.get().asFile
                if (!jar.exists()) return@doLast

                val tmpJar = File(jar.parentFile, "${jar.name}.tmp")

                ZipFile(jar).use { zipIn ->
                    ZipOutputStream(tmpJar.outputStream()).use { zipOut ->
                        for (entry in zipIn.entries()) {
                            val name = entry.name

                            val newName = if (
                                name.startsWith("META-INF/native/") &&
                                name != "META-INF/native/" &&
                                name.contains("netty_")
                            ) {
                                val fileName = name.substringAfter("META-INF/native/")
                                val nettyIndex = fileName.indexOf("netty_")
                                if (nettyIndex >= 0) {
                                    val before = fileName.substring(0, nettyIndex)
                                    val after = fileName.substring(nettyIndex)
                                    "META-INF/native/$before$mangledPrefix$after"
                                } else {
                                    name
                                }
                            } else {
                                name
                            }

                            val newEntry = ZipEntry(newName)
                            newEntry.time = entry.time
                            if (entry.method == ZipEntry.STORED) {
                                newEntry.method = ZipEntry.STORED
                                newEntry.size = entry.size
                                newEntry.crc = entry.crc
                                newEntry.compressedSize = entry.compressedSize
                            }
                            zipOut.putNextEntry(newEntry)
                            zipIn.getInputStream(entry).use { input ->
                                input.copyTo(zipOut)
                            }
                            zipOut.closeEntry()
                        }
                    }
                }

                jar.delete()
                tmpJar.renameTo(jar)
            }
        }
    }
}
```

Der Import `com.github.jengelman.gradle.plugins.shadow.ShadowExtension` und der Block
`configure<ShadowExtension> { addShadowVariantIntoJavaComponent = false }` aus surf-redis
werden **nicht** übernommen: surf-redis setzte das nur, um ausschließlich die Shadow-Variante
zu publizieren, was hier durch `surf-redis-api/build.gradle.kts` weiterhin lokal geregelt ist.
Sollte `./gradlew :surf-redis-api:publishToMavenLocal` daraufhin eine doppelte Variante
melden, wird der Block in `surf-redis-api/build.gradle.kts` nachgezogen, nicht in die Wurzel.

- [ ] **Step 2: Build und Shadow-Aufgaben ausführen**

Run: `./gradlew build shadowJar -PskipIntegration`
Expected: PASS

- [ ] **Step 3: Relocation im Jar prüfen**

Run: `unzip -l surf-redis-paper/build/libs/*-all.jar | grep -c 'dev/slne/surf/eventbus/shaded/io/netty'`
Expected: eine Zahl > 0 — Netty ist auf die gemeinsame Basis relociert.

Run: `unzip -l surf-redis-paper/build/libs/*-all.jar | grep -c 'META-INF/native/.*dev_slne_surf_eventbus_shaded_.*netty_'`
Expected: eine Zahl > 0 auf Linux-Builds — die Native-Bibliotheken tragen das gemangelte
Präfix. Auf einem Build ohne Linux-Natives ist `0` korrekt; dann stattdessen prüfen, dass
`unzip -l … | grep 'META-INF/native'` leer ist.

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: merge shading, netty pinning and opt-ins of both transports"
```

---

### Task 6: Redis' Serializer-Cache durch den gemeinsamen ersetzen

**Files:**
- Delete: `surf-redis-core/src/main/kotlin/dev/slne/surf/redis/util/KotlinSerializerCache.kt`
- Modify: `surf-redis-core/build.gradle.kts`
- Modify: alle Redis-Dateien, die den Cache importieren (per `sed`, siehe Step 2)

**Interfaces:**
- Consumes: `dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache` aus Task 2
- Produces: nichts Neues — eine Klasse weniger

- [ ] **Step 1: Abhängigkeit ergänzen**

In `surf-redis-core/build.gradle.kts` im `dependencies`-Block vor `api(projects.surfRedisApi)`:

```kotlin
    api(projects.surfEventbusCommon)
```

- [ ] **Step 2: Duplikat löschen und Importe umbiegen**

```bash
git rm surf-redis-core/src/main/kotlin/dev/slne/surf/redis/util/KotlinSerializerCache.kt
grep -rl 'dev.slne.surf.redis.util.KotlinSerializerCache' --include='*.kt' . \
  | xargs sed -i 's/dev\.slne\.surf\.redis\.util\.KotlinSerializerCache/dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache/g'
```

- [ ] **Step 3: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS. Sollte eine Datei den Cache über einen Wildcard-Import
(`import dev.slne.surf.redis.util.*`) beziehen, ergänzt man dort den expliziten Import
`import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: drop the duplicated redis serializer cache"
```

---

### Task 7: Plattform-Reflection-Proxies zusammenführen

`JavaPluginLoaderProxy` und `SerializedPluginDescriptionProxy` existieren in
`surf-rabbitmq-velocity` und `surf-redis-velocity` identisch bis auf das `package`-Statement.

**Files:**
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/JavaPluginLoaderProxy.kt`
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/SerializedPluginDescriptionProxy.kt`
- Delete: `surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/JavaPluginLoaderProxy.kt`
- Delete: `surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/SerializedPluginDescriptionProxy.kt`
- Delete: `surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/JavaPluginLoaderProxy.kt`
- Delete: `surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/SerializedPluginDescriptionProxy.kt`
- Modify: `surf-rabbitmq-velocity/build.gradle.kts`, `surf-redis-velocity/build.gradle.kts`

**Interfaces:**
- Consumes: nichts
- Produces: `dev.slne.surf.eventbus.common.platform.JavaPluginLoaderProxy` und
  `SerializedPluginDescriptionProxy` — Klassennamen und öffentliche Member unverändert
  gegenüber der Rabbit-Variante

- [ ] **Step 1: Gleichheit belegen, bevor eine der beiden gewinnt**

```bash
diff <(tail -n +2 surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/JavaPluginLoaderProxy.kt) \
     <(tail -n +2 surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/JavaPluginLoaderProxy.kt)
diff <(tail -n +2 surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/SerializedPluginDescriptionProxy.kt) \
     <(tail -n +2 surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/SerializedPluginDescriptionProxy.kt)
```

Expected: kein Ausgabeunterschied außer eventuell Importzeilen des jeweils anderen Proxy.
Weicht mehr ab, wird **nicht** zusammengeführt: dann bleibt diese Task aus und die
Abweichung wird im Plan-Review gemeldet, weil ein stiller Verhaltensunterschied im
Plugin-Loading schwer zu diagnostizieren ist.

- [ ] **Step 2: Rabbit-Variante als gemeinsame Fassung verschieben**

```bash
mkdir -p surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform
git mv surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/JavaPluginLoaderProxy.kt \
       surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/JavaPluginLoaderProxy.kt
git mv surf-rabbitmq-velocity/src/main/kotlin/dev/slne/surf/rabbitmq/velocity/reflection/SerializedPluginDescriptionProxy.kt \
       surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/SerializedPluginDescriptionProxy.kt
git rm surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/JavaPluginLoaderProxy.kt \
       surf-redis-velocity/src/main/kotlin/dev/slne/surf/redis/reflection/SerializedPluginDescriptionProxy.kt
```

In beiden verschobenen Dateien die `package`-Zeile ersetzen durch:

```kotlin
package dev.slne.surf.eventbus.common.platform
```

- [ ] **Step 3: Velocity-Module auf das gemeinsame Modul zeigen lassen**

In `surf-rabbitmq-velocity/build.gradle.kts` und `surf-redis-velocity/build.gradle.kts`
jeweils im `dependencies`-Block ergänzen:

```kotlin
    api(projects.surfEventbusCommon)
```

Importe nachziehen:

```bash
grep -rl -e 'dev.slne.surf.rabbitmq.velocity.reflection' -e 'dev.slne.surf.redis.reflection' \
  --include='*.kt' . \
  | xargs sed -i \
      -e 's/dev\.slne\.surf\.rabbitmq\.velocity\.reflection/dev.slne.surf.eventbus.common.platform/g' \
      -e 's/dev\.slne\.surf\.redis\.reflection/dev.slne.surf.eventbus.common.platform/g'
```

- [ ] **Step 4: Build ausführen**

Run: `./gradlew build -PskipIntegration`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: share the velocity plugin loader proxies"
```

---

### Task 8: ABI-Dumps neu erzeugen und Absorption abschließen

**Files:**
- Modify: `surf-rabbitmq-api/api/surf-rabbitmq-api.api`
- Modify: `surf-redis-api/api/surf-redis-api.api`

**Interfaces:**
- Consumes: alle vorigen Tasks
- Produces: aktuelle ABI-Dumps, die die verschobenen Packages abbilden

- [ ] **Step 1: Prüfen, was die ABI-Validierung meldet**

Run: `./gradlew checkLegacyAbi`
Expected: FAIL für beide `-api`-Module — die Dumps kennen die alten Package-Namen der
verschobenen Klassen noch.

- [ ] **Step 2: Dumps aktualisieren**

Run: `./gradlew updateLegacyAbi`

- [ ] **Step 3: Diff der Dumps prüfen**

Run: `git diff --stat -- '*/api/*.api'`
Expected: Änderungen ausschließlich an Zeilen, die
`dev/slne/surf/rabbitmq/shared/serialization`, `dev/slne/surf/rabbitmq/shared/dispatch`,
`dev/slne/surf/rabbitmq/velocity/reflection` oder `dev/slne/surf/redis/util/KotlinSerializerCache`
betreffen. Jede andere Änderung ist ein unbeabsichtigter API-Bruch und muss vor dem Commit
untersucht werden.

- [ ] **Step 4: Vollständigen Build inklusive Integrationstests versuchen**

Run: `./gradlew build`
Expected: PASS, falls ein Docker-Daemon läuft. Ohne Daemon: `./gradlew build -PskipIntegration`
und der Status der Integrationstests wird als „nicht verifiziert" berichtet.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "build: refresh the ABI dumps after the module merge"
```

---

## Definition of Done

- `./gradlew build -PskipIntegration` grün
- `:surf-redis-api`, `:surf-redis-core`, `:surf-redis-paper`, `:surf-redis-velocity` sind
  Projekte dieses Repositories, mit Historie
- `KotlinSerializerCache`, `KotlinSerializerNameCache`, `JavaPluginLoaderProxy` und
  `SerializedPluginDescriptionProxy` existieren je einmal
- `SharedPackagePurityTest` liest `.kt` **und** `.java` und verbietet auch Redisson
- Eine Netty-Version, eine Relocation-Basis, ein Mangling-Block
- Keine Änderung am Verhalten oder an einer öffentlichen Signatur außer den verschobenen
  Package-Namen der vier oben genannten internen Klassen
