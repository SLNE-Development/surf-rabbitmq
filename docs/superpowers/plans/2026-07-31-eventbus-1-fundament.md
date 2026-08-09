# surf-eventbus 1: Fundament Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** use
> `superpowers:subagent-driven-development` — this repository's owner has forbidden subagents.

**Goal:** Aus surf-rabbitmq 1.6.2 und surf-redis 1.10.1 ein Repository `surf-eventbus` 2.0.0
machen, in dem beide Transports unter `dev.slne.surf.eventbus.*` liegen, eine Netty-Version und
ein gemeinsames Fundament haben — ohne eine einzige Verhaltensänderung.

**Architecture:** Drei Bewegungen, jede mit grünem Build als Abschluss. Erst wird surf-rabbitmq
in sich umbenannt und sein broker-neutrales Fundament nach `surf-eventbus-common` gezogen
(inklusive `surf-circuitbreaker`). Dann zieht surf-redis als Modulbaum herein und wird
gleichbenannt. Es entsteht **kein** neuer Code außer Guard-Tests, die verhindern, dass die
Umbenennung unvollständig bleibt.

**Tech Stack:** Kotlin/JVM, Gradle mit `dev.slne.surf.api.gradle.core`, Kotlin ABI Validation
(`abiValidation`), JUnit 5, Shadow für Relocation, surf-api-core (Logger, Invoker,
`EnvironmentVariables`), amqp-client 5.34.0, Redisson 4.6.1, Netty 4.2.16.

## Global Constraints

- **Gruppe** aller Module: `dev.slne.surf.eventbus`. **Version:** `2.0.0` in `gradle.properties`.
- **`rootProject.name`**: `surf-eventbus`.
- **Packages:** `dev.slne.surf.eventbus.*`. Kein Quelltext darf am Ende `package dev.slne.surf.rabbitmq`,
  `package dev.slne.surf.redis` oder `package dev.slne.surf.circuitbreaker` deklarieren.
- **Env-Variablen:** ausschließlich `SURF_EVENTBUS_*`. Eine gesetzte alte Variable ist ein
  Startfehler.
- **Netty:** genau `4.2.16.Final` für beide Transports. Relocation-Basis
  `dev.slne.surf.eventbus.shaded.` (darf die Zeichenfolge `lib` nicht enthalten),
  Bibliotheks-Basis `dev.slne.surf.eventbus.libs.`.
- **Kein fremdes Repository wird verändert.** `S:\Workspaces\surf-redis` ist in diesem Plan
  **nur Lesequelle** (Stand `master`, v1.10.1). Es wird daraus kopiert, niemals darin editiert.
- **Verhalten bleibt gleich.** Diese Etappe verschiebt und benennt um. Jede Verhaltensänderung
  gehört in Plan 2 oder später.
- **Docker ist auf dieser Maschine nicht erreichbar.** Tests mit `@RequiresDocker` werden nicht
  ausgeführt; ihr Status ist „nicht verifiziert", niemals „bestanden".

---

## File Structure

**Neu:**

| Datei | Verantwortung |
|---|---|
| `surf-eventbus-common/build.gradle.kts` | Modul für das transportfreie Fundament |
| `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCache.kt` | Serializer-Cache, aus beiden Projekten zusammengeführt |
| `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/JavaPluginLoaderProxy.kt` | Velocity-Reflection-Proxy, einmal statt zweimal |
| `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/SerializedPluginDescriptionProxy.kt` | dito |
| `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/circuitbreaker/*.kt` | die vier Dateien aus `surf-circuitbreaker` |
| `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/PackageNamingTest.kt` | Guard: kein Quelltext im Repo trägt ein Altpackage |
| `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/CommonPurityTest.kt` | Guard: `-common` kennt weder RabbitMQ noch Redisson |
| `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/internal/config/RabbitEnvironment.kt` | Env-Variablen als `env`-Delegates |
| `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/config/LegacyEnvironmentGuard.kt` | Startprüfung auf alte Variablennamen |

**Verschoben (Inhalt unverändert, Package neu):**

| Vorher | Nachher |
|---|---|
| `surf-rabbitmq-api/**` | `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/**` |
| `surf-rabbitmq-core/**` | `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/**` |
| `surf-rabbitmq-ksp/**` | `surf-eventbus-ksp/**` |
| `surf-rabbitmq-paper`, `-velocity` | `surf-eventbus-platform/surf-eventbus-platform-paper`, `-velocity` |
| `surf-rabbitmq-test/**` | `surf-eventbus-test/**` |
| `surf-circuitbreaker/**` | in `surf-eventbus-common` aufgelöst |
| surf-redis `surf-redis-api`, `-core` | `surf-eventbus-redis/surf-eventbus-redis-api`, `-core` |
| surf-redis `surf-redis-paper`, `-velocity` | in die bestehenden Plattform-Module hinein |
| surf-redis `surf-redis-standalone` | `surf-eventbus-platform/surf-eventbus-platform-standalone` |

**Gelöscht:**

| Datei | Grund |
|---|---|
| `surf-rabbitmq-api/.../internal/config/RabbitMQEnvironmentConfig.kt` (Teile) | `RabbitMQEnvironmentVariables` und die handgeschriebenen `text`/`integer`/`boolean`-Parser werden durch `env`-Delegates ersetzt |
| `surf-circuitbreaker/src/test/.../NoRabbitMqDependencyTest.kt` | geht in `CommonPurityTest` auf |
| surf-redis `surf-redis-velocity/.../reflection/*.kt` | Duplikat von `-common/platform` |
| surf-redis `surf-redis-core/.../util/KotlinSerializerCache.kt` | Duplikat |

---

## Task 1: Guard-Test und Koordinatenwechsel

**Files:**
- Create: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/PackageNamingTest.kt`
- Modify: `gradle.properties:4`, `settings.gradle.kts:14`, `build.gradle.kts:21-22`
- Test: derselbe neue Test

**Interfaces:**
- Consumes: nichts.
- Produces: `PackageNamingTest` — der Guard, den Task 2 grün machen muss. Er wandert in Task 5
  nach `surf-eventbus-common` und bleibt dauerhaft im Repository.

- [ ] **Step 1: Write the failing test**

Der Test liest die Quellbäume und prüft die `package`-Zeilen. Er scheitert jetzt, weil noch alles
`dev.slne.surf.rabbitmq` heißt.

`surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/PackageNamingTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * Every Kotlin file in the repository declares a package under `dev.slne.surf.eventbus`.
 *
 * A half-finished rename compiles: the old package still exists, imports still resolve, and
 * nothing fails until a consumer wonders why one class sits somewhere else. This test is the
 * only thing that notices.
 */
class PackageNamingTest {

    private val allowedPrefix = "package dev.slne.surf.eventbus"

    @Test
    fun `every source file declares a package under dev slne surf eventbus`() {
        val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
        val offenders = Files.walk(repositoryRoot).asSequence()
            .filter { it.extension == "kt" }
            .filterNot { it.toString().contains("${java.io.File.separator}build${java.io.File.separator}") }
            .mapNotNull { file ->
                val declaration = file.readText()
                    .lineSequence()
                    .firstOrNull { it.startsWith("package ") }
                    ?: return@mapNotNull null

                if (declaration.startsWith(allowedPrefix)) null else "$file declares '$declaration'"
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Files outside dev.slne.surf.eventbus:\n" + offenders.joinToString("\n"))
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*PackageNamingTest*'`
Expected: FAIL, die Liste nennt jede Datei unter `dev.slne.surf.rabbitmq` und
`dev.slne.surf.circuitbreaker`.

- [ ] **Step 3: Koordinaten umstellen**

`gradle.properties` Zeile 4:

```properties
version=2.0.0
```

`settings.gradle.kts` Zeile 14:

```kotlin
rootProject.name = "surf-eventbus"
```

`build.gradle.kts` Zeilen 21-22:

```kotlin
allprojects {
    group = "dev.slne.surf.eventbus"
    version = findProperty("version") as String
}
```

Im selben `build.gradle.kts` die Relocation-Basen (Zeilen um 45-51) und den Opt-in:

```kotlin
        optIn.add("dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ")
```

```kotlin
            val base = "dev.slne.surf.eventbus.libs."
            relocate("com.rabbitmq", base + "com.rabbitmq")

            val nettyBase = "dev.slne.surf.eventbus.shaded." // fails to load if contains "lib"
```

- [ ] **Step 4: Build laufen lassen, Guard scheitert weiterhin**

Run: `./gradlew build -x test`
Expected: FAIL — der Opt-in-String zeigt auf ein Package, das es noch nicht gibt. Das ist
erwartet und wird in Task 2 aufgelöst; wer hier nicht weitermachen will, setzt den Opt-in
vorübergehend zurück. Notiere den Fehler und fahre mit Task 2 fort, ohne zu committen.

- [ ] **Step 5: Nichts committen**

Dieser Task hinterlässt absichtlich einen nicht übersetzbaren Zustand: Koordinaten und Packages
müssen in einem Commit zusammen wandern, sonst ist die Zwischenversion unbrauchbar. Task 2
committet beides gemeinsam.

---

## Task 2: Packages nach `dev.slne.surf.eventbus.rabbitmq` verschieben

**Files:**
- Modify: alle `.kt` unter `surf-rabbitmq-api`, `surf-rabbitmq-core`, `surf-rabbitmq-ksp`,
  `surf-rabbitmq-paper`, `surf-rabbitmq-velocity`, `surf-rabbitmq-test`, `surf-circuitbreaker`
- Modify: `surf-rabbitmq-ksp/src/main/kotlin/dev/slne/surf/rabbitmq/processor/Names.kt` (alle 12
  FQ-Namen)
- Modify: `surf-rabbitmq-api/build.gradle.kts:38-44` (ABI-Filter, buildConfig-Package)
- Modify: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/shared/SharedPackagePurityTest.kt:31`
- Modify: `surf-rabbitmq-api/api/surf-rabbitmq-api.api` (neu erzeugt, nicht handgeschrieben)
- Test: `PackageNamingTest` aus Task 1

**Interfaces:**
- Consumes: `PackageNamingTest` aus Task 1.
- Produces: alle öffentlichen Typen unverändert benannt, aber unter neuen Packages —
  `dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi`,
  `dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService`,
  `dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget`,
  `dev.slne.surf.eventbus.common.circuitbreaker.CircuitBreaker`. Jeder folgende Task und Plan
  benutzt diese Namen.

- [ ] **Step 1: Verzeichnisse verschieben**

`dev/slne/surf/rabbitmq` → `dev/slne/surf/eventbus/rabbitmq`, und der Circuit Breaker nach
`dev/slne/surf/eventbus/common/circuitbreaker`. Für jedes Modul und jedes Source-Set:

```bash
cd S:/Workspaces/surf-rabbitmq
for module in surf-rabbitmq-api surf-rabbitmq-core surf-rabbitmq-ksp surf-rabbitmq-paper surf-rabbitmq-velocity; do
  for set in main test; do
    src="$module/src/$set/kotlin/dev/slne/surf/rabbitmq"
    [ -d "$src" ] || continue
    dst="$module/src/$set/kotlin/dev/slne/surf/eventbus/rabbitmq"
    mkdir -p "$(dirname "$dst")"
    git mv "$src" "$dst"
  done
done
```

Die verschachtelten Testmodule (`surf-rabbitmq-test/*`) tragen dieselbe Struktur; dieselbe
Schleife über `surf-rabbitmq-test/surf-rabbitmq-test-common`,
`surf-rabbitmq-test/surf-rabbitmq-test-paper`, `surf-rabbitmq-test/surf-rabbitmq-test-server`
laufen lassen.

Der Circuit Breaker wandert erst in Task 5 in sein neues Modul; hier bekommt er nur das neue
Package, damit der Guard grün wird:

```bash
for set in main test; do
  src="surf-circuitbreaker/src/$set/kotlin/dev/slne/surf/circuitbreaker"
  [ -d "$src" ] || continue
  dst="surf-circuitbreaker/src/$set/kotlin/dev/slne/surf/eventbus/common/circuitbreaker"
  mkdir -p "$(dirname "$dst")"
  git mv "$src" "$dst"
done
```

- [ ] **Step 2: Package- und Import-Zeilen ersetzen**

Zwei Ersetzungen, in dieser Reihenfolge (die spezifischere zuerst, sonst frisst die erste die
zweite):

```bash
cd S:/Workspaces/surf-rabbitmq
files=$(git ls-files '*.kt' '*.kts')
sed -i 's/dev\.slne\.surf\.circuitbreaker/dev.slne.surf.eventbus.common.circuitbreaker/g' $files
sed -i 's/dev\.slne\.surf\.rabbitmq/dev.slne.surf.eventbus.rabbitmq/g' $files
```

Das trifft auch `Names.kt` im KSP-Modul (alle zwölf `ClassName`- und `_FQ`-Konstanten), den
Opt-in-String, den ABI-Filter `annotatedWith.add("…InternalRabbitMQ")` und das buildConfig-Ziel
`forClass("dev.slne.surf.eventbus.rabbitmq.api.version", "BuildVersion")`.

- [ ] **Step 3: Zwei Stellen nachziehen, die `sed` nicht sieht**

`SharedPackagePurityTest` sucht seinen Quellbaum über einen zusammengesetzten Pfad. Zeile 31:

```kotlin
        val root = Path.of("src", "main", "kotlin", "dev", "slne", "surf", "eventbus", "rabbitmq")
```

Und im Wurzel-`build.gradle.kts` die Bedingung, die Testmodule überspringt (Zeile 27) — der
Modulname ändert sich erst in Task 3, hier bleibt sie:

```kotlin
    if (name.contains("surf-rabbitmq-test")) return@subprojects
```

- [ ] **Step 4: Guard und Build laufen lassen**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*PackageNamingTest*'`
Expected: PASS.

Run: `./gradlew build -x test`
Expected: SUCCESS.

Run: `./gradlew test`
Expected: SUCCESS bis auf Tests mit `@RequiresDocker`, die übersprungen werden.

- [ ] **Step 5: ABI-Dump neu erzeugen**

Run: `./gradlew updateLegacyAbi`
(Der Taskname kommt aus Kotlins `abiValidation`. Wenn er abweicht:
`./gradlew tasks --all | grep -i abi`.)
Expected: `surf-rabbitmq-api/api/surf-rabbitmq-api.api` enthält nur noch
`dev/slne/surf/eventbus/...`-Pfade.

Run: `./gradlew checkLegacyAbi`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor!: move every package under dev.slne.surf.eventbus

Group becomes dev.slne.surf.eventbus, version 2.0.0, rootProject.name
surf-eventbus. PackageNamingTest keeps the rename from staying half done."
```

---

## Task 3: Module verschachteln und umbenennen

**Files:**
- Modify: `settings.gradle.kts` (vollständig neu geschrieben)
- Modify: `build.gradle.kts:27` (Testmodul-Bedingung)
- Modify: jedes `build.gradle.kts`, das `projects.surfRabbitmq*` benutzt
- Test: `PackageNamingTest`, gesamte Suite

**Interfaces:**
- Consumes: die Packages aus Task 2.
- Produces: Gradle-Projektpfade, auf die alle folgenden Tasks verweisen:
  `:surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api`,
  `:surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core`, `:surf-eventbus-ksp`,
  `:surf-eventbus-platform:surf-eventbus-platform-paper`,
  `:surf-eventbus-platform:surf-eventbus-platform-velocity`, `:surf-eventbus-test`.
  Typsichere Accessoren heißen dann `projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi` usw.

- [ ] **Step 1: Verzeichnisse verschieben**

```bash
cd S:/Workspaces/surf-rabbitmq
mkdir -p surf-eventbus-rabbitmq surf-eventbus-platform
git mv surf-rabbitmq-api      surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api
git mv surf-rabbitmq-core     surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core
git mv surf-rabbitmq-ksp      surf-eventbus-ksp
git mv surf-rabbitmq-paper    surf-eventbus-platform/surf-eventbus-platform-paper
git mv surf-rabbitmq-velocity surf-eventbus-platform/surf-eventbus-platform-velocity
git mv surf-rabbitmq-test     surf-eventbus-test
git mv surf-eventbus-test/surf-rabbitmq-test-common surf-eventbus-test/surf-eventbus-test-common
git mv surf-eventbus-test/surf-rabbitmq-test-paper   surf-eventbus-test/surf-eventbus-test-paper
git mv surf-eventbus-test/surf-rabbitmq-test-server  surf-eventbus-test/surf-eventbus-test-server
git mv surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/api/surf-rabbitmq-api.api \
       surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/api/surf-eventbus-rabbitmq-api.api
```

- [ ] **Step 2: `settings.gradle.kts` neu schreiben**

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://reposilite.slne.dev/public/") { name = "public" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.slne.surf.api.gradle.settings") version "+"
}

rootProject.name = "surf-eventbus"

include("surf-eventbus-common")

include("surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api")
include("surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core")

include("surf-eventbus-ksp")

include("surf-eventbus-platform:surf-eventbus-platform-paper")
include("surf-eventbus-platform:surf-eventbus-platform-velocity")

val isCi = providers.environmentVariable("CI").isPresent

if (!isCi) {
    include("surf-eventbus-test")
    include("surf-eventbus-test:surf-eventbus-test-common")
    include("surf-eventbus-test:surf-eventbus-test-paper")
    include("surf-eventbus-test:surf-eventbus-test-server")
}
```

`surf-eventbus-common` ist hier schon eingetragen, obwohl das Modul erst in Task 5 entsteht —
Gradle scheitert an einem `include` ohne Verzeichnis. Also im selben Schritt:

```bash
mkdir -p surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common
mkdir -p surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common
cat > surf-eventbus-common/build.gradle.kts <<'EOF'
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
EOF
```

`surf-circuitbreaker` steht absichtlich **nicht** mehr in `settings.gradle.kts`. Sein Verzeichnis
bleibt bis Task 5 liegen und wird dort aufgelöst; solange ist es kein Gradle-Projekt mehr.

- [ ] **Step 3: Projektverweise nachziehen**

Im Wurzel-`build.gradle.kts` Zeile 27:

```kotlin
    if (name.contains("surf-eventbus-test")) return@subprojects
```

In `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/build.gradle.kts` die beiden `api`-Zeilen:

```kotlin
    api(projects.surfEventbusRabbitmq.surfEventbusRabbitmqApi)
    api(projects.surfEventbusCommon)
```

`projects.surfCircuitbreaker` verschwindet damit — der Circuit Breaker kommt in Task 5 über
`surf-eventbus-common` mit. Bis dahin fehlt er auf dem Classpath, deshalb steht Task 5
unmittelbar danach. Dieselbe Ersetzung in den Plattform- und Testmodulen; welche betroffen sind:

```bash
grep -rln "projects.surfRabbitmq\|projects.surfCircuitbreaker" --include=build.gradle.kts .
```

- [ ] **Step 4: Build laufen lassen**

Run: `./gradlew build -x test`
Expected: FAIL in `surf-eventbus-rabbitmq-core` — `CircuitBreaker` ist unauflösbar, weil sein
Modul nicht mehr eingebunden ist. Erwartet; Task 5 löst es.

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api:build -x test`
Expected: SUCCESS. Das API-Modul kennt den Breaker nicht und beweist, dass die
Modulverschachtelung selbst trägt.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor!: nest modules as surf-eventbus-<area>-<layer>

surf-circuitbreaker leaves the build here and is absorbed into
surf-eventbus-common in the next commit."
```

---

## Task 4: Circuit Breaker und Fundament nach `surf-eventbus-common`

**Files:**
- Create: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/CommonPurityTest.kt`
- Move: `surf-circuitbreaker/src/main/.../circuitbreaker/*.kt` →
  `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/circuitbreaker/`
- Move: `surf-circuitbreaker/src/test/.../*.kt` (ohne `NoRabbitMqDependencyTest`, ohne
  `ScaffoldTest`) → `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/circuitbreaker/`
- Move: `…rabbitmq-core/src/main/.../shared/serialization/KotlinSerializerCache.kt` →
  `surf-eventbus-common/…/common/serialization/`
- Move: `…platform-velocity/src/main/.../reflection/*.kt` → `surf-eventbus-common/…/common/platform/`
- Move: `PackageNamingTest` → `surf-eventbus-common/src/test/…`
- Delete: `surf-circuitbreaker/` samt `NoRabbitMqDependencyTest`, `ScaffoldTest`
- Delete: `…rabbitmq-core/src/test/.../shared/SharedPackagePurityTest.kt`

**Interfaces:**
- Consumes: Modulpfade aus Task 3.
- Produces: `dev.slne.surf.eventbus.common.circuitbreaker.CircuitBreaker`,
  `CircuitBreakerRegistry`, `CircuitOpenException`, `CircuitState`;
  `dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache`;
  `dev.slne.surf.eventbus.common.platform.JavaPluginLoaderProxy`,
  `SerializedPluginDescriptionProxy`. Plan 2 baut `surf-eventbus-bus-api` auf genau diesem Modul
  auf.

- [ ] **Step 1: Purity-Guard schreiben, der scheitert**

`surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/CommonPurityTest.kt`:

```kotlin
package dev.slne.surf.eventbus.common

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * The foundation stays free of both brokers.
 *
 * Successor of surf-rabbitmq's SharedPackagePurityTest and surf-circuitbreaker's
 * NoRabbitMqDependencyTest, widened to Redisson. One broker import here would turn every later
 * module boundary into a suggestion.
 */
class CommonPurityTest {

    private val forbidden = listOf(
        "com.rabbitmq",
        "RabbitPacket",
        "RabbitClient",
        "org.redisson",
        "RedissonClient"
    )

    @Test
    fun `surf-eventbus-common references neither broker`() {
        val root = Path.of("src", "main", "kotlin")

        if (!Files.exists(root)) {
            fail("Expected sources at ${root.toAbsolutePath()}")
        }

        val offenders = Files.walk(root).asSequence()
            .filter { it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                forbidden.firstOrNull { text.contains(it) }?.let { "$file references '$it'" }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("Broker types in surf-eventbus-common:\n" + offenders.joinToString("\n"))
        }
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-common:test --tests '*CommonPurityTest*'`
Expected: FAIL mit „Expected sources at …" — `src/main/kotlin` ist noch leer. Genau die Aussage,
die der nächste Schritt beseitigt.

- [ ] **Step 3: Dateien verschieben**

```bash
cd S:/Workspaces/surf-rabbitmq
common_main=surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common
common_test=surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common
mkdir -p "$common_main/circuitbreaker" "$common_main/serialization" "$common_main/platform"
mkdir -p "$common_test/circuitbreaker"

cb_src=surf-circuitbreaker/src/main/kotlin/dev/slne/surf/eventbus/common/circuitbreaker
git mv "$cb_src"/*.kt "$common_main/circuitbreaker/"

cb_test=surf-circuitbreaker/src/test/kotlin/dev/slne/surf/eventbus/common/circuitbreaker
for keep in CircuitBreakerTest CircuitBreakerRegistryTest CircuitBreakerConcurrencyTest MutableClock MutableClockTest; do
  git mv "$cb_test/$keep.kt" "$common_test/circuitbreaker/"
done
git rm -r surf-circuitbreaker

rabbit_main=surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq
git mv "$rabbit_main/shared/serialization/KotlinSerializerCache.kt" "$common_main/serialization/"

velocity_main=surf-eventbus-platform/surf-eventbus-platform-velocity/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq
git mv "$velocity_main"/reflection/*.kt "$common_main/platform/" 2>/dev/null || \
  echo "check where the Velocity proxies live: grep -rl JavaPluginLoaderProxy --include=*.kt ."

git rm surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/shared/SharedPackagePurityTest.kt
git mv surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/PackageNamingTest.kt "$common_test/"
```

- [ ] **Step 4: Packages der verschobenen Dateien anpassen**

Die `package`-Zeilen der drei nicht-Breaker-Dateien zeigen noch auf ihre alten Orte:

```bash
cd S:/Workspaces/surf-rabbitmq
sed -i 's/^package .*/package dev.slne.surf.eventbus.common.serialization/' \
  surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/serialization/KotlinSerializerCache.kt
sed -i 's/^package .*/package dev.slne.surf.eventbus.common.platform/' \
  surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/platform/*.kt
sed -i 's/^package .*/package dev.slne.surf.eventbus.common/' \
  surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/PackageNamingTest.kt
```

Danach in den Modulen, die diese Typen benutzen, den Import umbiegen:

```bash
files=$(git ls-files '*.kt')
sed -i 's/dev\.slne\.surf\.eventbus\.rabbitmq\.shared\.serialization\.KotlinSerializerCache/dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache/g' $files
sed -i 's/dev\.slne\.surf\.eventbus\.rabbitmq\.reflection\./dev.slne.surf.eventbus.common.platform./g' $files
```

Und `surf-eventbus-rabbitmq-core` sowie die Plattform-Module hängen an das neue Modul:

```kotlin
    api(projects.surfEventbusCommon)
```

`PackageNamingTest` läuft jetzt aus einem anderen Modulverzeichnis; der Repository-Wurzelpfad
bleibt `Path.of("..")` nur für Module auf der ersten Ebene richtig. In der verschobenen Datei:

```kotlin
        val repositoryRoot = Path.of("..").toAbsolutePath().normalize()
```

bleibt korrekt, weil `surf-eventbus-common` auf der ersten Ebene liegt. Für Module der zweiten
Ebene wäre `Path.of("../..")` nötig — deshalb wohnt der Test hier.

- [ ] **Step 5: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-common:test`
Expected: PASS — `CommonPurityTest`, `PackageNamingTest` und die fünf Breaker-Testklassen.

Run: `./gradlew build -x test`
Expected: SUCCESS. Der Breaker ist über `surf-eventbus-common` wieder auf dem Classpath.

Run: `./gradlew test`
Expected: SUCCESS, `@RequiresDocker` übersprungen.

- [ ] **Step 6: ABI-Dumps und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`
Expected: SUCCESS.

```bash
git add -A
git commit -m "refactor!: absorb surf-circuitbreaker and the shared foundation into surf-eventbus-common

CommonPurityTest replaces SharedPackagePurityTest and NoRabbitMqDependencyTest
and additionally forbids Redisson."
```

---

## Task 5: Env-Schicht auf surf-api-cores `env`-Delegates

**Files:**
- Create: `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/internal/config/RabbitEnvironment.kt`
- Create: `surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/config/LegacyEnvironmentGuard.kt`
- Modify: `…rabbitmq-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/internal/config/RabbitMQEnvironmentConfig.kt`
  (`RabbitMQEnvironmentVariables`, `EnvironmentOverrideRabbitMQConfig` und die drei privaten
  Parser entfallen)
- Create: `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-api/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/api/internal/config/RabbitEnvironmentTest.kt`
- Create: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/config/LegacyEnvironmentGuardTest.kt`

**Interfaces:**
- Consumes: `surf-eventbus-common` aus Task 4; `dev.slne.surf.api.core.environment.EnvironmentVariables`
  (`EnvironmentVariables.from(values: Map<String, String>)`, `EnvironmentVariables.system`) und
  `dev.slne.surf.api.core.environment.env`.
- Produces:
  - `RabbitEnvironment.resolve(environment: EnvironmentVariables, fallback: CommonRabbitMQConfig): CommonRabbitMQConfig`
  - `LegacyEnvironmentGuard.check(environment: EnvironmentVariables)` — wirft
    `IllegalStateException`, wenn eine alte Variable gesetzt ist. Plan 2 ruft sie in
    `SurfEventBus.builder(...).build()` auf.

- [ ] **Step 1: Failing test für die Guard schreiben**

`surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/config/LegacyEnvironmentGuardTest.kt`:

```kotlin
package dev.slne.surf.eventbus.common.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class LegacyEnvironmentGuardTest {

    @Test
    fun `a legacy rabbit variable fails the start and names its replacement`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_RABBITMQ_HOST" to "broker.internal"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assertContains(failure.message!!, "SURF_RABBITMQ_HOST")
        assertContains(failure.message!!, "SURF_EVENTBUS_RABBITMQ_HOST")
    }

    @Test
    fun `a legacy redis variable fails the start and names its replacement`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_REDIS_PASSWORD" to "secret"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assertContains(failure.message!!, "SURF_EVENTBUS_REDIS_PASSWORD")
    }

    @Test
    fun `the failure never contains the value of a sensitive variable`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_REDIS_PASSWORD" to "secret"))

        val failure = assertFailsWith<IllegalStateException> {
            LegacyEnvironmentGuard.check(environment)
        }

        assert(!failure.message!!.contains("secret")) { "the guard leaked a password" }
    }

    @Test
    fun `an environment with only new names passes`() {
        val environment = EnvironmentVariables.from(
            mapOf("SURF_EVENTBUS_RABBITMQ_HOST" to "broker.internal")
        )

        LegacyEnvironmentGuard.check(environment)
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-common:test --tests '*LegacyEnvironmentGuardTest*'`
Expected: FAIL, „Unresolved reference: LegacyEnvironmentGuard".

- [ ] **Step 3: Guard implementieren**

`surf-eventbus-common/src/main/kotlin/dev/slne/surf/eventbus/common/config/LegacyEnvironmentGuard.kt`:

```kotlin
package dev.slne.surf.eventbus.common.config

import dev.slne.surf.api.core.environment.EnvironmentVariables

/**
 * Fails the start when a pre-2.0 environment variable is still set.
 *
 * Every variable moved under `SURF_EVENTBUS_*`. Silently ignoring an old name is the worst
 * available outcome: the value falls back to its default — `localhost` for both hosts — and the
 * process comes up looking healthy while pointing at nothing.
 */
object LegacyEnvironmentGuard {

    private const val RABBIT_LEGACY_PREFIX = "SURF_RABBITMQ_"
    private const val RABBIT_PREFIX = "SURF_EVENTBUS_RABBITMQ_"
    private const val REDIS_LEGACY_PREFIX = "SURF_REDIS_"
    private const val REDIS_PREFIX = "SURF_EVENTBUS_REDIS_"

    private val rabbitSuffixes = listOf(
        "HOST", "PORT", "USERNAME", "PASSWORD", "VHOST", "TIMEOUT",
        "REQUEST_TIMEOUT_SECONDS", "PUBLISHER_POOL_SIZE", "SERVER_PREFETCH_COUNT",
        "PERSIST_REQUESTS", "PERSIST_RESPONSES",
        "OUTGOING_REQUEST_CHUNKING_ENABLED", "OUTGOING_RESPONSE_CHUNKING_ENABLED"
    )

    private val redisSuffixes = listOf("HOST", "PORT", "PASSWORD", "CLIENT_NAME")

    /**
     * @throws IllegalStateException naming every legacy variable found and its replacement.
     *   Values are never included — one of them is a password.
     */
    fun check(environment: EnvironmentVariables = EnvironmentVariables.system) {
        val found = buildList {
            for (suffix in rabbitSuffixes) {
                addIfPresent(environment, RABBIT_LEGACY_PREFIX + suffix, RABBIT_PREFIX + suffix)
            }
            for (suffix in redisSuffixes) {
                addIfPresent(environment, REDIS_LEGACY_PREFIX + suffix, REDIS_PREFIX + suffix)
            }
        }

        if (found.isEmpty()) return

        error(
            found.joinToString(
                prefix = "Pre-2.0 environment variables are set but no longer read:\n",
                separator = "\n"
            )
        )
    }

    private fun MutableList<String>.addIfPresent(
        environment: EnvironmentVariables,
        legacyName: String,
        newName: String
    ) {
        // sensitive = true: a present password must not reach the message.
        if (environment.optional(legacyName, sensitive = true) != null) {
            add("  $legacyName is set but no longer read. -> rename it to $newName")
        }
    }
}
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-common:test --tests '*LegacyEnvironmentGuardTest*'`
Expected: PASS, alle vier Tests.

- [ ] **Step 5: Failing test für die neue Env-Schicht schreiben**

`…rabbitmq-api/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/api/internal/config/RabbitEnvironmentTest.kt`:

```kotlin
package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RabbitEnvironmentTest {

    private val yamlFallback = GlobalRabbitMQConfig()

    @Test
    fun `an unset variable falls through to the yaml layer`() {
        val resolved = RabbitEnvironment.resolve(EnvironmentVariables.from(emptyMap()), yamlFallback)

        assertEquals(yamlFallback.getHost(), resolved.getHost())
        assertEquals(yamlFallback.getPort(), resolved.getPort())
    }

    @Test
    fun `a set variable overrides the yaml layer`() {
        val environment = EnvironmentVariables.from(
            mapOf(
                "SURF_EVENTBUS_RABBITMQ_HOST" to "broker.internal",
                "SURF_EVENTBUS_RABBITMQ_PORT" to "5673"
            )
        )

        val resolved = RabbitEnvironment.resolve(environment, yamlFallback)

        assertEquals("broker.internal", resolved.getHost())
        assertEquals(5673, resolved.getPort())
    }

    @Test
    fun `a port outside the valid range fails`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "70000"))

        assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }
    }

    @Test
    fun `a non-numeric port fails`() {
        val environment = EnvironmentVariables.from(mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "nope"))

        assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }
    }

    @Test
    fun `a failure about the password does not contain it`() {
        val environment = EnvironmentVariables.from(
            mapOf("SURF_EVENTBUS_RABBITMQ_PORT" to "nope", "SURF_EVENTBUS_RABBITMQ_PASSWORD" to "hunter2")
        )

        val failure = assertFailsWith<IllegalStateException> {
            RabbitEnvironment.resolve(environment, yamlFallback).getPort()
        }

        assert(!failure.message!!.contains("hunter2"))
    }
}
```

- [ ] **Step 6: Test laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api:test --tests '*RabbitEnvironmentTest*'`
Expected: FAIL, „Unresolved reference: RabbitEnvironment".

- [ ] **Step 7: `RabbitEnvironment` implementieren**

`…/api/internal/config/RabbitEnvironment.kt`:

```kotlin
package dev.slne.surf.eventbus.rabbitmq.api.internal.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.api.core.environment.requireIn
import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ

/**
 * The environment layer of the RabbitMQ configuration.
 *
 * Replaces the hand-rolled `RabbitMQEnvironmentVariables` plus `EnvironmentOverrideRabbitMQConfig`
 * with surf-api-core's resolver, which brings conversion, range validation and — for the
 * password — keeping the value out of failure messages.
 */
@InternalRabbitMQ
object RabbitEnvironment {

    private const val PREFIX = "SURF_EVENTBUS_RABBITMQ_"

    fun resolve(
        environment: EnvironmentVariables,
        fallback: CommonRabbitMQConfig
    ): CommonRabbitMQConfig = object : CommonRabbitMQConfig {

        override fun getHost(): String =
            environment.optional(PREFIX + "HOST") { require("expected a non-blank host") { it.isNotBlank() } }
                ?: fallback.getHost()

        override fun getPort(): Int =
            environment.optionalInt(PREFIX + "PORT") { requireIn(1..65535) } ?: fallback.getPort()

        override fun getUsername(): String =
            environment.optional(PREFIX + "USERNAME") { require("expected a non-blank username") { it.isNotBlank() } }
                ?: fallback.getUsername()

        // Blank is allowed: a broker without authentication is a legitimate local setup.
        override fun getPassword(): String =
            environment.optional(PREFIX + "PASSWORD", sensitive = true) ?: fallback.getPassword()

        override fun getVhost(): String =
            environment.optional(PREFIX + "VHOST") { require("expected a non-blank vhost") { it.isNotBlank() } }
                ?: fallback.getVhost()

        override fun getTimeout(): Int =
            environment.optionalInt(PREFIX + "TIMEOUT") { require("expected a positive timeout") { it > 0 } }
                ?: fallback.getTimeout()

        override fun getRequestTimeoutSeconds(): Int =
            environment.optionalInt(PREFIX + "REQUEST_TIMEOUT_SECONDS") { require("expected a positive timeout") { it > 0 } }
                ?: fallback.getRequestTimeoutSeconds()

        override fun getPublisherPoolSize(): Int =
            environment.optionalInt(PREFIX + "PUBLISHER_POOL_SIZE") { require("expected a positive size") { it > 0 } }
                ?: fallback.getPublisherPoolSize()

        override fun getServerPrefetchCount(): Int =
            environment.optionalInt(PREFIX + "SERVER_PREFETCH_COUNT") { requireIn(0..Short.MAX_VALUE.toInt()) }
                ?: fallback.getServerPrefetchCount()

        override fun isPersistRequests(): Boolean =
            environment.optionalBoolean(PREFIX + "PERSIST_REQUESTS") ?: fallback.isPersistRequests()

        override fun isPersistResponses(): Boolean =
            environment.optionalBoolean(PREFIX + "PERSIST_RESPONSES") ?: fallback.isPersistResponses()

        override fun isOutgoingRequestChunkingEnabled(): Boolean =
            environment.optionalBoolean(PREFIX + "OUTGOING_REQUEST_CHUNKING_ENABLED")
                ?: fallback.isOutgoingRequestChunkingEnabled()

        override fun isOutgoingResponseChunkingEnabled(): Boolean =
            environment.optionalBoolean(PREFIX + "OUTGOING_RESPONSE_CHUNKING_ENABLED")
                ?: fallback.isOutgoingResponseChunkingEnabled()
    }
}
```

Falls `optionalInt` oder `optionalBoolean` in der vorliegenden surf-api-core-Version anders
heißen: `grep -n "fun optional" $(git -C S:/Workspaces/surf-api ls-files '*EnvironmentVariables.kt')`
zeigt die verfügbaren Überladungen. Die generische Form `optional(name) { … }` mit Konverter
existiert in jedem Fall.

- [ ] **Step 8: Alte Env-Schicht entfernen und verdrahten**

In `RabbitMQEnvironmentConfig.kt` entfallen `RabbitMQEnvironmentVariables`,
`EnvironmentOverrideRabbitMQConfig`, `EnvironmentVariableLookup`,
`ProcessEnvironmentVariableLookup` und die privaten `text`/`positiveInteger`/`integer`/`boolean`.
Übrig bleibt die Auflösung, die jetzt `RabbitEnvironment` benutzt:

```kotlin
@InternalRabbitMQ
fun resolveRabbitMQConfig(
    global: GlobalRabbitMQConfig,
    plugin: PluginRabbitMQConfig? = null,
    environment: EnvironmentVariables = EnvironmentVariables.system,
): CommonRabbitMQConfig {
    val yamlConfig = if (plugin == null) global else PluginWithGlobalFallback(plugin, global)
    return RabbitEnvironment.resolve(environment, yamlConfig)
}
```

`PluginWithGlobalFallback` bleibt unverändert.

- [ ] **Step 9: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api:test`
Expected: PASS, inklusive `RabbitEnvironmentTest`, `SurfRabbitApiBuilderTest`, `RabbitIdentityTest`.

Run: `./gradlew test`
Expected: SUCCESS.

- [ ] **Step 10: ABI-Dump und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "refactor!: rename rabbit env vars to SURF_EVENTBUS_RABBITMQ_* via surf-api-core

Deletes ~150 lines of hand-rolled parsing, gains range validation and
sensitive-value masking. LegacyEnvironmentGuard turns a forgotten old
variable into a start failure instead of a silent localhost fallback."
```

---

## Task 6: surf-redis inventarisieren und hereinziehen

**Files:**
- Create: `surf-eventbus-redis/surf-eventbus-redis-api/**` (kopiert aus surf-redis `surf-redis-api`)
- Create: `surf-eventbus-redis/surf-eventbus-redis-core/**` (kopiert aus `surf-redis-core`)
- Create: `surf-eventbus-platform/surf-eventbus-platform-standalone/**` (aus `surf-redis-standalone`)
- Modify: `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`
- Test: die sieben mitkommenden Redis-Testklassen

**Interfaces:**
- Consumes: Modul- und Package-Konventionen aus Task 3 und 4.
- Produces: `dev.slne.surf.eventbus.redis.RedisApi`, `…redis.sync.*`, `…redis.cache.*`,
  `…redis.codec.*` (inklusive `RedisCodec`, `AbstractCodec`, `JsonKotlinCodec`),
  `…redis.event.*` (noch die alte Event-API — Plan 3 löst sie ab),
  `…redis.request.*` (noch `RedisRequest` — Plan 3 ersetzt sie durch `@QueryService`),
  `dev.slne.surf.eventbus.redis.TransportInfo`.

- [ ] **Step 1: Bestandsaufnahme schreiben**

Bevor eine Datei kopiert wird, festhalten, was tatsächlich da ist — der Entwurf war gegen 1.5.0
geschrieben und irrte an vier Stellen. In `docs/superpowers/notes/2026-07-31-surf-redis-inventory.md`:

```bash
cd S:/Workspaces/surf-redis
git log --oneline -1
find . -name "*.kt" | grep -v "/build/" | grep -v "/test/" | wc -l
find . -name "*.kt" -path "*/test/*" | grep -v "/build/" | sort
grep -n "redisson\|netty" gradle/libs.versions.toml
grep -rn "@Blocking" surf-redis-api/src/main/kotlin/dev/slne/surf/redis/RedisApi.kt
```

Die Ausgaben in die Notiz übernehmen. Erwartet: v1.10.1, sieben Testklassen, Redisson 4.6.1,
Netty 4.2.15.Final, vier `@Blocking`-Stellen.

- [ ] **Step 2: Module kopieren**

surf-redis wird **gelesen, nicht verändert**:

```bash
cd S:/Workspaces/surf-rabbitmq
src=S:/Workspaces/surf-redis
mkdir -p surf-eventbus-redis
cp -r "$src/surf-redis-api"  surf-eventbus-redis/surf-eventbus-redis-api
cp -r "$src/surf-redis-core" surf-eventbus-redis/surf-eventbus-redis-core
cp -r "$src/surf-redis-standalone" surf-eventbus-platform/surf-eventbus-platform-standalone
find surf-eventbus-redis surf-eventbus-platform/surf-eventbus-platform-standalone \
  -name build -type d -prune -exec rm -rf {} +
find surf-eventbus-redis -name "*.api" -delete
```

Die Paper- und Velocity-Module von surf-redis werden **nicht** kopiert: ihre Aufgabe (eine
Instanz bereitstellen) übernehmen die bestehenden Plattform-Module in Plan 4. Ihre einzigen
eigenen Dateien sind die beiden Reflection-Proxies, die in Task 4 schon nach
`surf-eventbus-common` gezogen sind.

- [ ] **Step 3: Verzeichnisse auf das neue Package heben**

```bash
cd S:/Workspaces/surf-rabbitmq
for module in surf-eventbus-redis/surf-eventbus-redis-api surf-eventbus-redis/surf-eventbus-redis-core surf-eventbus-platform/surf-eventbus-platform-standalone; do
  for set in main test jmh; do
    src="$module/src/$set/kotlin/dev/slne/surf/redis"
    [ -d "$src" ] || continue
    dst="$module/src/$set/kotlin/dev/slne/surf/eventbus/redis"
    mkdir -p "$(dirname "$dst")"
    mv "$src" "$dst"
  done
done
files=$(find surf-eventbus-redis surf-eventbus-platform/surf-eventbus-platform-standalone -name "*.kt" -o -name "*.kts")
sed -i 's/dev\.slne\.surf\.redis/dev.slne.surf.eventbus.redis/g' $files
```

- [ ] **Step 4: Duplikate löschen und Env-Namen umstellen**

```bash
cd S:/Workspaces/surf-rabbitmq
redis_core=surf-eventbus-redis/surf-eventbus-redis-core/src/main/kotlin/dev/slne/surf/eventbus/redis
rm "$redis_core/util/KotlinSerializerCache.kt"
grep -rl "KotlinSerializerCache" --include=*.kt surf-eventbus-redis | while read -r f; do
  sed -i 's/dev\.slne\.surf\.eventbus\.redis\.util\.KotlinSerializerCache/dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache/' "$f"
done
```

`RedisEnvironment` benutzt schon `env`-Delegates, braucht also nur neue Namen. Die
Delegate-Form leitet den Variablennamen vom Property-Namen ab — mit `.named(...)` bleibt der
Kotlin-Name lesbar und der Variablenname explizit:

`$redis_core/config/RedisEnvironment.kt`:

```kotlin
package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.environment.env

object RedisEnvironment {
    val host by env.optional().named("SURF_EVENTBUS_REDIS_HOST")
    val port by env.optionalInt {
        require("Port must be between 0 and 65535") { it in 0..65535 }
    }.named("SURF_EVENTBUS_REDIS_PORT")
    val password by env.optional(sensitive = true).named("SURF_EVENTBUS_REDIS_PASSWORD")
    val clientName by env.optional().named("SURF_EVENTBUS_REDIS_CLIENT_NAME")
}
```

Und `RedisConfig.overwriteFromEnv()` entsprechend:

```kotlin
    fun overwriteFromEnv() = copy(
        host = RedisEnvironment.host ?: host,
        port = RedisEnvironment.port ?: port,
        password = RedisEnvironment.password ?: password,
        clientName = RedisEnvironment.clientName ?: clientName
    )
```

- [ ] **Step 5: Build-Dateien und `settings.gradle.kts` ergänzen**

`settings.gradle.kts` bekommt drei `include`:

```kotlin
include("surf-eventbus-redis:surf-eventbus-redis-api")
include("surf-eventbus-redis:surf-eventbus-redis-core")
include("surf-eventbus-platform:surf-eventbus-platform-standalone")
```

In den kopierten `build.gradle.kts` die Projektverweise umschreiben:
`projects.surfRedisApi` → `projects.surfEventbusRedis.surfEventbusRedisApi`,
`projects.surfRedisCore` → `projects.surfEventbusRedis.surfEventbusRedisCore`. Zusätzlich hängt
jedes der beiden Module an `projects.surfEventbusCommon`.

Die Redisson- und Netty-Einträge aus surf-redis' `gradle/libs.versions.toml` in das des
Zielprojekts übernehmen, Netty auf den bestehenden Wert:

```toml
redisson = "4.6.1"
netty = "4.2.16.Final" # Rabbit's pin wins; Redisson tests against 4.2.15
```

- [ ] **Step 6: Build und Tests laufen lassen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-api:build -x test`
Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:build -x test`
Expected: SUCCESS. Bei unauflösbaren Referenzen zuerst prüfen, ob eine Datei aus
`surf-redis-paper`/`-velocity` fehlt, die doch gebraucht wird — dann gehört sie nach
`surf-eventbus-common` und nicht in ein Plattform-Modul.

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:test :surf-eventbus-redis:surf-eventbus-redis-api:test`
Expected: PASS, alle sieben mitgekommenen Testklassen inklusive
`EventCodecRegistryLincheckTest`.

Run: `./gradlew :surf-eventbus-common:test --tests '*PackageNamingTest*'`
Expected: PASS — beweist, dass kein kopiertes File ein Altpackage behalten hat.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat!: absorb surf-redis 1.10.1 as surf-eventbus-redis

Sources copied from surf-redis master (v1.10.1) and renamed to
dev.slne.surf.eventbus.redis. Behaviour unchanged; the seven existing test
classes come along. Env variables renamed to SURF_EVENTBUS_REDIS_*.
Duplicated KotlinSerializerCache and the Velocity reflection proxies now
come from surf-eventbus-common."
```

---

## Task 7: Shading, Netty und ABI zusammenführen

**Files:**
- Modify: `build.gradle.kts` (Relocation-Liste und `META-INF/native`-Mangling, einmal für alles)
- Modify: `surf-eventbus-redis/*/build.gradle.kts` (eigene Shadow-Blöcke entfallen)
- Create: ABI-Dumps für die neuen Module
- Create: `surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/RelocationBaseTest.kt`

**Interfaces:**
- Consumes: Module aus Task 6.
- Produces: eine Relocation-Basis `dev.slne.surf.eventbus.shaded.` für Netty und
  `dev.slne.surf.eventbus.libs.` für alles andere; ein `META-INF/native`-Mangling-Block im
  Wurzel-Build. Plan 4 baut die Plattform-Jars darauf.

- [ ] **Step 1: Failing test für die Relocation-Basis schreiben**

Der Netty-Mangling-Block bricht still, wenn die Basis die Zeichenfolge `lib` enthält — der
Kommentar im Build sagt es, aber nichts prüft es.

`surf-eventbus-common/src/test/kotlin/dev/slne/surf/eventbus/common/RelocationBaseTest.kt`:

```kotlin
package dev.slne.surf.eventbus.common

import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Netty relocation base must not contain "lib" and must exist exactly once.
 *
 * A base containing "lib" makes Netty's native loader fail to find its mangled resources, and
 * the failure surfaces at runtime on a real server rather than in the build. Two bases mean two
 * Netty copies in one jar.
 */
class RelocationBaseTest {

    private val rootBuildFile = Path.of("..", "build.gradle.kts")

    @Test
    fun `the netty relocation base is declared once and contains no lib`() {
        val text = rootBuildFile.readText()
        val declarations = Regex("""val nettyBase = "([^"]+)"""").findAll(text).toList()

        assertEquals(1, declarations.size, "expected exactly one Netty relocation base")

        val base = declarations.single().groupValues[1]
        assertTrue(base.startsWith("dev.slne.surf.eventbus.shaded."), "unexpected base: $base")
        assertFalse(base.contains("lib"), "a base containing 'lib' breaks Netty's native loader")
    }

    @Test
    fun `no module declares its own netty relocation`() {
        val offenders = Path.of("..").toFile()
            .walkTopDown()
            .filter { it.name == "build.gradle.kts" && it.parentFile.name != "surf-eventbus" }
            .filterNot { it.path.contains("${java.io.File.separator}build${java.io.File.separator}") }
            .filter { it.readText().contains("relocate(\"io.netty\"") }
            .map { it.path }
            .toList()

        assertTrue(offenders.isEmpty(), "Netty relocated outside the root build: $offenders")
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-common:test --tests '*RelocationBaseTest*'`
Expected: FAIL im zweiten Test — die aus surf-redis kopierten Builds relocaten Netty selbst.

- [ ] **Step 3: Relocation zusammenführen**

Im Wurzel-`build.gradle.kts` die Liste aus surf-redis ergänzen, damit sie einmal vollständig
existiert:

```kotlin
            val base = "dev.slne.surf.eventbus.libs."
            relocate("com.rabbitmq", base + "com.rabbitmq")
            relocate("org.redisson", base + "redisson")
            relocate("com.esotericsoftware", base + "kryo")
            relocate("io.reactivex", base + "reactivex")
            relocate("javax.cache", base + "javax.cache")
            relocate("jodd", base + "jodd")
            relocate("net.bytebuddy", base + "bytebuddy")
            relocate("org.objenesis", base + "objenesis")
            relocate("org.yaml", base + "yaml")
```

Aus `surf-eventbus-redis/*/build.gradle.kts` und
`surf-eventbus-platform/surf-eventbus-platform-standalone/build.gradle.kts` die eigenen
`ShadowJar`-Blöcke und `relocate`-Aufrufe entfernen. Der `META-INF/native`-Block im Wurzel-Build
bleibt, wie er ist — er ist bereits generisch über `mangledPrefix`.

- [ ] **Step 4: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-common:test --tests '*RelocationBaseTest*'`
Expected: PASS.

Run: `./gradlew build -x test`
Expected: SUCCESS.

Run: `./gradlew test`
Expected: SUCCESS, `@RequiresDocker` übersprungen.

- [ ] **Step 5: Netty-Konvergenz belegen**

Run: `./gradlew :surf-eventbus-redis:surf-eventbus-redis-core:dependencies --configuration runtimeClasspath | grep io.netty | sort -u`
Expected: jede Zeile endet auf `4.2.16.Final`; keine Zeile zeigt `4.2.15.Final` oder ein
`-> 4.2.16.Final (*)` aus einem anderen Pin.

Das in die Inventar-Notiz aus Task 6 Step 1 nachtragen, mit einem Satz dazu, dass Redisson 4.6.1
damit gegen eine Netty-Version läuft, die es selbst nicht testet.

- [ ] **Step 6: ABI-Dumps erzeugen**

Run: `./gradlew updateLegacyAbi`
Expected: neue Dumps unter `surf-eventbus-redis/*/api/` und
`surf-eventbus-common/api/surf-eventbus-common.api`.

Run: `./gradlew checkLegacyAbi`
Expected: SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "build!: one relocation base, one Netty version, fresh ABI dumps

Netty converges on 4.2.16 (Rabbit's pin). RelocationBaseTest guards the
'lib' trap in the Netty base and keeps the relocation in one place."
```

---

## Task 8: Etappenabschluss belegen

**Files:**
- Create: `docs/superpowers/notes/2026-07-31-fundament-verification.md`

**Interfaces:**
- Consumes: alles Vorherige.
- Produces: die Belegdatei, auf die Plan 2 sich beim Start beruft.

- [ ] **Step 1: Vollständigen Build laufen lassen und protokollieren**

```bash
cd S:/Workspaces/surf-rabbitmq
./gradlew clean build 2>&1 | tail -40
./gradlew test 2>&1 | grep -E "tests? (completed|passed|failed|skipped)|BUILD"
./gradlew checkLegacyAbi 2>&1 | tail -5
./gradlew :surf-eventbus-common:test --tests '*PackageNamingTest*' --tests '*CommonPurityTest*' --tests '*RelocationBaseTest*' 2>&1 | tail -10
```

- [ ] **Step 2: Notiz schreiben**

Die Datei hält genau drei Dinge fest, jedes mit der Ausgabe darunter:

1. Was grün ist — Modulliste und Testzahlen aus Step 1.
2. Was **nicht ausgeführt** wurde: jeder Test mit `@RequiresDocker`, weil auf dieser Maschine
   kein Docker-Daemon erreichbar ist. Namentlich auflisten:
   `./gradlew test --dry-run` hilft nicht; stattdessen
   `grep -rl "@RequiresDocker" --include=*.kt . | grep -v /build/`.
   Diese Tests gelten als „nicht verifiziert", nicht als bestanden.
3. Was sich am Verhalten geändert hat: **nichts**, außer den Env-Variablennamen und der
   Startprüfung. Wenn doch etwas aufgefallen ist, hier hin — nicht in einen Commit-Text.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/notes/2026-07-31-fundament-verification.md
git commit -m "docs: record what stage 1 verified and what stayed unverified"
```

---

## Self-Review

**Spec-Abdeckung dieser Etappen (Spec-Abschnitt → Task):**

| Spec | Task |
|---|---|
| Etappe 1: Umbenennen und umhängen | 1, 2, 3 |
| Etappe 1: Env-Namen und Startprüfung | 5 |
| Etappe 2: `surf-eventbus-common`, CircuitBreaker eingliedern, Purity-Test | 4 |
| Etappe 3: Bestandsaufnahme v1.10.1 | 6 Step 1 |
| Etappe 3: Module hereinziehen, Packages, Env | 6 |
| Etappe 3: Netty konvergieren, Shading, ABI | 7 |
| „Docker nicht erreichbar → nicht verifiziert" | 8 |

**Offen gelassen und bewusst nicht hier:** die Aggregat-Module `surf-eventbus-api` /
`surf-eventbus-core` (Plan 4, weil sie erst etwas zu aggregieren brauchen), das
Zusammenführen der Plattform-Module (Plan 4), jede Verhaltensänderung an Events, RPC, Queries
und Audit (Pläne 2 bis 4).

**Typkonsistenz:** `LegacyEnvironmentGuard.check(EnvironmentVariables)` und
`RabbitEnvironment.resolve(EnvironmentVariables, CommonRabbitMQConfig)` sind die beiden
Signaturen, auf die Plan 2 zugreift; beide sind in Task 5 mit Tests festgelegt.
`CommonRabbitMQConfig` behält alle 13 Getter unverändert — die Env-Umstellung ändert die
Schnittstelle nicht, nur ihre Quelle.
