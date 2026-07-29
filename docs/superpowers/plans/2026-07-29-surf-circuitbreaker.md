# surf-circuitbreaker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Do NOT use subagent-driven-development.** The repository owner's global instructions forbid delegating work to subagents.

**Goal:** Build a standalone `surf-circuitbreaker` module that stops callers from repeatedly waiting on an unreachable dependency, reusable from surf-rabbitmq, surf-broker and surf-redis without modification.

**Architecture:** A single `CircuitBreaker` class holding a three-state machine (`CLOSED` → `OPEN` → `HALF_OPEN`). State transitions happen under a lock; the guarded call runs outside it. Time is read through an injected `Clock` so tests advance time instead of sleeping. Which throwables count as failures is decided by an injected predicate, so transport errors can trip the breaker while business exceptions pass through untouched.

**Tech Stack:** Kotlin (JVM toolchain 25), kotlinx.coroutines, JUnit 5.

## Global Constraints

- JVM toolchain is **25**, configured by the `dev.slne.surf.api.gradle.core` plugin. Do not override it.
- `gradle.properties` sets `kotlin.stdlib.default.dependency=false`. The stdlib arrives through the surf plugin; do not add it manually.
- The module **must not** reference RabbitMQ, `com.rabbitmq.*`, or any `dev.slne.surf.rabbitmq.*` type. This is enforced by a test in Task 5.
- Package root is `dev.slne.surf.circuitbreaker`.
- The Gradle plugin does **not** configure `useJUnitPlatform()`. Every module that has tests must set it explicitly.
- Root `build.gradle.kts` applies an `optIn` for `dev.slne.surf.rabbitmq.api.InternalRabbitMQ` to all subprojects except those matching `surf-rabbitmq-test`. This is harmless for this module (the annotation is simply unused) — do not modify the root build for it.
- Default values, copied verbatim from the spec: `failureThreshold = 5`, `openDuration = 30.seconds`.
- Commit after every task. Do not squash tasks into one commit.

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | Register the new module |
| `gradle/libs.versions.toml` | JUnit and coroutines-test versions |
| `surf-circuitbreaker/build.gradle.kts` | Module build, test platform wiring |
| `.../circuitbreaker/CircuitState.kt` | The three states |
| `.../circuitbreaker/CircuitOpenException.kt` | Thrown when a call is rejected |
| `.../circuitbreaker/CircuitBreaker.kt` | State machine and guarded execution |
| `.../circuitbreaker/CircuitBreakerRegistry.kt` | One breaker per name |
| `src/test/.../MutableClock.kt` | Test clock that advances on command |
| `src/test/.../CircuitBreakerTest.kt` | State machine behaviour |
| `src/test/.../CircuitBreakerConcurrencyTest.kt` | Half-open single-probe guarantee |
| `src/test/.../CircuitBreakerRegistryTest.kt` | Registry identity |
| `src/test/.../NoRabbitMqDependencyTest.kt` | Reusability constraint |

---

### Task 1: Module scaffold with a working test run

Creates the module and proves the test infrastructure actually executes. The repository has zero tests today, so nothing about the test setup can be assumed to work.

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Create: `surf-circuitbreaker/build.gradle.kts`
- Create: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/ScaffoldTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a `:surf-circuitbreaker` Gradle module whose `test` task runs JUnit 5

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, add after the `include("surf-rabbitmq-ksp")` line:

```kotlin
include("surf-circuitbreaker")
```

- [ ] **Step 2: Add test dependency versions**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
junit = "5.11.4"
coroutines = "1.10.2"
```

Add to `[libraries]`:

```toml
junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
```

`junit-jupiter` and `junit-platform-launcher` intentionally carry no version — the BOM supplies it.

- [ ] **Step 3: Create the module build file**

Create `surf-circuitbreaker/build.gradle.kts`:

```kotlin
import dev.slne.surf.api.gradle.util.slneReleases

plugins {
    id("dev.slne.surf.api.gradle.core")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)
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

- [ ] **Step 4: Write a scaffold test that must fail**

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/ScaffoldTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test
    fun `test infrastructure runs and can fail`() {
        assertEquals(1, 2, "intentional failure proving tests execute")
    }
}
```

- [ ] **Step 5: Run it and confirm it FAILS**

Run: `./gradlew :surf-circuitbreaker:test`
Expected: build fails, output names `ScaffoldTest > test infrastructure runs and can fail FAILED`.

If instead the build reports `NO-SOURCE` or "no tests found", the test wiring is broken — fix that before continuing. A passing build here is also a failure of this step: it means the test never ran.

If `kotlin.test.assertEquals` does not resolve, add `testImplementation(kotlin("test"))` to the dependencies block and re-run.

- [ ] **Step 6: Make it pass**

Change the assertion to:

```kotlin
        assertEquals(1, 1, "test infrastructure works")
```

- [ ] **Step 7: Run and confirm it PASSES**

Run: `./gradlew :surf-circuitbreaker:test`
Expected: `BUILD SUCCESSFUL`, one test passed.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts gradle/libs.versions.toml surf-circuitbreaker/
git commit -m "build: add surf-circuitbreaker module with JUnit 5 test setup"
```

---

### Task 2: States, exception, and the test clock

Three small types with no logic, plus the clock the later tests depend on. Kept together because none is independently useful and none carries behaviour worth its own review gate.

**Files:**
- Create: `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitState.kt`
- Create: `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitOpenException.kt`
- Create: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/MutableClock.kt`
- Test: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/MutableClockTest.kt`

**Interfaces:**
- Consumes: the module from Task 1
- Produces:
  - `enum class CircuitState { CLOSED, OPEN, HALF_OPEN }`
  - `class CircuitOpenException(breakerName: String) : IllegalStateException`, property `breakerName: String`
  - `class MutableClock(instant: Instant, zone: ZoneId) : Clock` with `fun advance(duration: kotlin.time.Duration)`

- [ ] **Step 1: Write the failing test for the clock**

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/MutableClockTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class MutableClockTest {
    @Test
    fun `advance moves the clock forward by the given duration`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start, ZoneOffset.UTC)

        assertEquals(start, clock.instant())

        clock.advance(30.seconds)

        assertEquals(start.plusSeconds(30), clock.instant())
    }

    @Test
    fun `clock does not move on its own`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val clock = MutableClock(start, ZoneOffset.UTC)

        repeat(1000) { clock.instant() }

        assertEquals(start, clock.instant())
    }
}
```

- [ ] **Step 2: Run and confirm it FAILS**

Run: `./gradlew :surf-circuitbreaker:test --tests '*MutableClockTest*'`
Expected: compilation error, `Unresolved reference: MutableClock`.

- [ ] **Step 3: Implement the three types**

Create `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitState.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

/**
 * The state of a [CircuitBreaker].
 *
 * A breaker starts [CLOSED]. Consecutive failures move it to [OPEN], where calls are
 * rejected without being attempted. After the configured open duration it becomes
 * [HALF_OPEN] and admits a single probe call to test whether the dependency recovered.
 */
enum class CircuitState {
    /** Calls pass through. Failures are counted. */
    CLOSED,

    /** Calls are rejected immediately with [CircuitOpenException]. */
    OPEN,

    /** A single probe call is admitted; all others are rejected. */
    HALF_OPEN
}
```

Create `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitOpenException.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import java.io.Serial

/**
 * Thrown when a call is rejected because the breaker is [CircuitState.OPEN], or because
 * another probe is already in flight while [CircuitState.HALF_OPEN].
 *
 * The guarded block was **not** executed when this is thrown.
 */
class CircuitOpenException(
    val breakerName: String
) : IllegalStateException("Circuit breaker '$breakerName' is open") {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}
```

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/MutableClock.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * A [Clock] that only moves when [advance] is called.
 *
 * Lets breaker timeout behaviour be tested without real waiting, which keeps the
 * suite fast and removes timing flakiness.
 */
class MutableClock(
    instant: Instant,
    private val zone: ZoneId
) : Clock() {
    private val current = AtomicReference(instant)

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(current.get(), zone)

    override fun instant(): Instant = current.get()

    /** Moves the clock forward by [duration]. */
    fun advance(duration: kotlin.time.Duration) {
        current.updateAndGet { it.plusMillis(duration.inWholeMilliseconds) }
    }
}
```

- [ ] **Step 4: Run and confirm it PASSES**

Run: `./gradlew :surf-circuitbreaker:test --tests '*MutableClockTest*'`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
git add surf-circuitbreaker/src
git commit -m "feat(circuitbreaker): add circuit state, rejection exception and test clock"
```

---

### Task 3: The breaker state machine

The core of the module. Written test-first, one behaviour at a time.

**Files:**
- Create: `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitBreaker.kt`
- Test: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerTest.kt`

**Interfaces:**
- Consumes: `CircuitState`, `CircuitOpenException`, `MutableClock` from Task 2
- Produces:
  ```kotlin
  class CircuitBreaker(
      val name: String,
      private val failureThreshold: Int = 5,
      private val openDuration: Duration = 30.seconds,
      private val clock: Clock = Clock.systemUTC(),
      private val isFailure: (Throwable) -> Boolean = { true }
  ) {
      val state: CircuitState
      suspend fun <T> withBreaker(block: suspend () -> T): T
      fun reset()
  }
  ```

- [ ] **Step 1: Write the failing tests**

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerTest {

    private fun breaker(
        threshold: Int = 3,
        clock: MutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC),
        isFailure: (Throwable) -> Boolean = { true }
    ) = clock to CircuitBreaker(
        name = "test",
        failureThreshold = threshold,
        openDuration = 30.seconds,
        clock = clock,
        isFailure = isFailure
    )

    private class Boom : RuntimeException("boom")
    private class Business : RuntimeException("business rule violated")

    @Test
    fun `closed breaker returns the block result`() = runTest {
        val (_, cb) = breaker()

        assertEquals("ok", cb.withBreaker { "ok" })
        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `failures below the threshold keep the breaker closed`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        repeat(2) {
            assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `a success resets the failure counter`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        cb.withBreaker { "ok" }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `reaching the threshold opens the breaker`() = runTest {
        val (_, cb) = breaker(threshold = 3)

        repeat(3) {
            assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        }

        assertEquals(CircuitState.OPEN, cb.state)
    }

    @Test
    fun `an open breaker rejects without running the block`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        var executions = 0

        assertFailsWith<Boom> { cb.withBreaker { executions++; throw Boom() } }
        assertFailsWith<CircuitOpenException> { cb.withBreaker { executions++; "never" } }

        assertEquals(1, executions, "the block must not run while the breaker is open")
    }

    @Test
    fun `the rejection names the breaker`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        val thrown = assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }
        assertEquals("test", thrown.breakerName)
    }

    @Test
    fun `the breaker stays open for the full open duration`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        clock.advance(29.seconds)

        assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }
        assertEquals(CircuitState.OPEN, cb.state)
    }

    @Test
    fun `after the open duration a probe is admitted`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        clock.advance(30.seconds)

        assertEquals("probe", cb.withBreaker { "probe" })
    }

    @Test
    fun `a successful probe closes the breaker`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)

        cb.withBreaker { "probe" }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `a failed probe reopens the breaker and restarts the timer`() = runTest {
        val (clock, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertEquals(CircuitState.OPEN, cb.state)

        clock.advance(29.seconds)
        assertFailsWith<CircuitOpenException> { cb.withBreaker { "never" } }

        clock.advance(1.seconds)
        assertEquals("probe", cb.withBreaker { "probe" })
    }

    @Test
    fun `throwables rejected by the predicate do not count`() = runTest {
        val (_, cb) = breaker(threshold = 2, isFailure = { it !is Business })

        repeat(10) {
            assertFailsWith<Business> { cb.withBreaker { throw Business() } }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `business failures do not reset counted failures`() = runTest {
        val (_, cb) = breaker(threshold = 2, isFailure = { it !is Business })

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertFailsWith<Business> { cb.withBreaker { throw Business() } }
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }

        assertEquals(
            CircuitState.OPEN, cb.state,
            "an ignored throwable must not clear the counter the way a success does"
        )
    }

    @Test
    fun `cancellation never counts as a failure`() = runTest {
        val (_, cb) = breaker(threshold = 1)

        repeat(5) {
            assertFailsWith<CancellationException> {
                cb.withBreaker { throw CancellationException("cancelled") }
            }
        }

        assertEquals(CircuitState.CLOSED, cb.state)
    }

    @Test
    fun `reset returns an open breaker to closed`() = runTest {
        val (_, cb) = breaker(threshold = 1)
        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertEquals(CircuitState.OPEN, cb.state)

        cb.reset()

        assertEquals(CircuitState.CLOSED, cb.state)
        assertEquals("ok", cb.withBreaker { "ok" })
    }

    @Test
    fun `the original exception propagates unchanged`() = runTest {
        val (_, cb) = breaker(threshold = 5)
        val thrown = assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        assertTrue(thrown.message == "boom")
    }
}
```

- [ ] **Step 2: Run and confirm they FAIL**

Run: `./gradlew :surf-circuitbreaker:test --tests '*CircuitBreakerTest*'`
Expected: compilation error, `Unresolved reference: CircuitBreaker`.

- [ ] **Step 3: Implement the breaker**

Create `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitBreaker.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Stops a caller from repeatedly waiting on a dependency that is already known to be down.
 *
 * After [failureThreshold] consecutive counted failures the breaker opens and rejects calls
 * immediately with [CircuitOpenException] for [openDuration]. It then admits a single probe
 * call: if the probe succeeds the breaker closes, otherwise it opens again for another
 * [openDuration].
 *
 * Which throwables count is decided by [isFailure]. This matters when guarding a remote call:
 * a transport error means the dependency is unreachable and should trip the breaker, while a
 * business exception proves the dependency is alive and must not. [CancellationException] is
 * never counted, regardless of [isFailure] — it signals that the *caller* went away.
 *
 * Time is read through [clock] so that timeout behaviour can be tested without waiting.
 *
 * Instances are safe to use from multiple coroutines. The guarded block runs outside the
 * internal lock, so a slow call never blocks state transitions of other callers.
 *
 * ```kotlin
 * val breaker = CircuitBreaker(
 *     name = "surf-punish",
 *     isFailure = { it is IOException }
 * )
 *
 * val result = breaker.withBreaker { remoteCall() }
 * ```
 */
class CircuitBreaker(
    val name: String,
    private val failureThreshold: Int = 5,
    private val openDuration: Duration = 30.seconds,
    private val clock: Clock = Clock.systemUTC(),
    private val isFailure: (Throwable) -> Boolean = { true }
) {
    init {
        require(failureThreshold > 0) { "failureThreshold must be positive, was $failureThreshold" }
        require(openDuration.isPositive()) { "openDuration must be positive, was $openDuration" }
    }

    private val lock = Any()

    private var currentState: CircuitState = CircuitState.CLOSED
    private var consecutiveFailures: Int = 0
    private var openedAt: Instant? = null
    private var probeInFlight: Boolean = false

    /**
     * The current state.
     *
     * Reading this transitions [CircuitState.OPEN] to [CircuitState.HALF_OPEN] if
     * [openDuration] has elapsed, so the value always reflects what the next call would do.
     */
    val state: CircuitState
        get() = synchronized(lock) {
            refreshState()
            currentState
        }

    /**
     * Runs [block] unless the breaker is currently rejecting calls.
     *
     * @throws CircuitOpenException if the call was rejected. [block] did not run.
     */
    suspend fun <T> withBreaker(block: suspend () -> T): T {
        acquirePermit()

        return try {
            val result = block()
            onSuccess()
            result
        } catch (cause: Throwable) {
            onFailure(cause)
            throw cause
        }
    }

    /** Forces the breaker back to [CircuitState.CLOSED] and clears the failure counter. */
    fun reset() {
        synchronized(lock) {
            currentState = CircuitState.CLOSED
            consecutiveFailures = 0
            openedAt = null
            probeInFlight = false
        }
    }

    private fun acquirePermit() {
        synchronized(lock) {
            refreshState()

            when (currentState) {
                CircuitState.CLOSED -> Unit

                CircuitState.OPEN -> throw CircuitOpenException(name)

                CircuitState.HALF_OPEN -> {
                    // Exactly one probe is allowed to test the dependency. Admitting more
                    // would send a burst at a service that is likely still recovering.
                    if (probeInFlight) throw CircuitOpenException(name)
                    probeInFlight = true
                }
            }
        }
    }

    private fun onSuccess() {
        synchronized(lock) {
            currentState = CircuitState.CLOSED
            consecutiveFailures = 0
            openedAt = null
            probeInFlight = false
        }
    }

    private fun onFailure(cause: Throwable) {
        synchronized(lock) {
            // A cancelled caller says nothing about the dependency's health.
            if (cause is CancellationException || !isFailure(cause)) {
                probeInFlight = false
                return
            }

            if (currentState == CircuitState.HALF_OPEN) {
                open()
                return
            }

            consecutiveFailures++
            if (consecutiveFailures >= failureThreshold) {
                open()
            }
        }
    }

    private fun open() {
        currentState = CircuitState.OPEN
        openedAt = clock.instant()
        probeInFlight = false
    }

    /** Must be called while holding [lock]. */
    private fun refreshState() {
        if (currentState != CircuitState.OPEN) return

        val since = openedAt ?: return
        val elapsed = java.time.Duration.between(since, clock.instant())

        if (elapsed.toMillis() >= openDuration.inWholeMilliseconds) {
            currentState = CircuitState.HALF_OPEN
            probeInFlight = false
        }
    }
}
```

- [ ] **Step 4: Run and confirm they PASS**

Run: `./gradlew :surf-circuitbreaker:test --tests '*CircuitBreakerTest*'`
Expected: `BUILD SUCCESSFUL`, 15 tests passed.

If `business failures do not reset counted failures` fails, the cause is `onFailure` clearing
the counter for ignored throwables. It must only clear `probeInFlight` and return.

- [ ] **Step 5: Commit**

```bash
git add surf-circuitbreaker/src
git commit -m "feat(circuitbreaker): implement three-state breaker with injectable clock"
```

---

### Task 4: Half-open admits exactly one probe under concurrency

The single-probe guarantee is the one property a sequential test cannot prove, and the one that matters most in production: without it, a recovering service is hit by every waiting caller at once.

**Files:**
- Test: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerConcurrencyTest.kt`

**Interfaces:**
- Consumes: `CircuitBreaker` from Task 3
- Produces: nothing (test-only)

- [ ] **Step 1: Write the failing test**

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerConcurrencyTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerConcurrencyTest {

    private class Boom : RuntimeException("boom")

    @Test
    fun `only one of many concurrent callers is admitted as the probe`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val cb = CircuitBreaker(
            name = "test",
            failureThreshold = 1,
            openDuration = 30.seconds,
            clock = clock
        )

        assertFailsWith<Boom> { cb.withBreaker { throw Boom() } }
        clock.advance(30.seconds)
        assertEquals(CircuitState.HALF_OPEN, cb.state)

        val admitted = AtomicInteger()
        val rejected = AtomicInteger()

        // The probe blocks until released, so every other caller arrives while it is in
        // flight — exactly the race the single-probe rule has to survive.
        val release = CompletableDeferred<Unit>()

        val callers = (1..64).map {
            async(Dispatchers.Default) {
                try {
                    cb.withBreaker {
                        admitted.incrementAndGet()
                        release.await()
                        "ok"
                    }
                } catch (_: CircuitOpenException) {
                    rejected.incrementAndGet()
                }
            }
        }

        // Give the losers time to reach the breaker and be turned away.
        withContext(Dispatchers.Default) {
            while (rejected.get() < 63) {
                Thread.onSpinWait()
            }
        }

        release.complete(Unit)
        callers.awaitAll()

        assertEquals(1, admitted.get(), "exactly one probe may run")
        assertEquals(63, rejected.get(), "every other caller must be rejected")
        assertEquals(CircuitState.CLOSED, cb.state, "the successful probe closes the breaker")
    }

    @Test
    fun `concurrent failures do not overcount past the threshold`() = runBlocking {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val cb = CircuitBreaker(
            name = "test",
            failureThreshold = 5,
            openDuration = 30.seconds,
            clock = clock
        )

        val executions = AtomicInteger()

        (1..32).map {
            async(Dispatchers.Default) {
                runCatching {
                    cb.withBreaker {
                        executions.incrementAndGet()
                        throw Boom()
                    }
                }
            }
        }.awaitAll()

        assertEquals(CircuitState.OPEN, cb.state)
        // Once open, further callers must be turned away rather than reaching the block.
        assert(executions.get() <= 32) { "no caller may run the block twice" }
    }
}
```

- [ ] **Step 2: Run and confirm the behaviour**

Run: `./gradlew :surf-circuitbreaker:test --tests '*CircuitBreakerConcurrencyTest*'`
Expected: `BUILD SUCCESSFUL`, 2 tests passed.

This test is expected to pass against the Task 3 implementation — it guards the
`probeInFlight` flag against regression. If it *fails*, `acquirePermit` is not holding the
lock across the check-and-set of `probeInFlight`, which is a real defect. Fix it before
continuing.

If the spin-wait loop hangs, the breaker is admitting more than one caller: the assertion
`rejected.get() < 63` never becomes false because some callers were admitted instead of
rejected. That is the same defect.

- [ ] **Step 3: Commit**

```bash
git add surf-circuitbreaker/src/test
git commit -m "test(circuitbreaker): verify single-probe guarantee under concurrency"
```

---

### Task 5: Registry and the reusability constraint

One breaker per target name, plus the test that keeps the module extractable into `surf-broker` later.

**Files:**
- Create: `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerRegistry.kt`
- Test: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerRegistryTest.kt`
- Test: `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/NoRabbitMqDependencyTest.kt`

**Interfaces:**
- Consumes: `CircuitBreaker` from Task 3
- Produces:
  ```kotlin
  class CircuitBreakerRegistry(
      private val failureThreshold: Int = 5,
      private val openDuration: Duration = 30.seconds,
      private val clock: Clock = Clock.systemUTC(),
      private val isFailure: (Throwable) -> Boolean = { true }
  ) {
      fun forName(name: String): CircuitBreaker
      fun names(): Set<String>
      fun resetAll()
  }
  ```
  Plan 4 uses `forName(serviceName)` to obtain one breaker per target service.

- [ ] **Step 1: Write the failing tests**

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerRegistryTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds

class CircuitBreakerRegistryTest {

    private class Boom : RuntimeException("boom")

    private fun registry(clock: MutableClock) = CircuitBreakerRegistry(
        failureThreshold = 1,
        openDuration = 30.seconds,
        clock = clock
    )

    @Test
    fun `the same name yields the same breaker`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assertSame(reg.forName("surf-punish"), reg.forName("surf-punish"))
    }

    @Test
    fun `different names yield different breakers`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assert(reg.forName("surf-punish") !== reg.forName("surf-factions"))
    }

    @Test
    fun `one failing target does not open the breaker of another`() = runTest {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        val punish = reg.forName("surf-punish")
        val factions = reg.forName("surf-factions")

        assertFailsWith<Boom> { punish.withBreaker { throw Boom() } }

        assertEquals(CircuitState.OPEN, punish.state)
        assertEquals(CircuitState.CLOSED, factions.state)
        assertEquals("ok", factions.withBreaker { "ok" })
    }

    @Test
    fun `names lists every created breaker`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        reg.forName("a")
        reg.forName("b")
        reg.forName("a")

        assertEquals(setOf("a", "b"), reg.names())
    }

    @Test
    fun `resetAll closes every breaker`() = runTest {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        val a = reg.forName("a")
        val b = reg.forName("b")
        assertFailsWith<Boom> { a.withBreaker { throw Boom() } }
        assertFailsWith<Boom> { b.withBreaker { throw Boom() } }

        reg.resetAll()

        assertEquals(CircuitState.CLOSED, a.state)
        assertEquals(CircuitState.CLOSED, b.state)
    }

    @Test
    fun `the breaker is named after the target`() {
        val reg = registry(MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))

        assertEquals("surf-punish", reg.forName("surf-punish").name)
    }
}
```

Create `surf-circuitbreaker/src/test/kotlin/dev/slne/surf/circuitbreaker/NoRabbitMqDependencyTest.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.streams.asSequence
import kotlin.test.fail

/**
 * The module is meant to be reused unchanged from surf-broker and surf-redis. A single
 * RabbitMQ import would silently destroy that, and nothing else in the build would notice.
 */
class NoRabbitMqDependencyTest {

    private val forbidden = listOf(
        "com.rabbitmq",
        "dev.slne.surf.rabbitmq"
    )

    @Test
    fun `no source file references RabbitMQ`() {
        val sourceRoot = Path.of("src", "main", "kotlin")

        if (!Files.exists(sourceRoot)) {
            fail(
                "Expected sources at ${sourceRoot.toAbsolutePath()}. " +
                        "This test must run with the module directory as working directory."
            )
        }

        val offenders = Files.walk(sourceRoot).asSequence()
            .filter { it.extension == "kt" }
            .mapNotNull { file ->
                val hit = forbidden.firstOrNull { file.readText().contains(it) }
                hit?.let { "$file references '$it'" }
            }
            .toList()

        if (offenders.isNotEmpty()) {
            fail(
                "surf-circuitbreaker must stay free of RabbitMQ so it can be reused " +
                        "from surf-broker and surf-redis:\n" + offenders.joinToString("\n")
            )
        }
    }
}
```

- [ ] **Step 2: Run and confirm they FAIL**

Run: `./gradlew :surf-circuitbreaker:test --tests '*Registry*'`
Expected: compilation error, `Unresolved reference: CircuitBreakerRegistry`.

- [ ] **Step 3: Implement the registry**

Create `surf-circuitbreaker/src/main/kotlin/dev/slne/surf/circuitbreaker/CircuitBreakerRegistry.kt`:

```kotlin
package dev.slne.surf.circuitbreaker

import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds one [CircuitBreaker] per named target, created on first use.
 *
 * Per-target isolation is the point: one unreachable dependency must not cause calls to a
 * healthy one to be rejected. A single shared breaker would do exactly that.
 *
 * ```kotlin
 * val registry = CircuitBreakerRegistry(isFailure = { it is IOException })
 *
 * registry.forName("surf-punish").withBreaker { call() }
 * ```
 */
class CircuitBreakerRegistry(
    private val failureThreshold: Int = 5,
    private val openDuration: Duration = 30.seconds,
    private val clock: Clock = Clock.systemUTC(),
    private val isFailure: (Throwable) -> Boolean = { true }
) {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    /** Returns the breaker for [name], creating it on first call. */
    fun forName(name: String): CircuitBreaker = breakers.computeIfAbsent(name) {
        CircuitBreaker(
            name = it,
            failureThreshold = failureThreshold,
            openDuration = openDuration,
            clock = clock,
            isFailure = isFailure
        )
    }

    /** The names of all breakers created so far. */
    fun names(): Set<String> = breakers.keys.toSet()

    /** Closes every breaker and clears its failure counter. */
    fun resetAll() {
        breakers.values.forEach(CircuitBreaker::reset)
    }
}
```

- [ ] **Step 4: Run the whole module suite**

Run: `./gradlew :surf-circuitbreaker:test`
Expected: `BUILD SUCCESSFUL`, all tests pass (2 clock + 15 breaker + 2 concurrency + 6 registry + 1 dependency = 26).

If `NoRabbitMqDependencyTest` fails with the "working directory" message, Gradle is not
running the test from the module directory. Add to `surf-circuitbreaker/build.gradle.kts`
inside `tasks.test { }`:

```kotlin
    workingDir = projectDir
```

- [ ] **Step 5: Commit**

```bash
git add surf-circuitbreaker
git commit -m "feat(circuitbreaker): add per-target registry and reusability guard"
```

---

### Task 6: Module documentation

The module is meant to be picked up by two other projects. Without a README, the next reader has to infer the failure-predicate contract from the source.

**Files:**
- Create: `surf-circuitbreaker/README.md`

**Interfaces:**
- Consumes: everything from Tasks 2–5
- Produces: nothing

- [ ] **Step 1: Write the README**

Create `surf-circuitbreaker/README.md`:

````markdown
# surf-circuitbreaker

Stops a caller from repeatedly waiting on a dependency that is already known to be down.

The module has no dependency on RabbitMQ, Redis or Minecraft and is intended for reuse from
`surf-rabbitmq`, `surf-broker` and `surf-redis`. A test enforces this.

## Why

Without a breaker, every call to an unreachable service waits for the full request timeout.
With a Minecraft server issuing many short calls, the thread and coroutine cost of those
waits piles up and the server degrades because of a dependency it does not even need.

## States

```
CLOSED ──failureThreshold consecutive failures──▶ OPEN
  ▲                                                │
  │                                       openDuration elapsed
  │                                                ▼
  └────────── probe succeeded ──────────────  HALF_OPEN
                                                   │
                                          probe failed
                                                   ▼
                                                 OPEN
```

`HALF_OPEN` admits **exactly one** probe call. Everything else is rejected until the probe
settles, so a recovering service is not hit by every waiting caller at once.

## Usage

```kotlin
val registry = CircuitBreakerRegistry(
    failureThreshold = 5,
    openDuration = 30.seconds,
    isFailure = { it is IOException }
)

val result = registry.forName("surf-punish").withBreaker {
    remoteCall()
}
```

`CircuitOpenException` means the call was **rejected without running**. Any other exception
comes from the guarded block and propagates unchanged.

## Choosing `isFailure`

This is the setting that decides whether the breaker helps or hurts.

Count only errors that mean **the dependency is unreachable** — connection loss, unroutable
message, timeout. Do **not** count exceptions the dependency deliberately returned: those
prove it is alive and answering. A breaker that opens on business errors takes down a
perfectly healthy service.

`CancellationException` is never counted, regardless of the predicate. It says the caller
went away, not that the dependency is unhealthy.

## Testing against it

Pass a `Clock` to avoid real waiting:

```kotlin
val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
val breaker = CircuitBreaker(name = "t", openDuration = 30.seconds, clock = clock)

clock.advance(30.seconds)   // breaker is now HALF_OPEN
```

`MutableClock` lives in this module's test sources. Copy it, or promote it to `src/main` if
another module needs it.
````

- [ ] **Step 2: Verify the full build still works**

Run: `./gradlew :surf-circuitbreaker:build`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add surf-circuitbreaker/README.md
git commit -m "docs(circuitbreaker): document states, usage and failure predicate"
```

---

## Done when

- [ ] `./gradlew :surf-circuitbreaker:build` succeeds
- [ ] 26 tests pass
- [ ] `NoRabbitMqDependencyTest` passes, keeping the module extractable
- [ ] No Docker required for any of it

## Deliberately out of scope

- **Wiring into surf-rabbitmq.** Happens in Plan 4, once `SurfRabbitApi` exists. This plan
  produces the module only.
- **Metrics and state-change listeners.** Add when something actually consumes them.
- **Sliding-window failure rates.** Consecutive-failure counting is sufficient for the
  stated purpose; a window is a larger design with its own tuning problem.
- **Bulkheads and concurrency limits.** Separate concern, separate module if ever needed.
