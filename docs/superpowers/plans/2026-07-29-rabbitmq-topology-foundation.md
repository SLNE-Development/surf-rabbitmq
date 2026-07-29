# RabbitMQ Topology Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Do NOT use subagent-driven-development.** The repository owner's global instructions forbid delegating work to subagents.

**Goal:** Replace default-exchange routing with a declared exchange topology, merge the six client/server modules into two, and expose one `SurfRabbitApi` that can address any service over a single connection.

**Architecture:** A `direct` exchange `surf.rpc` routes by target name; queue and exchange names come from one `RabbitTopology` object so naming can be unit-tested without a broker. A process declares only the queues it consumes, which removes the current cross-declaration conflict. `ClientRabbitMQApi` and `ServerRabbitMQApi` collapse into `SurfRabbitApi`; capability follows from which register methods a process calls.

**Tech Stack:** Kotlin (JVM toolchain 25), amqp-client 5.34.0, kotlinx.serialization CBOR, JUnit 5, Testcontainers.

## Global Constraints

- JVM toolchain is **25**, set by `dev.slne.surf.api.gradle.core`. Do not override.
- `kotlin.stdlib.default.dependency=false` in `gradle.properties`. Do not add stdlib manually.
- The Gradle plugin does **not** configure `useJUnitPlatform()`. Set it explicitly per module.
- Root `build.gradle.kts` skips subprojects whose name contains `surf-rabbitmq-test` when
  applying the `InternalRabbitMQ` opt-in and shadow config. Keep that guard intact.
- Exchange names, copied verbatim from the spec: `surf.rpc`, `surf.events`, `surf.dlx`,
  `surf.unroutable`.
- `surf.rpc` is declared with `alternate-exchange` = `surf.unroutable`.
- Service queue arguments, verbatim: `x-queue-type: quorum`,
  `x-dead-letter-exchange: surf.dlx`, `x-max-length-bytes: 268435456`,
  `x-overflow: reject-publish`.
- **A process declares only queues it consumes.** Never declare another service's queue.
- Breaking changes are permitted. Wire compatibility with 1.6.x is explicitly **not** a goal.
- Commit after every task.

## Docker requirement

Tasks 1, 4, 8 and 9 contain integration tests needing a Docker daemon. At the time of writing
no daemon is reachable on the development machine.

If Docker is unavailable: write the tests, run `./gradlew test -PskipIntegration`, and record
in the commit body that integration tests are **unverified**. Do not report them as passing.
Task 1 sets up that flag.

## File Structure

| File | Responsibility |
|---|---|
| `surf-rabbitmq-core/.../topology/RabbitTopology.kt` | All exchange, queue and routing-key names |
| `surf-rabbitmq-core/.../topology/RabbitTopologyDeclarer.kt` | Declaring exchanges, queues, bindings |
| `surf-rabbitmq-core/.../topology/QueueArguments.kt` | Argument maps for each queue kind |
| `surf-rabbitmq-api/.../SurfRabbitApi.kt` | The single public API |
| `surf-rabbitmq-api/.../SurfRabbitApiBuilder.kt` | Construction |
| `surf-rabbitmq-api/.../target/RabbitTarget.kt` | `ServiceTarget` / `InstanceTarget` |
| `surf-rabbitmq-api/.../identity/RabbitIdentity.kt` | `serviceName` + `instanceId` |
| `surf-rabbitmq-core/.../connection/RabbitConnectionImpl.kt` | Merged client/server connection |
| `surf-rabbitmq-core/src/test/.../RabbitBrokerExtension.kt` | Shared Testcontainers broker |
| `surf-rabbitmq-core/src/test/.../*Test.kt` | Unit and integration tests |

---

### Task 1: Test infrastructure for both unit and integration tests

The repository has no tests at all. Nothing about the test setup can be assumed to work, and the integration tests must be skippable so the suite stays useful without Docker.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `surf-rabbitmq-common/build.gradle.kts`
- Create: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/ScaffoldTest.kt`
- Create: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/RequiresDocker.kt`
- Create: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/RabbitBrokerExtension.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `@RequiresDocker` — JUnit tag plus skip condition
  - `RabbitBrokerExtension` — starts one shared broker container, exposes
    `fun connectionFactory(): ConnectionFactory` and `fun amqpUrl(): String`
  - Gradle property `-PskipIntegration` excluding the `integration` tag

- [ ] **Step 1: Add test dependency versions**

In `gradle/libs.versions.toml` add to `[versions]` (skip any line already added by Plan 1):

```toml
junit = "5.11.4"
coroutines = "1.10.2"
testcontainers = "1.21.3"
```

Add to `[libraries]`:

```toml
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
testcontainers-core = { module = "org.testcontainers:testcontainers" }
testcontainers-junit = { module = "org.testcontainers:junit-jupiter" }
testcontainers-rabbitmq = { module = "org.testcontainers:rabbitmq" }
```

- [ ] **Step 2: Wire the test platform into the module**

Append to `surf-rabbitmq-common/build.gradle.kts`, before the `publishing` block:

```kotlin
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
    testImplementation(kotlin("test"))

    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.rabbitmq)
}

tasks.test {
    useJUnitPlatform {
        // Integration tests need a Docker daemon. Excluding the tag keeps the
        // remaining suite usable on machines without one.
        if (providers.gradleProperty("skipIntegration").isPresent) {
            excludeTags("integration")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

- [ ] **Step 3: Write a scaffold test that must fail**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/ScaffoldTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test
    fun `test infrastructure runs and can fail`() {
        assertEquals(1, 2, "intentional failure proving tests execute")
    }
}
```

- [ ] **Step 4: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-common:test`
Expected: build fails naming `ScaffoldTest`.

`NO-SOURCE`, "no tests found", or a green build all mean the wiring is broken. Fix before continuing.

- [ ] **Step 5: Make it pass**

Change the assertion to `assertEquals(1, 1, "test infrastructure works")`.

Run: `./gradlew :surf-rabbitmq-common:test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Add the Docker tag and skip condition**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/RequiresDocker.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.testing

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.DockerClientFactory

/**
 * Marks a test that needs a running Docker daemon.
 *
 * Tagged `integration` so `./gradlew test -PskipIntegration` can exclude it, and guarded by
 * [DockerAvailableCondition] so an unreachable daemon skips the test instead of failing it
 * with an unrelated container error.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Tag("integration")
@ExtendWith(DockerAvailableCondition::class)
annotation class RequiresDocker

class DockerAvailableCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        return if (dockerAvailable) {
            ConditionEvaluationResult.enabled("Docker is available")
        } else {
            ConditionEvaluationResult.disabled(
                "Docker is not available - integration test skipped, NOT verified"
            )
        }
    }

    private companion object {
        // Probing is slow, so do it once per JVM.
        val dockerAvailable: Boolean by lazy {
            runCatching { DockerClientFactory.instance().isDockerAvailable }
                .getOrDefault(false)
        }
    }
}
```

- [ ] **Step 7: Add the shared broker**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/RabbitBrokerExtension.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.testing

import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.utility.DockerImageName

/**
 * One RabbitMQ broker shared by every integration test in the JVM.
 *
 * Starting a container per test class costs several seconds each. Tests isolate themselves by
 * using unique service names instead, which is both faster and closer to production, where
 * many services share one broker.
 */
object RabbitBrokerExtension {

    private val container: RabbitMQContainer by lazy {
        RabbitMQContainer(DockerImageName.parse("rabbitmq:4.1-management"))
            .withReuse(false)
            .also { it.start() }
    }

    fun connectionFactory(): ConnectionFactory = ConnectionFactory().apply {
        host = container.host
        port = container.amqpPort
        username = container.adminUsername
        password = container.adminPassword
        virtualHost = "/"
    }

    fun newConnection(name: String): Connection = connectionFactory().newConnection(name)

    fun amqpUrl(): String = container.amqpUrl

    /** Management API base URL, for assertions that need queue arguments. */
    fun managementUrl(): String = container.httpUrl

    fun adminUsername(): String = container.adminUsername
    fun adminPassword(): String = container.adminPassword

    /** Unique per test to keep parallel tests from colliding on names. */
    fun uniqueServiceName(prefix: String): String =
        "$prefix-${System.nanoTime().toString(16)}"
}
```

- [ ] **Step 8: Prove the broker starts**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/RabbitBrokerExtensionTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.testing

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@RequiresDocker
class RabbitBrokerExtensionTest {

    @Test
    fun `a connection to the test broker can be opened`() {
        RabbitBrokerExtension.newConnection("scaffold").use { connection ->
            assertTrue(connection.isOpen)

            connection.createChannel().use { channel ->
                assertTrue(channel.isOpen)
            }
        }
    }
}
```

- [ ] **Step 9: Run it both ways**

Run: `./gradlew :surf-rabbitmq-common:test -PskipIntegration`
Expected: `BUILD SUCCESSFUL`, the broker test is not executed.

Run: `./gradlew :surf-rabbitmq-common:test`
Expected with Docker: `BUILD SUCCESSFUL`, broker test passes.
Expected without Docker: `BUILD SUCCESSFUL`, broker test reported as **skipped** — never failed.

- [ ] **Step 10: Commit**

```bash
git add gradle/libs.versions.toml surf-rabbitmq-common/
git commit -m "test: add JUnit 5 and Testcontainers infrastructure

Integration tests are tagged 'integration' and skipped when no Docker
daemon is reachable, so the unit suite stays usable without Docker."
```

---

### Task 2: Topology naming

Every queue, exchange and routing key name in one place, fully unit-testable without a broker. Getting names wrong is the single easiest way to lose messages silently, and it is also the easiest thing to test.

**Files:**
- Create: `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopology.kt`
- Test: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyTest.kt`

**Interfaces:**
- Consumes: test infrastructure from Task 1
- Produces:
  ```kotlin
  object RabbitTopology {
      const val RPC_EXCHANGE = "surf.rpc"
      const val EVENTS_EXCHANGE = "surf.events"
      const val DLX_EXCHANGE = "surf.dlx"
      const val UNROUTABLE_EXCHANGE = "surf.unroutable"
      const val UNROUTABLE_QUEUE = "surf.unroutable"

      fun serviceQueue(serviceName: String): String
      fun instanceQueue(instanceId: String): String
      fun replyQueue(instanceId: String): String
      fun sharedEventQueue(serviceName: String): String
      fun instanceEventQueue(instanceId: String): String
      fun deadLetterQueue(serviceName: String): String
      fun sanitize(value: String): String
  }
  ```

**Naming note:** the spec sketched broadcast and shared event queues as `surf.events.<instanceId>`
and `surf.events.<service>`. Those two namespaces can collide. This plan uses
`surf.events.instance.<instanceId>` and `surf.events.shared.<service>` instead. Update the spec
in Task 10.

- [ ] **Step 1: Write the failing test**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RabbitTopologyTest {

    @Test
    fun `exchange names match the specification`() {
        assertEquals("surf.rpc", RabbitTopology.RPC_EXCHANGE)
        assertEquals("surf.events", RabbitTopology.EVENTS_EXCHANGE)
        assertEquals("surf.dlx", RabbitTopology.DLX_EXCHANGE)
        assertEquals("surf.unroutable", RabbitTopology.UNROUTABLE_EXCHANGE)
    }

    @Test
    fun `queue names are built from their prefix`() {
        assertEquals("surf.service.surf-factions", RabbitTopology.serviceQueue("surf-factions"))
        assertEquals("surf.instance.lobby-3", RabbitTopology.instanceQueue("lobby-3"))
        assertEquals("surf.reply.lobby-3", RabbitTopology.replyQueue("lobby-3"))
        assertEquals("surf.dlq.surf-factions", RabbitTopology.deadLetterQueue("surf-factions"))
    }

    @Test
    fun `shared and instance event queues live in separate namespaces`() {
        // Without the extra segment, a service named "x" and an instance named "x"
        // would fight over the same queue.
        assertEquals(
            "surf.events.shared.surf-factions",
            RabbitTopology.sharedEventQueue("surf-factions")
        )
        assertEquals(
            "surf.events.instance.surf-factions",
            RabbitTopology.instanceEventQueue("surf-factions")
        )
        assertTrue(
            RabbitTopology.sharedEventQueue("x") != RabbitTopology.instanceEventQueue("x")
        )
    }

    @Test
    fun `characters illegal in AMQP names are replaced`() {
        assertEquals("surf.service.my_service", RabbitTopology.serviceQueue("my service"))
        assertEquals("surf.service.a_b", RabbitTopology.serviceQueue("a/b"))
        assertEquals("surf.service.a_b", RabbitTopology.serviceQueue("a#b"))
    }

    @Test
    fun `legal characters survive sanitising`() {
        assertEquals("a-b_c.d1", RabbitTopology.sanitize("a-b_c.d1"))
    }

    @Test
    fun `sanitising is stable`() {
        val once = RabbitTopology.sanitize("my service")
        assertEquals(once, RabbitTopology.sanitize(once))
    }

    @Test
    fun `blank names are rejected`() {
        val thrown = runCatching { RabbitTopology.serviceQueue("  ") }.exceptionOrNull()
        assertTrue(
            thrown is IllegalArgumentException,
            "a blank service name would produce the queue 'surf.service.' and silently " +
                    "collide with every other blank-named service"
        )
    }

    @Test
    fun `names stay within the AMQP length limit`() {
        // AMQP caps queue names at 255 bytes; the prefix must not push a long
        // service name past it unnoticed.
        val long = "s".repeat(300)
        val thrown = runCatching { RabbitTopology.serviceQueue(long) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "over-long names must be rejected")
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-common:test --tests '*RabbitTopologyTest*'`
Expected: `Unresolved reference: RabbitTopology`.

- [ ] **Step 3: Implement it**

Create `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopology.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

/**
 * Every exchange, queue and routing-key name used by the library.
 *
 * Centralised so that a mismatch between the declaring and the publishing side becomes a
 * compile-time impossibility rather than a message that vanishes at runtime.
 */
object RabbitTopology {

    /** Routes RPC and fire-and-forget by target name. Type `direct`. */
    const val RPC_EXCHANGE = "surf.rpc"

    /** Routes events by topic pattern. Type `topic`. */
    const val EVENTS_EXCHANGE = "surf.events"

    /** Dead-letter destination for rejected messages. Type `direct`. */
    const val DLX_EXCHANGE = "surf.dlx"

    /** Alternate exchange of [RPC_EXCHANGE], catching messages to unknown targets. Type `fanout`. */
    const val UNROUTABLE_EXCHANGE = "surf.unroutable"

    /** Single queue bound to [UNROUTABLE_EXCHANGE]. */
    const val UNROUTABLE_QUEUE = "surf.unroutable"

    private const val MAX_NAME_LENGTH = 255
    private val illegalCharacter = "[^a-zA-Z0-9._-]".toRegex()

    /** Shared, durable queue. Every instance of a service competes for its messages. */
    fun serviceQueue(serviceName: String): String = build("surf.service.", serviceName)

    /** Per-process queue for messages addressed at one specific instance. */
    fun instanceQueue(instanceId: String): String = build("surf.instance.", instanceId)

    /** Per-process RPC reply queue. */
    fun replyQueue(instanceId: String): String = build("surf.reply.", instanceId)

    /** Durable queue shared by all instances of a service. Exactly one instance handles each event. */
    fun sharedEventQueue(serviceName: String): String = build("surf.events.shared.", serviceName)

    /** Ephemeral queue owned by one instance. Every instance receives its own copy. */
    fun instanceEventQueue(instanceId: String): String = build("surf.events.instance.", instanceId)

    /** Durable queue holding messages a service could not process. */
    fun deadLetterQueue(serviceName: String): String = build("surf.dlq.", serviceName)

    /**
     * Replaces characters that are not valid in AMQP names.
     *
     * Applying this twice yields the same result, so a sanitised name can be passed through
     * again without changing.
     */
    fun sanitize(value: String): String = value.replace(illegalCharacter, "_")

    private fun build(prefix: String, rawName: String): String {
        require(rawName.isNotBlank()) {
            "Name must not be blank: a blank name would collide with every other blank name"
        }

        val name = prefix + sanitize(rawName)

        require(name.length <= MAX_NAME_LENGTH) {
            "AMQP names are limited to $MAX_NAME_LENGTH characters, " +
                    "but '$name' is ${name.length}"
        }

        return name
    }
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-common:test --tests '*RabbitTopologyTest*'`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 5: Commit**

```bash
git add surf-rabbitmq-common/src
git commit -m "feat(topology): centralise exchange and queue naming"
```

---

### Task 3: Identity and targets

Splits the overloaded `pluginName` into `serviceName` and `instanceId`, and introduces the type that says where a message goes. Both are pure value types, testable without a broker.

**Files:**
- Create: `surf-rabbitmq-api/surf-rabbitmq-common-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/identity/RabbitIdentity.kt`
- Create: `surf-rabbitmq-api/surf-rabbitmq-common-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/target/RabbitTarget.kt`
- Modify: `surf-rabbitmq-api/surf-rabbitmq-common-api/build.gradle.kts`
- Test: `surf-rabbitmq-api/surf-rabbitmq-common-api/src/test/kotlin/dev/slne/surf/rabbitmq/api/identity/RabbitIdentityTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  ```kotlin
  class RabbitIdentity(val serviceName: String, val instanceId: String) {
      companion object { fun create(serviceName: String): RabbitIdentity }
  }

  sealed interface RabbitTarget {
      val routingKey: String
      data class ServiceTarget(val serviceName: String) : RabbitTarget
      data class InstanceTarget(val instanceId: String) : RabbitTarget
  }
  ```
  Task 6 publishes with `target.routingKey` against `RabbitTopology.RPC_EXCHANGE`.

- [ ] **Step 1: Add the test platform to the API module**

Append to `surf-rabbitmq-api/surf-rabbitmq-common-api/build.gradle.kts`, before `kotlin { }`:

```kotlin
dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

- [ ] **Step 2: Write the failing test**

Create `surf-rabbitmq-api/surf-rabbitmq-common-api/src/test/kotlin/dev/slne/surf/rabbitmq/api/identity/RabbitIdentityTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.identity

import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RabbitIdentityTest {

    @Test
    fun `the instance id carries the service name`() {
        val identity = RabbitIdentity.create("surf-factions")

        assertEquals("surf-factions", identity.serviceName)
        assertTrue(
            identity.instanceId.startsWith("surf-factions-"),
            "an instance id that does not name its service is unreadable in broker tooling, " +
                    "but was '${identity.instanceId}'"
        )
    }

    @Test
    fun `two instances of the same service get different ids`() {
        val a = RabbitIdentity.create("surf-factions")
        val b = RabbitIdentity.create("surf-factions")

        assertEquals(a.serviceName, b.serviceName)
        assertNotEquals(
            a.instanceId, b.instanceId,
            "colliding instance ids make two processes share one reply queue"
        )
    }

    @Test
    fun `the suffix is eight hex characters`() {
        val identity = RabbitIdentity.create("svc")
        val suffix = identity.instanceId.removePrefix("svc-")

        assertEquals(8, suffix.length)
        assertTrue(suffix.all { it in "0123456789abcdef" }, "suffix was '$suffix'")
    }

    @Test
    fun `a blank service name is rejected`() {
        val thrown = runCatching { RabbitIdentity.create(" ") }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `a service target routes by service name`() {
        assertEquals("surf-factions", RabbitTarget.ServiceTarget("surf-factions").routingKey)
    }

    @Test
    fun `an instance target routes by instance id`() {
        assertEquals("lobby-3", RabbitTarget.InstanceTarget("lobby-3").routingKey)
    }

    @Test
    fun `targets of different kinds are not equal even with the same name`() {
        assertNotEquals<RabbitTarget>(
            RabbitTarget.ServiceTarget("x"),
            RabbitTarget.InstanceTarget("x")
        )
    }
}
```

- [ ] **Step 3: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-api:surf-rabbitmq-common-api:test`
Expected: `Unresolved reference: RabbitIdentity`.

- [ ] **Step 4: Implement both types**

Create `surf-rabbitmq-api/surf-rabbitmq-common-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/identity/RabbitIdentity.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.identity

import java.util.concurrent.ThreadLocalRandom

/**
 * Who this process is on the broker.
 *
 * Replaces the former `pluginName`, which acted as connection name, request queue name and
 * callback queue prefix at once and therefore limited a process to a single peer.
 *
 * @property serviceName the logical service, shared by every instance, e.g. `surf-factions`
 * @property instanceId unique to this process, e.g. `surf-factions-3f9a1c07`
 */
class RabbitIdentity(
    val serviceName: String,
    val instanceId: String
) {
    override fun toString(): String =
        "RabbitIdentity(serviceName='$serviceName', instanceId='$instanceId')"

    companion object {
        /**
         * Builds an identity for [serviceName] with a random instance suffix.
         *
         * The suffix keeps the service name visible so an operator looking at a queue list can
         * tell which service an instance queue belongs to.
         */
        fun create(serviceName: String): RabbitIdentity {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }

            val suffix = ThreadLocalRandom.current()
                .nextInt()
                .toLong()
                .and(0xFFFFFFFFL)
                .toString(16)
                .padStart(8, '0')

            return RabbitIdentity(
                serviceName = serviceName,
                instanceId = "$serviceName-$suffix"
            )
        }
    }
}
```

Create `surf-rabbitmq-api/surf-rabbitmq-common-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/target/RabbitTarget.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.target

/**
 * Where a message goes.
 *
 * Both variants are published to `surf.rpc`, which is a `direct` exchange: the routing key is
 * matched exactly against the binding of the destination queue.
 */
sealed interface RabbitTarget {

    /** The routing key used when publishing to `surf.rpc`. */
    val routingKey: String

    /**
     * Any one instance of [serviceName].
     *
     * All instances compete for the same queue, so the broker load-balances between them.
     * This is the normal case.
     */
    data class ServiceTarget(val serviceName: String) : RabbitTarget {
        init {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        }

        override val routingKey: String get() = serviceName
    }

    /**
     * One specific process, addressed by its [RabbitIdentity.instanceId].
     *
     * Use for things that only make sense on one machine, such as moving a player to a
     * particular Paper server. The message is lost if that instance is offline, since its
     * queue is `autoDelete`.
     */
    data class InstanceTarget(val instanceId: String) : RabbitTarget {
        init {
            require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        }

        override val routingKey: String get() = instanceId
    }
}
```

- [ ] **Step 5: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-api:surf-rabbitmq-common-api:test`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

- [ ] **Step 6: Commit**

```bash
git add surf-rabbitmq-api/surf-rabbitmq-common-api
git commit -m "feat(api): split pluginName into service name and instance id"
```

---

### Task 4: Queue arguments and topology declaration

Declares the topology against a real broker. The arguments are what make quorum queues, dead-lettering and overflow behaviour work — and they can only be verified against a broker, never against a mock.

**Files:**
- Create: `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/QueueArguments.kt`
- Create: `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyDeclarer.kt`
- Test: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/QueueArgumentsTest.kt`
- Test: `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyDeclarerTest.kt`

**Interfaces:**
- Consumes: `RabbitTopology` (Task 2), `RabbitIdentity` (Task 3), `RabbitBrokerExtension` (Task 1)
- Produces:
  ```kotlin
  object QueueArguments {
      const val MAX_SERVICE_QUEUE_BYTES = 268_435_456L
      fun serviceQueue(): Map<String, Any>
      fun deadLetterQueue(): Map<String, Any>
      fun sharedEventQueue(): Map<String, Any>
      fun ephemeralQueue(): Map<String, Any>
  }

  class RabbitTopologyDeclarer(private val channel: Channel) {
      fun declareExchanges()
      fun declareServiceQueue(serviceName: String): String
      fun declareInstanceQueue(instanceId: String): String
      fun declareReplyQueue(instanceId: String): String
      fun declareUnroutableQueue(): String
  }
  ```

- [ ] **Step 1: Write the failing argument test**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/QueueArgumentsTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueArgumentsTest {

    @Test
    fun `service queues are quorum, dead-lettered and bounded`() {
        val args = QueueArguments.serviceQueue()

        assertEquals("quorum", args["x-queue-type"])
        assertEquals(RabbitTopology.DLX_EXCHANGE, args["x-dead-letter-exchange"])
        assertEquals(268_435_456L, args["x-max-length-bytes"])
        assertEquals("reject-publish", args["x-overflow"])
    }

    @Test
    fun `service queues do not override the dead-letter routing key`() {
        // Dead-lettering must preserve the original routing key so a retried message
        // finds its way back to its own service queue.
        assertNull(QueueArguments.serviceQueue()["x-dead-letter-routing-key"])
    }

    @Test
    fun `the dead letter queue is not itself dead-lettered`() {
        // Otherwise a failing DLQ consumer would loop messages forever.
        val args = QueueArguments.deadLetterQueue()

        assertEquals("quorum", args["x-queue-type"])
        assertNull(args["x-dead-letter-exchange"])
    }

    @Test
    fun `ephemeral queues carry no quorum or overflow settings`() {
        // Quorum queues cannot be exclusive or auto-delete.
        val args = QueueArguments.ephemeralQueue()

        assertNull(args["x-queue-type"])
        assertNull(args["x-max-length-bytes"])
    }

    @Test
    fun `shared event queues are durable and dead-lettered`() {
        val args = QueueArguments.sharedEventQueue()

        assertEquals("quorum", args["x-queue-type"])
        assertEquals(RabbitTopology.DLX_EXCHANGE, args["x-dead-letter-exchange"])
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-common:test --tests '*QueueArgumentsTest*'`
Expected: `Unresolved reference: QueueArguments`.

- [ ] **Step 3: Implement the arguments**

Create `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/QueueArguments.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

/**
 * Argument maps for each kind of queue.
 *
 * These arguments are part of a queue's identity: redeclaring an existing queue with
 * different arguments fails the channel with `PRECONDITION_FAILED (406)`. Keeping them in one
 * place is what allows every declaring process to agree.
 */
object QueueArguments {

    /**
     * Upper bound on a service queue, in bytes.
     *
     * Combined with `reject-publish` this stops a service that has been down for days from
     * exhausting broker memory and taking every other service down with it.
     */
    const val MAX_SERVICE_QUEUE_BYTES = 268_435_456L

    /**
     * Durable, replicated, bounded, dead-lettered.
     *
     * No `x-dead-letter-routing-key` is set on purpose: RabbitMQ then preserves the original
     * routing key when dead-lettering, which is what lets a retried message return to its own
     * service queue without per-service retry queues.
     */
    fun serviceQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
        "x-max-length-bytes" to MAX_SERVICE_QUEUE_BYTES,
        "x-overflow" to "reject-publish"
    )

    /**
     * Durable and replicated, but **not** dead-lettered.
     *
     * A dead-letter queue that dead-letters would cycle messages endlessly when its own
     * consumer fails.
     */
    fun deadLetterQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum"
    )

    /** Same durability as a service queue: shared subscriptions must survive a restart. */
    fun sharedEventQueue(): Map<String, Any> = mapOf(
        "x-queue-type" to "quorum",
        "x-dead-letter-exchange" to RabbitTopology.DLX_EXCHANGE,
        "x-max-length-bytes" to MAX_SERVICE_QUEUE_BYTES,
        "x-overflow" to "reject-publish"
    )

    /**
     * No arguments at all.
     *
     * Reply, instance and per-instance event queues are `exclusive` and `autoDelete`, which
     * quorum queues do not support. They die with their process, which is the intent.
     */
    fun ephemeralQueue(): Map<String, Any> = emptyMap()
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-rabbitmq-common:test --tests '*QueueArgumentsTest*'`
Expected: `BUILD SUCCESSFUL`, 5 tests passed.

- [ ] **Step 5: Write the failing declarer test**

Create `surf-rabbitmq-common/src/test/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyDeclarerTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RequiresDocker
class RabbitTopologyDeclarerTest {

    private lateinit var connection: Connection
    private lateinit var channel: Channel
    private lateinit var declarer: RabbitTopologyDeclarer

    @BeforeEach
    fun setUp() {
        connection = RabbitBrokerExtension.newConnection("declarer-test")
        channel = connection.createChannel()
        declarer = RabbitTopologyDeclarer(channel)
    }

    @AfterEach
    fun tearDown() {
        runCatching { channel.close() }
        runCatching { connection.close() }
    }

    @Test
    fun `declaring exchanges is idempotent`() {
        declarer.declareExchanges()
        declarer.declareExchanges()

        // Passive declare throws if the exchange is absent.
        channel.exchangeDeclarePassive(RabbitTopology.RPC_EXCHANGE)
        channel.exchangeDeclarePassive(RabbitTopology.EVENTS_EXCHANGE)
        channel.exchangeDeclarePassive(RabbitTopology.DLX_EXCHANGE)
        channel.exchangeDeclarePassive(RabbitTopology.UNROUTABLE_EXCHANGE)
    }

    @Test
    fun `a service queue is bound to the rpc exchange under its service name`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("svc")
        val queue = declarer.declareServiceQueue(service)

        assertEquals(RabbitTopology.serviceQueue(service), queue)

        channel.basicPublish(
            RabbitTopology.RPC_EXCHANGE,
            service,
            null,
            "hello".toByteArray()
        )

        val delivered = awaitMessage(queue)
        assertEquals("hello", String(delivered))
    }

    @Test
    fun `a message to an unknown service lands in the unroutable queue`() {
        declarer.declareExchanges()
        declarer.declareUnroutableQueue()

        channel.basicPublish(
            RabbitTopology.RPC_EXCHANGE,
            "service-that-does-not-exist",
            null,
            "orphan".toByteArray()
        )

        val delivered = awaitMessage(RabbitTopology.UNROUTABLE_QUEUE)
        assertEquals(
            "orphan", String(delivered),
            "without the alternate exchange this message would be dropped silently"
        )
    }

    @Test
    fun `an instance queue only receives messages for its own instance`() {
        declarer.declareExchanges()
        val a = RabbitBrokerExtension.uniqueServiceName("inst-a")
        val b = RabbitBrokerExtension.uniqueServiceName("inst-b")

        val queueA = declarer.declareInstanceQueue(a)
        declarer.declareInstanceQueue(b)

        channel.basicPublish(RabbitTopology.RPC_EXCHANGE, a, null, "for-a".toByteArray())

        assertEquals("for-a", String(awaitMessage(queueA)))
        assertEquals(
            0, channel.queueDeclarePassive(RabbitTopology.instanceQueue(b)).messageCount,
            "instance b must not receive a message addressed to instance a"
        )
    }

    @Test
    fun `redeclaring a service queue with different arguments is refused`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("conflict")
        declarer.declareServiceQueue(service)

        // A second channel, because a failed declare kills the channel it ran on.
        connection.createChannel().use { other ->
            val thrown = runCatching {
                other.queueDeclare(
                    RabbitTopology.serviceQueue(service),
                    true, false, false,
                    mapOf("x-queue-type" to "classic")
                )
            }.exceptionOrNull()

            assertTrue(
                thrown is IOException,
                "changing queue arguments must be refused, otherwise two versions of the " +
                        "library would silently disagree about the topology"
            )
        }
    }

    @Test
    fun `the service queue carries the configured arguments`() {
        declarer.declareExchanges()
        val service = RabbitBrokerExtension.uniqueServiceName("args")
        declarer.declareServiceQueue(service)

        // Redeclaring with identical arguments succeeds; with different ones it would fail.
        // This is the cheapest way to assert the stored arguments without the HTTP API.
        val ok = channel.queueDeclare(
            RabbitTopology.serviceQueue(service),
            true, false, false,
            QueueArguments.serviceQueue()
        )

        assertNotNull(ok)
    }

    @Test
    fun `a reply queue is addressable through the default exchange`() {
        declarer.declareExchanges()
        val instance = RabbitBrokerExtension.uniqueServiceName("reply")
        val queue = declarer.declareReplyQueue(instance)

        channel.basicPublish("", queue, null, "pong".toByteArray())

        assertEquals("pong", String(awaitMessage(queue)))
    }

    private fun awaitMessage(queue: String, timeoutMillis: Long = 5_000): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMillis

        while (System.currentTimeMillis() < deadline) {
            val response = channel.basicGet(queue, true)
            if (response != null) return response.body
            Thread.sleep(25)
        }

        throw AssertionError("no message arrived in '$queue' within ${timeoutMillis}ms")
    }
}
```

- [ ] **Step 6: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-common:test --tests '*RabbitTopologyDeclarerTest*'`
Expected: `Unresolved reference: RabbitTopologyDeclarer`.

Without Docker the class still must compile; the tests are then reported skipped.

- [ ] **Step 7: Implement the declarer**

Create `surf-rabbitmq-common/src/main/kotlin/dev/slne/surf/rabbitmq/common/topology/RabbitTopologyDeclarer.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.topology

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel

/**
 * Declares exchanges, queues and bindings on a channel.
 *
 * Exchange declaration is idempotent and every process performs it. Queue declaration is not
 * shared: **a process declares only queues it consumes itself**. Declaring another service's
 * queue was the cause of the `PRECONDITION_FAILED (406)` failures in 1.6.x, where a client
 * declared the server's queue with arguments the server later disagreed with.
 *
 * All methods are blocking and must not run on a coroutine dispatcher that disallows blocking.
 */
class RabbitTopologyDeclarer(private val channel: Channel) {

    /**
     * Declares the four exchanges.
     *
     * Safe to call from every process and on every reconnect: redeclaring with identical
     * properties is a no-op on the broker.
     */
    fun declareExchanges() {
        channel.exchangeDeclare(
            RabbitTopology.UNROUTABLE_EXCHANGE,
            BuiltinExchangeType.FANOUT,
            /* durable = */ true
        )

        // Anything published to surf.rpc with no matching binding is re-routed here instead
        // of being dropped without trace.
        channel.exchangeDeclare(
            RabbitTopology.RPC_EXCHANGE,
            BuiltinExchangeType.DIRECT,
            /* durable = */ true,
            /* autoDelete = */ false,
            mapOf("alternate-exchange" to RabbitTopology.UNROUTABLE_EXCHANGE)
        )

        channel.exchangeDeclare(
            RabbitTopology.EVENTS_EXCHANGE,
            BuiltinExchangeType.TOPIC,
            /* durable = */ true
        )

        channel.exchangeDeclare(
            RabbitTopology.DLX_EXCHANGE,
            BuiltinExchangeType.DIRECT,
            /* durable = */ true
        )
    }

    /**
     * Declares the shared service queue and its dead-letter queue, and binds both.
     *
     * Call this only on a process that hosts [serviceName].
     *
     * @return the queue name
     */
    fun declareServiceQueue(serviceName: String): String {
        val dlq = RabbitTopology.deadLetterQueue(serviceName)
        channel.queueDeclare(dlq, true, false, false, QueueArguments.deadLetterQueue())
        channel.queueBind(dlq, RabbitTopology.DLX_EXCHANGE, serviceName)

        val queue = RabbitTopology.serviceQueue(serviceName)
        channel.queueDeclare(queue, true, false, false, QueueArguments.serviceQueue())
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, serviceName)

        return queue
    }

    /**
     * Declares this process's private queue for directly addressed messages.
     *
     * Exclusive and auto-deleting: it disappears when the process goes away, so an offline
     * instance leaves nothing behind on the broker.
     */
    fun declareInstanceQueue(instanceId: String): String {
        val queue = RabbitTopology.instanceQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())
        channel.queueBind(queue, RabbitTopology.RPC_EXCHANGE, instanceId)

        return queue
    }

    /**
     * Declares this process's RPC reply queue.
     *
     * Bound to nothing: replies are addressed through the default exchange using the queue
     * name as routing key, which is what the `replyTo` property carries.
     */
    fun declareReplyQueue(instanceId: String): String {
        val queue = RabbitTopology.replyQueue(instanceId)
        channel.queueDeclare(queue, false, true, true, QueueArguments.ephemeralQueue())

        return queue
    }

    /** Declares the single queue collecting messages addressed to unknown targets. */
    fun declareUnroutableQueue(): String {
        val queue = RabbitTopology.UNROUTABLE_QUEUE
        channel.queueDeclare(queue, true, false, false, QueueArguments.deadLetterQueue())
        channel.queueBind(queue, RabbitTopology.UNROUTABLE_EXCHANGE, "")

        return queue
    }
}
```

- [ ] **Step 8: Run the integration tests**

With Docker running:

Run: `./gradlew :surf-rabbitmq-common:test --tests '*RabbitTopologyDeclarerTest*'`
Expected: `BUILD SUCCESSFUL`, 7 tests passed.

Without Docker:

Run: `./gradlew :surf-rabbitmq-common:test`
Expected: `BUILD SUCCESSFUL`, declarer tests **skipped**. Record them as unverified.

- [ ] **Step 9: Commit**

```bash
git add surf-rabbitmq-common
git commit -m "feat(topology): declare exchanges, service, instance and reply queues

A process now declares only queues it consumes itself, which removes the
cross-declaration that broke client channels when queue arguments changed."
```

---

### Task 5: Merge the six modules into two

Purely mechanical, no behaviour change. Done as its own task so that the diff stays reviewable and any later failure can be attributed to design rather than to the move.

**Files:**
- Modify: `settings.gradle.kts`
- Move: `surf-rabbitmq-api/surf-rabbitmq-{client,server}-api/src/**` → `surf-rabbitmq-api/src/**`
- Move: `surf-rabbitmq-{client,server}/src/**` → `surf-rabbitmq-core/src/**`
- Move: `surf-rabbitmq-common/src/**` → `surf-rabbitmq-core/src/**`
- Modify: every `build.gradle.kts` referencing the old module paths

**Interfaces:**
- Consumes: everything from Tasks 1–4
- Produces: modules `:surf-rabbitmq-api` and `:surf-rabbitmq-core`

- [ ] **Step 1: Verify the build is green before moving anything**

Run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL`.

If this fails, stop. Do not start a move from a broken build — you will not be able to tell which failure is yours.

- [ ] **Step 2: Restructure the API modules**

```bash
git mv surf-rabbitmq-api/surf-rabbitmq-common-api/src surf-rabbitmq-api/src-tmp
git mv surf-rabbitmq-api/surf-rabbitmq-common-api/build.gradle.kts surf-rabbitmq-api/build.gradle.kts

cp -r surf-rabbitmq-api/surf-rabbitmq-client-api/src/main/kotlin/. surf-rabbitmq-api/src-tmp/main/kotlin/
cp -r surf-rabbitmq-api/surf-rabbitmq-server-api/src/main/kotlin/. surf-rabbitmq-api/src-tmp/main/kotlin/

git rm -r --quiet surf-rabbitmq-api/surf-rabbitmq-client-api
git rm -r --quiet surf-rabbitmq-api/surf-rabbitmq-server-api
git rm -r --quiet surf-rabbitmq-api/surf-rabbitmq-common-api

git mv surf-rabbitmq-api/src-tmp surf-rabbitmq-api/src
```

- [ ] **Step 3: Restructure the implementation modules**

```bash
git mv surf-rabbitmq-common surf-rabbitmq-core

cp -r surf-rabbitmq-client/src/main/kotlin/. surf-rabbitmq-core/src/main/kotlin/
cp -r surf-rabbitmq-server/src/main/kotlin/. surf-rabbitmq-core/src/main/kotlin/
cp -r surf-rabbitmq-server/src/main/java/. surf-rabbitmq-core/src/main/java/

git rm -r --quiet surf-rabbitmq-client
git rm -r --quiet surf-rabbitmq-server
```

- [ ] **Step 4: Update settings.gradle.kts**

Replace the include block with:

```kotlin
include("surf-rabbitmq-api")
include("surf-rabbitmq-core")
include("surf-circuitbreaker")

include("surf-rabbitmq-paper")
include("surf-rabbitmq-velocity")
include("surf-rabbitmq-ksp")
```

Keep the `isCi` guarded test includes below unchanged.

- [ ] **Step 5: Fix project references**

Search for stale references:

```bash
grep -rn "surfRabbitmqCommonApi\|surfRabbitmqClientApi\|surfRabbitmqServerApi\|surfRabbitmqCommon\b\|surfRabbitmqClient\|surfRabbitmqServer" --include=build.gradle.kts .
```

Replace every hit with `projects.surfRabbitmqApi` or `projects.surfRabbitmqCore` as appropriate.
In `surf-rabbitmq-core/build.gradle.kts` the API dependency becomes:

```kotlin
    api(projects.surfRabbitmqApi)
```

- [ ] **Step 6: Compile and fix fallout**

Run: `./gradlew build -x test`

Expected failures and their fixes:
- Duplicate `RabbitMQConnectionFactory` service registrations from client and server →
  keep one, delete the other; the merged connection is built in Task 6.
- `surf-rabbitmq-paper` / `-velocity` referencing removed modules → point them at
  `projects.surfRabbitmqCore`.

Iterate until green. Do **not** change behaviour in this task; only make it compile.

- [ ] **Step 7: Run the full test suite**

Run: `./gradlew test -PskipIntegration`
Expected: `BUILD SUCCESSFUL`, all Task 1–4 tests still pass.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: merge client and server modules into api and core

Mechanical move only. Six modules become two; no behaviour changes."
```

---

### Task 6: SurfRabbitApi

The unified entry point. Replaces `ClientRabbitMQApi` and `ServerRabbitMQApi`, so one process can address many services over one connection.

**Files:**
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApi.kt`
- Create: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApiBuilder.kt`
- Delete: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/ClientRabbitMQApi.kt`
- Delete: `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/ServerRabbitMQApi.kt`
- Test: `surf-rabbitmq-api/src/test/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApiBuilderTest.kt`

**Interfaces:**
- Consumes: `RabbitIdentity`, `RabbitTarget` (Task 3)
- Produces:
  ```kotlin
  class SurfRabbitApi {
      val identity: RabbitIdentity
      val config: CommonRabbitMQConfig
      val cbor: Cbor
      val scope: CoroutineScope

      fun freeze()
      fun isFrozen(): Boolean
      suspend fun connect()
      suspend fun freezeAndConnect()
      suspend fun disconnect()

      fun registerRequestHandler(instance: Any)
      fun <S : Any> registerService(kClass: KClass<S>, instance: S)
      fun <S : Any> rpc(kClass: KClass<S>, service: String? = null): S

      companion object { fun builder(serviceName: String, dataPath: Path): SurfRabbitApiBuilder }
  }
  ```
  Plan 3 adds `publish`, `send` and `registerListener`. Plan 4 adds breaker wiring.

- [ ] **Step 1: Write the failing builder test**

Create `surf-rabbitmq-api/src/test/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApiBuilderTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SurfRabbitApiBuilderTest {

    private val dataPath = Files.createTempDirectory("surf-rabbit-test")

    @Test
    fun `the builder derives an identity from the service name`() {
        val api = SurfRabbitApi.builder("surf-factions", dataPath).build()

        assertEquals("surf-factions", api.identity.serviceName)
        assertTrue(api.identity.instanceId.startsWith("surf-factions-"))
    }

    @Test
    fun `a blank service name is rejected at build time`() {
        assertFailsWith<IllegalArgumentException> {
            SurfRabbitApi.builder("  ", dataPath).build()
        }
    }

    @Test
    fun `a fresh api is not frozen`() {
        val api = SurfRabbitApi.builder("svc", dataPath).build()
        assertTrue(!api.isFrozen())
    }

    @Test
    fun `freezing twice is refused`() {
        val api = SurfRabbitApi.builder("svc", dataPath).build()
        api.freeze()

        assertFailsWith<IllegalStateException> { api.freeze() }
    }

    @Test
    fun `handlers cannot be registered after freezing`() {
        val api = SurfRabbitApi.builder("svc", dataPath).build()
        api.freeze()

        assertFailsWith<IllegalStateException> {
            api.registerRequestHandler(Any())
        }
    }

    @Test
    fun `two instances of the same service get different identities`() {
        val a = SurfRabbitApi.builder("svc", dataPath).build()
        val b = SurfRabbitApi.builder("svc", dataPath).build()

        assertTrue(
            a.identity.instanceId != b.identity.instanceId,
            "identical instance ids would make two processes share a reply queue"
        )
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-rabbitmq-api:test`
Expected: `Unresolved reference: SurfRabbitApi`.

- [ ] **Step 3: Implement the builder**

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApiBuilder.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api

import dev.slne.surf.rabbitmq.api.identity.RabbitIdentity
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.api.internal.config.GlobalRabbitMQConfig
import dev.slne.surf.rabbitmq.api.internal.config.resolveRabbitMQConfig
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

/**
 * Builds a [SurfRabbitApi].
 *
 * ```kotlin
 * val rabbit = SurfRabbitApi.builder("surf-factions", dataPath)
 *     .serializers(FactionsSerializers)
 *     .build()
 * ```
 */
class SurfRabbitApiBuilder internal constructor(
    private val serviceName: String,
    private val dataPath: Path
) {
    private var serializers: SerializersModule = EmptySerializersModule()
    private var configOverride: CommonRabbitMQConfig? = null
    private var configFileName: String = "rabbitmq.yml"

    /** Additional serializers for packet, event and RPC payload types. */
    fun serializers(module: SerializersModule): SurfRabbitApiBuilder = apply {
        serializers = module
    }

    /** Overrides the config file name looked up under the data path. */
    fun configFileName(name: String): SurfRabbitApiBuilder = apply {
        configFileName = name
    }

    /** Supplies a config directly, bypassing file loading. Intended for tests. */
    fun config(config: CommonRabbitMQConfig): SurfRabbitApiBuilder = apply {
        configOverride = config
    }

    fun build(): SurfRabbitApi {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val config = configOverride
            ?: resolveRabbitMQConfig(GlobalRabbitMQConfig.getOrLoad(dataPath, configFileName))

        return SurfRabbitApi(
            identity = RabbitIdentity.create(serviceName),
            config = config,
            cbor = SurfRabbitApi.createCbor(serializers)
        )
    }
}
```

- [ ] **Step 4: Implement the API**

Create `surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/SurfRabbitApi.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api

import dev.slne.surf.api.core.serializer.SurfSerializerModule
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.connection.RabbitMQConnection
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiAlreadyFrozenException
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitApiNotFrozenException
import dev.slne.surf.rabbitmq.api.identity.RabbitIdentity
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.api.packet.standard.response.StringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.optional.OptionalStringResponsePacket
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.OptionalPrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.PrimitiveResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.ArrayResponse
import dev.slne.surf.rabbitmq.api.packet.standard.response.primitive.array.OptionalArrayResponse
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory
import kotlinx.coroutines.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.overwriteWith
import java.nio.file.Path
import kotlin.reflect.KClass

/**
 * The entry point to RabbitMQ messaging.
 *
 * Replaces the former `ClientRabbitMQApi` / `ServerRabbitMQApi` split. There is no client or
 * server role: a process that calls [registerService] or [registerRequestHandler] hosts a
 * service queue, and one that does not simply has none. Both can call [rpc].
 *
 * A single instance addresses any number of services over one TCP connection, one publisher
 * pool and one reply queue.
 *
 * ```kotlin
 * val rabbit = SurfRabbitApi.builder("surf-factions", dataPath).build()
 *
 * rabbit.registerService<FactionService>(FactionServiceImpl)
 * rabbit.freezeAndConnect()
 *
 * val punish = rabbit.rpc<PunishService>()
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
class SurfRabbitApi @InternalRabbitMQ constructor(
    val identity: RabbitIdentity,
    @InternalRabbitMQ val config: CommonRabbitMQConfig,
    val cbor: Cbor
) {
    @InternalRabbitMQ
    val scope = CoroutineScope(
        Dispatchers.Default +
                CoroutineName("SurfRabbitApi-${identity.instanceId}") +
                SupervisorJob() +
                CoroutineExceptionHandler { context, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log("Unhandled exception in SurfRabbitApi coroutine ${context[CoroutineName]}")
                }
    )

    @InternalRabbitMQ
    val rpcService = RabbitRpcServiceFactory.instance.createRpcService(this)

    @InternalRabbitMQ
    val connection: RabbitMQConnection = RabbitMQConnection.create(this)

    private var frozen = false

    /**
     * Locks registration.
     *
     * Handlers and services must be known before the consumer starts, otherwise a message
     * could arrive for a handler that is still being registered.
     */
    fun freeze() {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        frozen = true
    }

    fun isFrozen(): Boolean = frozen

    suspend fun connect() {
        if (!frozen) throw SurfRabbitApiNotFrozenException()
        connection.connect()
    }

    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    suspend fun disconnect() {
        connection.disconnect()
        scope.cancel("SurfRabbitApi disconnected")
    }

    /**
     * Registers `@RabbitHandler` methods on [instance].
     *
     * Hosting a handler makes this process consume the service queue of
     * [RabbitIdentity.serviceName].
     */
    fun registerRequestHandler(instance: Any) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        connection.registerRequestHandler(instance)
    }

    /** Registers the server-side implementation of an `@RpcService` interface. */
    fun <Service : Any> registerService(serviceKClass: KClass<Service>, serviceInstance: Service) {
        if (frozen) throw SurfRabbitApiAlreadyFrozenException()
        rpcService.registerService(serviceKClass, serviceInstance)
    }

    /** Registers the server-side implementation of an `@RpcService` interface. */
    inline fun <reified Service : Any> registerService(serviceInstance: Service) {
        registerService(Service::class, serviceInstance)
    }

    /**
     * Creates a client proxy for an `@RpcService` interface.
     *
     * The target service comes from the interface's `@RpcService(service = ...)`. Pass
     * [service] to override it, for example to reach a staging deployment.
     *
     * The returned proxy is cheap to keep but not free to create; create it once and reuse it.
     */
    fun <Service : Any> rpc(serviceKClass: KClass<Service>, service: String? = null): Service =
        rpcService.createService(serviceKClass, service)

    /** Creates a client proxy for an `@RpcService` interface. */
    inline fun <reified Service : Any> rpc(service: String? = null): Service =
        rpc(Service::class, service)

    companion object {
        private val log = logger()

        fun builder(serviceName: String, dataPath: Path): SurfRabbitApiBuilder =
            SurfRabbitApiBuilder(serviceName, dataPath)

        @InternalRabbitMQ
        fun createCbor(additionalSerializerModule: SerializersModule): Cbor = Cbor {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                include(SurfSerializerModule.all.overwriteWith(additionalSerializerModule))
                include(defaultSerializersModule)
            }
        }

        private val defaultSerializersModule = SerializersModule {
            include(PrimitiveResponse.SERIALIZER_MODULE)
            include(OptionalPrimitiveResponse.SERIALIZER_MODULE)
            include(ArrayResponse.SERIALIZER_MODULE)
            include(OptionalArrayResponse.SERIALIZER_MODULE)

            contextual(StringResponsePacket.serializer())
            contextual(OptionalStringResponsePacket.serializer())
        }
    }
}
```

- [ ] **Step 5: Adapt the interfaces the API depends on**

`RabbitMQConnection` gains the members `SurfRabbitApi` now calls. Edit
`surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/connection/RabbitMQConnection.kt`:

```kotlin
package dev.slne.surf.rabbitmq.api.connection

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget

@InternalRabbitMQ
interface RabbitMQConnection {
    suspend fun connect()
    suspend fun disconnect()

    fun registerRequestHandler(instance: Any)

    suspend fun <R : RabbitResponsePacket> sendRequest(
        request: RabbitRequestPacket<R>,
        responseClass: Class<R>,
        target: RabbitTarget
    ): R

    @InternalRabbitMQ
    companion object {
        fun create(api: SurfRabbitApi): RabbitMQConnection =
            RabbitMQConnectionFactory.createConnection(api)
    }
}
```

Update `RabbitMQConnectionFactory.createConnection` to take `SurfRabbitApi`, and
`RabbitRpcServiceFactory.createRpcService` likewise. Change
`RabbitRpcService.createService` to accept the nullable service override:

```kotlin
fun <Service : Any> createService(serviceKClass: KClass<Service>, service: String?): Service
```

- [ ] **Step 6: Delete the superseded API classes**

```bash
git rm surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/ClientRabbitMQApi.kt
git rm surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/ServerRabbitMQApi.kt
git rm surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/RabbitMQApi.kt
git rm surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/connection/ClientRabbitMQConnection.kt
git rm surf-rabbitmq-api/src/main/kotlin/dev/slne/surf/rabbitmq/api/connection/ServerRabbitMQConnection.kt
```

- [ ] **Step 7: Compile**

Run: `./gradlew :surf-rabbitmq-api:build -x test`

Fix every reference to the deleted types. `surf-rabbitmq-core` will not compile yet — that is
Task 7. Restrict this step to `:surf-rabbitmq-api`.

- [ ] **Step 8: Run the API tests**

Run: `./gradlew :surf-rabbitmq-api:test`
Expected: `BUILD SUCCESSFUL`, 6 builder tests plus the 7 identity tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A surf-rabbitmq-api
git commit -m "feat(api): replace client and server APIs with a single SurfRabbitApi

Capability now follows from which register methods a process calls rather
than from which API class it constructed. One instance can address any
number of services over one connection."
```

---

### Task 7: Merged connection over the new topology

Fuses `ClientRabbitMQConnectionImpl` and `ServerRabbitMQConnectionImpl` into one class that publishes to `surf.rpc` with a target routing key and consumes only what it hosts.

**Files:**
- Create: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/RabbitConnectionImpl.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/client/connection/ClientRabbitMQConnectionImpl.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/connection/ServerRabbitMQConnectionImpl.kt`
- Delete: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/connection/AbstractRabbitMQConnectionImpl.kt`
- Modify: `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/connection/client/RabbitClient.kt`

**Interfaces:**
- Consumes: `RabbitTopologyDeclarer` (Task 4), `SurfRabbitApi` (Task 6)
- Produces: `class RabbitConnectionImpl(api: SurfRabbitApi) : RabbitMQConnection`

- [ ] **Step 1: Write the connection wiring**

Create `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/core/connection/RabbitConnectionImpl.kt`.

Reuse the existing bodies wholesale — reply correlation, chunk assembly and pending-request
handling from `ClientRabbitMQConnectionImpl`, request dispatch from
`ServerRabbitMQConnectionImpl`. Change only what follows:

1. Replace the constructor parameters `(api: RabbitMQApi, config: CommonRabbitMQConfig)` with
   `(private val api: SurfRabbitApi)` and read `api.config` where the config is needed.

2. Replace `connect()` from `AbstractRabbitMQConnectionImpl`:

```kotlin
    override suspend fun connect() {
        val declareConsumer = client.newConsumer("declare")

        // Every process declares the exchanges; they are idempotent.
        declareConsumer.withChannel { channel ->
            RabbitTopologyDeclarer(channel).declareExchanges()
        }

        // Reply and instance queues get their own consumer, and therefore their own channel.
        // Sharing one channel meant a failed declare took the RPC reply path down with it.
        replyConsumer = client.newConsumer("reply")
        replyQueueName = replyConsumer.withChannel { channel ->
            RabbitTopologyDeclarer(channel).declareReplyQueue(api.identity.instanceId)
        }
        startConsumingResponses(replyQueueName)

        // Only a process that actually handles requests declares and consumes a service queue.
        if (listenerHandler.hasHandlers()) {
            serviceConsumer = client.newConsumer("service")
            val serviceQueue = serviceConsumer.withChannel { channel ->
                RabbitTopologyDeclarer(channel).declareServiceQueue(api.identity.serviceName)
            }
            startConsumingRequests(serviceQueue)
        }

        replyEndpoint.value = ReplyEndpoint(
            queueName = replyQueueName,
            connectionGeneration = client.connectionGeneration
        )
    }
```

3. Publish to the topology exchange instead of the default exchange. In the request path,
   replace:

```kotlin
                client.publish(
                    exchange = "",
                    routingKey = queueName,
```

with:

```kotlin
                client.publish(
                    exchange = RabbitTopology.RPC_EXCHANGE,
                    routingKey = target.routingKey,
                    mandatory = true,
```

`mandatory = true` makes the broker return a message it cannot route, so an unknown target
fails immediately instead of after the request timeout.

4. Keep the reply publish on the default exchange — `replyTo` carries a queue name:

```kotlin
            client.publish(
                exchange = "",
                routingKey = replyTo,
```

5. Thread `target: RabbitTarget` through `sendRequest` and `awaitResponse`.

- [ ] **Step 2: Add `withChannel` to RabbitConsumer**

In `surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/connection/consumer/RabbitConsumer.kt`, add:

```kotlin
    /**
     * Runs [block] on this consumer's channel, on the channel's own single-threaded
     * dispatcher.
     *
     * AMQP channels are not thread-safe. Confining every channel operation to one thread is
     * what makes concurrent declares safe.
     */
    suspend fun <T> withChannel(block: (Channel) -> T): T = withContext(channelDispatcher) {
        block(getChannel())
    }
```

- [ ] **Step 3: Delete the superseded connection classes**

```bash
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/client/connection/ClientRabbitMQConnectionImpl.kt
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/client/connection/ClientRabbitMQConnectionFactory.kt
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/connection/ServerRabbitMQConnectionImpl.kt
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/connection/ServerRabbitMQConnectionFactory.kt
git rm surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq/common/connection/AbstractRabbitMQConnectionImpl.kt
```

Create a single `RabbitConnectionFactoryImpl` registered via `@AutoService`, returning
`RabbitConnectionImpl`.

- [ ] **Step 4: Compile**

Run: `./gradlew build -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(core): merge client and server connections onto surf.rpc

Requests publish to the direct exchange with the target as routing key and
mandatory=true. Reply and service queues use separate channels so a failed
declare no longer takes down the RPC reply path."
```

---

### Task 8: RPC round-trip and multi-target integration tests

Proves the point of the whole plan: one connection, several targets, and each request answered by exactly one instance.

**Files:**
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/RpcRoundTripTest.kt`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/CompetingConsumersTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–7
- Produces: nothing

- [ ] **Step 1: Write the tests**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/RpcRoundTripTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket
import dev.slne.surf.rabbitmq.api.packet.RabbitResponsePacket
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

@Serializable
class EchoPacket(val text: String) : RabbitRequestPacket<EchoResponse>()

@Serializable
class EchoResponse(val text: String) : RabbitResponsePacket()

@RequiresDocker
class RpcRoundTripTest {

    private val dataPath = Files.createTempDirectory("rpc-test")

    private object EchoHandler {
        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    private fun api(serviceName: String) = SurfRabbitApi
        .builder(serviceName, dataPath)
        .config(testConfig())
        .build()

    @Test
    fun `a request is answered by the hosting service`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("echo")

        val server = api(service)
        server.registerRequestHandler(EchoHandler)
        server.freezeAndConnect()

        val client = api("caller")
        client.freezeAndConnect()

        try {
            val response = client.connection.sendRequest(
                EchoPacket("hello"),
                EchoResponse::class.java,
                RabbitTarget.ServiceTarget(service)
            )

            assertEquals("echo:hello", response.text)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `one client reaches two different services over one connection`() = runBlocking {
        val serviceA = RabbitBrokerExtension.uniqueServiceName("a")
        val serviceB = RabbitBrokerExtension.uniqueServiceName("b")

        val serverA = api(serviceA).also { it.registerRequestHandler(EchoHandler); it.freezeAndConnect() }
        val serverB = api(serviceB).also { it.registerRequestHandler(EchoHandler); it.freezeAndConnect() }

        val client = api("caller")
        client.freezeAndConnect()

        try {
            val fromA = client.connection.sendRequest(
                EchoPacket("a"), EchoResponse::class.java, RabbitTarget.ServiceTarget(serviceA)
            )
            val fromB = client.connection.sendRequest(
                EchoPacket("b"), EchoResponse::class.java, RabbitTarget.ServiceTarget(serviceB)
            )

            assertEquals("echo:a", fromA.text)
            assertEquals("echo:b", fromB.text)
            // Before this redesign, reaching two services required two API instances
            // and therefore two TCP connections.
        } finally {
            client.disconnect()
            serverA.disconnect()
            serverB.disconnect()
        }
    }

    @Test
    fun `a request to an unknown service fails fast instead of timing out`() = runBlocking {
        val client = api("caller")
        client.freezeAndConnect()

        try {
            val start = System.currentTimeMillis()

            val thrown = runCatching {
                client.connection.sendRequest(
                    EchoPacket("x"),
                    EchoResponse::class.java,
                    RabbitTarget.ServiceTarget("service-that-does-not-exist")
                )
            }.exceptionOrNull()

            val elapsed = System.currentTimeMillis() - start

            assert(thrown != null) { "an unroutable request must fail" }
            assert(elapsed < 10_000) {
                "expected a fast failure via mandatory + return listener, " +
                        "but it took ${elapsed}ms - it is falling through to the request timeout"
            }
        } finally {
            client.disconnect()
        }
    }
}
```

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/core/CompetingConsumersTest.kt`:

```kotlin
package dev.slne.surf.rabbitmq.core

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.handler.RabbitHandler
import dev.slne.surf.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RequiresDocker
class CompetingConsumersTest {

    private val dataPath = Files.createTempDirectory("competing-test")

    private class CountingHandler(val id: Int, val seen: MutableMap<String, Int>) {
        val handled = AtomicInteger()

        @RabbitHandler
        suspend fun onEcho(packet: EchoPacket) {
            handled.incrementAndGet()
            seen[packet.text] = id
            packet.respond(EchoResponse("echo:${packet.text}"))
        }
    }

    @Test
    fun `three instances share the load and each message is handled exactly once`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("competing")
        val seen = ConcurrentHashMap<String, Int>()

        val handlers = (1..3).map { CountingHandler(it, seen) }
        val servers = handlers.map { handler ->
            SurfRabbitApi.builder(service, dataPath).config(testConfig()).build().also {
                it.registerRequestHandler(handler)
                it.freezeAndConnect()
            }
        }

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val responses = (1..100).map { n ->
                async {
                    client.connection.sendRequest(
                        EchoPacket("msg-$n"),
                        EchoResponse::class.java,
                        RabbitTarget.ServiceTarget(service)
                    )
                }
            }.awaitAll()

            assertEquals(100, responses.size)
            assertEquals(
                100, seen.size,
                "every message must be handled exactly once - a smaller number means " +
                        "messages were dropped, a larger one means they were duplicated"
            )
            assertEquals(100, handlers.sumOf { it.handled.get() })

            val idle = handlers.count { it.handled.get() == 0 }
            assertTrue(
                idle == 0,
                "all three instances should receive work, but $idle received none - " +
                        "check that every instance consumes the same service queue"
            )
        } finally {
            client.disconnect()
            servers.forEach { it.disconnect() }
        }
    }
}
```

- [ ] **Step 2: Add the test config helper**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/common/testing/TestConfig.kt`:

```kotlin
package dev.slne.surf.rabbitmq.common.testing

import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig

/** Points a [CommonRabbitMQConfig] at the Testcontainers broker with test-sized timeouts. */
fun testConfig(
    requestTimeoutSeconds: Int = 10,
    prefetch: Int = 16
): CommonRabbitMQConfig = object : CommonRabbitMQConfig {
    private val factory = RabbitBrokerExtension.connectionFactory()

    override fun getHost() = factory.host
    override fun getPort() = factory.port
    override fun getUsername() = factory.username
    override fun getPassword() = factory.password
    override fun getVhost() = factory.virtualHost
    override fun getTimeout() = 10
    override fun getRequestTimeoutSeconds() = requestTimeoutSeconds
    override fun getPublisherPoolSize() = 2
    override fun getServerPrefetchCount() = prefetch
    override fun isPersistRequests() = true
    override fun isPersistResponses() = false
    override fun isOutgoingRequestChunkingEnabled() = false
    override fun isOutgoingResponseChunkingEnabled() = true
}
```

- [ ] **Step 3: Run**

With Docker: `./gradlew :surf-rabbitmq-core:test`
Expected: `BUILD SUCCESSFUL`, 4 tests passed.

Without Docker: tests are skipped. Record as unverified.

If `a request to an unknown service fails fast` fails on the elapsed-time assertion, the
`ReturnListener` is not wired: `mandatory = true` alone only returns the message to the
publisher, something must observe it. That wiring belongs to Plan 4 — if it is not yet
present, mark this single test `@Disabled("return listener lands in Plan 4")` and re-enable
it there.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test(core): verify RPC round-trip, multi-target and competing consumers"
```

---

### Task 9: Extraction-ready package split

Moves broker-neutral code into packages free of RabbitMQ types, and enforces it with a test so the boundary survives.

**Files:**
- Move: serializer caches → `dev.slne.surf.rabbitmq.shared.serialization`
- Move: invoker and handler discovery → `dev.slne.surf.rabbitmq.shared.dispatch`
- Move: config layering → `dev.slne.surf.rabbitmq.shared.config`
- Move: Paper/Velocity reflection proxies → `dev.slne.surf.rabbitmq.platform`
- Test: `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/shared/SharedPackagePurityTest.kt`

**Interfaces:**
- Consumes: the merged modules from Task 5
- Produces: `shared.*` and `platform.*` packages containing no RabbitMQ references

- [ ] **Step 1: Write the failing purity test**

Create `surf-rabbitmq-core/src/test/kotlin/dev/slne/surf/rabbitmq/shared/SharedPackagePurityTest.kt`:

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
 * Keeps the broker-neutral packages extractable into `surf-broker`.
 *
 * These packages are shared with surf-redis later. A single RabbitMQ import would turn that
 * move from mechanical into a redesign, and nothing else in the build would notice.
 */
class SharedPackagePurityTest {

    private val neutralPackages = listOf(
        "shared/serialization",
        "shared/dispatch",
        "shared/config",
        "platform"
    )

    private val forbidden = listOf("com.rabbitmq", "RabbitMQ", "RabbitPacket", "RabbitClient")

    @Test
    fun `broker-neutral packages do not reference RabbitMQ`() {
        val root = Path.of("src", "main", "kotlin", "dev", "slne", "surf", "rabbitmq")

        if (!Files.exists(root)) {
            fail("Expected sources at ${root.toAbsolutePath()}")
        }

        val offenders = neutralPackages
            .map(root::resolve)
            .filter(Files::exists)
            .flatMap { dir ->
                Files.walk(dir).asSequence()
                    .filter { it.extension == "kt" }
                    .mapNotNull { file ->
                        val text = file.readText()
                        forbidden.firstOrNull { text.contains(it) }
                            ?.let { "$file references '$it'" }
                    }
                    .toList()
            }

        if (offenders.isNotEmpty()) {
            fail(
                "These packages must stay broker-neutral so they can move to surf-broker " +
                        "unchanged:\n" + offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Run and see it fail or pass**

Run: `./gradlew :surf-rabbitmq-core:test --tests '*SharedPackagePurityTest*'`
Expected before the move: passes trivially, because the packages do not exist yet. That is
acceptable — it becomes meaningful in Step 3 and guards from then on.

- [ ] **Step 3: Move the neutral code**

```bash
cd surf-rabbitmq-core/src/main/kotlin/dev/slne/surf/rabbitmq

mkdir -p shared/serialization shared/dispatch shared/config platform

git mv common/util/KotlinSerializerCache.kt shared/serialization/
git mv common/util/KotlinSerializerNameCache.kt shared/serialization/
```

Update the `package` line in each moved file to match its new directory, and fix imports at
every call site. Repeat for the invoker (`RabbitListenerHandlerTemplate.java`,
`RabbitListenerMethodHandleProvider.java`) into `shared/dispatch` — renaming them to
`HandlerTemplate` / `HandlerMethodHandleProvider`, since the names must not say "Rabbit".

Move the Velocity reflection proxies from `surf-rabbitmq-velocity` into `platform`, which is
where the byte-identical duplicates with surf-redis live.

- [ ] **Step 4: Run the purity test and the full suite**

Run: `./gradlew test -PskipIntegration`
Expected: `BUILD SUCCESSFUL`. If the purity test fails, a moved file still names a RabbitMQ
type — rename the type or move the file back out of the neutral package.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: isolate broker-neutral code into shared and platform packages

Enforced by SharedPackagePurityTest so the later extraction into
surf-broker stays a move rather than a redesign."
```

---

### Task 10: Reconcile the spec and README

The plan changed two decisions from the spec. Leaving the spec stale would mislead the next reader.

**Files:**
- Modify: `docs/superpowers/specs/2026-07-29-rabbitmq-topology-redesign-design.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: everything above
- Produces: nothing

- [ ] **Step 1: Correct the event queue names in the spec**

In the queue table, replace `surf.events.<instanceId>` with `surf.events.instance.<instanceId>`
and `surf.events.<service>` with `surf.events.shared.<service>`. Add below the table:

```markdown
Event queues carry an extra `instance.` or `shared.` segment so that a service and an instance
of the same name cannot collide on one queue.
```

- [ ] **Step 2: Document the API change in the README**

Add after the intro:

````markdown
## Migrating from 1.6.x

`ClientRabbitMQApi` and `ServerRabbitMQApi` are replaced by a single `SurfRabbitApi`. Version
2.0 is **not wire-compatible** with 1.6.x: all services must be deployed together.

```kotlin
// before
val api = ServerRabbitMQApi.create("surf-factions", dataPath)
api.registerRpcService<FactionService>(FactionServiceImpl)
api.freezeAndConnect()

// after
val api = SurfRabbitApi.builder("surf-factions", dataPath).build()
api.registerService<FactionService>(FactionServiceImpl)
api.freezeAndConnect()
```

A client no longer needs one API instance per target service:

```kotlin
// before - two instances, two TCP connections
val factions = ClientRabbitMQApi.create("surf-factions", dataPath)
val punish   = ClientRabbitMQApi.create("surf-punish", dataPath)

// after - one instance, one connection
val rabbit = SurfRabbitApi.builder("lobby", dataPath).build()
val factions = rabbit.rpc<FactionService>()
val punish   = rabbit.rpc<PunishService>()
```
````

- [ ] **Step 3: Commit**

```bash
git add docs README.md
git commit -m "docs: reconcile spec with implemented queue naming, add migration guide"
```

---

## Done when

- [ ] `./gradlew build -PskipIntegration` succeeds
- [ ] With Docker: `./gradlew build` succeeds, all integration tests pass
- [ ] Six modules are now `surf-rabbitmq-api` and `surf-rabbitmq-core`
- [ ] One client reaches two services over one TCP connection (Task 8)
- [ ] Three instances share a queue and handle each message exactly once (Task 8)
- [ ] `SharedPackagePurityTest` passes

## Deliberately out of scope

- **Events, fire-and-forget, TTL split** — Plan 3.
- **DLQ, retry queues, return listener, circuit breaker wiring** — Plan 4.
- **KSP `@RpcService(service = ...)`** — Plan 4. Until then, `rpc()` requires the explicit
  service override argument.
- **Migrating `surf-rabbitmq-test`** — Plan 4.
- **Connection recovery and Netty transport setup** — untouched by design; it works.
