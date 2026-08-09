# surf-eventbus — full repository audit

Date: 2026-08-01 · Branch: `feat/surf-event-bus` (head `e4863ad`) · Version `2.0.0`
Scope: every tracked file (340), all five modules, build, CI, docs, tests.

---

## Executive Summary

This is a genuinely well-thought-out design with unusually good *intent* documentation. Almost every
non-obvious decision carries a KDoc explaining what it replaced and why. That is rare and valuable,
and it is the main reason this audit could be thorough.

It is also a codebase mid-consolidation, and the seams show. Two former projects (`surf-rabbitmq`,
`surf-redis`) were merged under one name and one package root, but only the *naming* was unified —
the two halves still disagree on concurrency model (coroutines vs Reactor), service lookup (five
different idioms), configuration (per-plugin layering works for Rabbit, silently does not for
Redis), lifecycle, error handling, and testing strategy. The KDoc frequently describes the intended
end state rather than the current one, which makes the drift harder to see, not easier.

The most serious problems are not stylistic. Several documented guarantees do not hold in the
current code: suspend event handlers can never run, the Redis event transport never unsubscribes,
the audit-report correlation id is regenerated on the first hop, and event/query audit rows never
reach the audit service at all. None of these are visible without reading the code, because each
failure mode is *silent* by design — which is exactly the class of bug this project's own README
warns about.

| Dimension                  | Score | One-line justification                                                                                                                                                                |
|----------------------------|------:|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Architecture               |  5/10 | Clear vision, clean module split; undermined by god classes, 5 service-lookup idioms, two concurrency paradigms, impl types in the public ABI                                         |
| Maintainability            |  5/10 | Excellent rationale comments; but ~1,900 lines of near-duplicate code, three copies of descriptor lookup, two copies of transport detection                                           |
| Scalability                |  5/10 | Sound broker topology; ~50 threads/process, hardcoded pools, per-event allocation in the dispatcher hot path, wildcard subscribers receive all fleet traffic                          |
| Test Coverage              |  4/10 | 277 tests, strong on protocol/topology/retry; zero on ~2,000 lines of Redis cache/sync, publisher, consumer, connection provider. **No CI runs any of them.**                         |
| Kotlin Idiomatic Usage     |  6/10 | Mostly idiomatic; spoiled by `Pair<A, B?>` as a domain type, `Array<out T>` in interfaces, a `@Serializable` class implementing `CoroutineScope`, partial `equals` on a data class    |
| Performance                |  5/10 | Careful in the chunker and stream parser; `waitForConfirmsOrDie` serialises publishing, `EventTopics.matches` allocates per segment per event, 3 collection copies per received event |
| Security                   |  5/10 | Good: no `x-death` trust, bounded chunk metadata, type-load guard. Bad: unbounded remote-keyed caches, full payloads + raw wire text into logs, no TLS anywhere, passwords via URI    |
| Developer Experience       |  4/10 | No test CI, no linter, no formatter, no local dev instructions, no `docker-compose`, stale KSP README and `@RpcService` KDoc showing code that will not compile                       |
| Technical Debt (10 = none) |  4/10 | Large and concentrated: the Redis half is a lift-and-shift that was renamed rather than integrated                                                                                    |

---

## Critical Issues — fix before this merges

### C1. No CI runs the tests

`.github/workflows/publish.yml` is the only workflow. It triggers on push to `version/*` and calls a
shared build-publish-release workflow. **There is no pull-request build, no test run, no ABI check,
no lint.** This branch — 21 commits of breaking rewrite — has never been verified by anything but a
developer's local machine, and `-PskipIntegration` plus "Docker not available → skip" means even a
local run can silently verify nothing (`RequiresDocker.kt:30`, message: *"integration test skipped,
NOT verified"* — the author knew).

*Impact:* every other finding in this document could have been caught, or could regress tomorrow.
*Fix:* a `ci.yml` on `pull_request` + `push` running `./gradlew check` with a Docker-enabled runner;
fail the build when the integration tag is skipped on CI (`-PrequireIntegration`). *Complexity:*
low. *Worth doing:* unconditionally, first.

### C2. `suspend` `@SurfSubscribe` handlers register successfully and can never run

`EventSubscriptionRegistry.register` (line 30-31) explicitly detects suspend handlers — it strips
the trailing `Continuation` parameter before counting arguments, so `suspend fun onX(e: E)` passes
validation and is registered. `EventDispatcher.invoke` (line 110-112) then calls
`method.invoke(listener, event)` with exactly one argument. A suspend method's JVM signature takes
two. Every delivery throws `IllegalArgumentException: wrong number of arguments`, is caught by the
containment block at line 73, and produces one `EVENT_HANDLER_FAILED` audit row per event.

The handler looks registered, `freeze()` passes, `connect()` passes, and nothing ever runs. This is
precisely the failure mode `EventTopics`' KDoc says validation exists to prevent.

*Fix:* either reject suspend handlers at registration with a clear message, or support them properly
(`kotlin.reflect.full.callSuspend`, or require a non-suspend adapter). Rejecting is the smaller
change and the honest one; supporting them is what users will expect from a coroutine-first bus.
*Complexity:* low to reject, medium to support. *Worth doing:* yes — silent no-op handlers are the
worst possible outcome.

### C3. `RedisEventTransport.disconnect()` does not unsubscribe, and

`connect()` races the subscription

Two defects in one class, both visible by comparing it to its sibling `RedisQueryTransport`, which
gets both right:

- **Subscription is not awaited.** `subscribeJson`/`subscribeBinary`/`subscribeJsonPattern`/
  `subscribeBinaryPattern` (lines 73-109) call `.subscribe()` on the `addListener` Mono and move on.
  `RedisQueryTransport.subscribeQuery` (line 89) calls `.awaitSingle()` and its comment states the
  reason verbatim: *"a query or reply published right after connect () must not race an in-flight
  SUBSCRIBE, or the message is gone for good — Pub/Sub has no redelivery."* The same hazard applies
  to events and is not handled.
- **Disconnect does not remove listeners.** `disconnect()` disposes the `Disposable` returned by
  `.subscribe()`. That subscription has already completed (it delivered the listener id); disposing
  it is a no-op. The Redis listener stays attached for the life of the `SurfRedisApi`. The query
  transport keeps `(topic, listenerId)` pairs and calls `removeListener(id)` — the correct approach.

*Impact:* events published immediately after `freezeAndConnect()` may be lost; a disconnected bus
keeps receiving and dispatching events into a scope that is about to be cancelled; in tests, buses
from an earlier test answer a later one (the `RedisBusSuite` KDoc already blames this class of
problem on "the harness"). *Fix:* mirror `RedisQueryTransport` exactly — `awaitSingle()` the
listener id, track `(topic, id)`,
`removeListener` on disconnect. Better: extract the shared subscribe/publish plumbing so the two
cannot diverge again (see X2). *Complexity:* low. *Worth doing:* yes.

### C4. The audit correlation id is regenerated on the first failure, breaking the grouping it exists for

`AuditMessageIdentity.of(properties)` returns the `x-surf-audit-message-id` header, or **a fresh
random UUID** when absent (`AuditMessageIdentity.kt:18-19`). In `RetryPublisher.handleFailure`:

```kotlin
val messageId = AuditMessageIdentity.of(properties)      // random UUID #1 — used to stamp attempt 2
auditSink.report(reports.handlerFailed(properties, …))   // base() calls of() again → random UUID #2
```

`AuditReports.base` (line 86) independently calls `AuditMessageIdentity.of(properties)`. On the
first failure — the only time the header is absent, and therefore every message's first failure —
the report carries a different id than the one stamped onto the retry. The whole point of the
mechanism, stated in its own KDoc (*"the database would show four unrelated incidents instead of one
message that failed four times"*), fails on hop one.

Related: `AuditMessageIdentity.stamp()` exists, is tested (`AuditMessageIdentityTest`), and is used
by **nothing in production** — `RetryPublisher.withNextAttempt` re-implements it inline. The test
suite validates a function the product does not call.

*Fix:* resolve the id once and thread it through (`AuditReports.base(messageUuid, …)`); delete the
inline copy in `withNextAttempt` in favour of `stamp()`. *Complexity:* low. *Worth doing:* yes.

### C5. Event and query audit rows never reach the audit service

`SurfEventBusImpl` hardcodes `auditSink = LoggingAuditSink` for both dispatchers (lines 56, 65) with
no injection point. `RabbitAuditSink` — the class that actually publishes `AuditReport`s to
`surf-eventbus-audit` — is constructed only inside `RabbitConnectionImpl` and is only reachable from
the RabbitMQ paths. So on a bus with `.withRabbit().withRedis()`, `EVENT_HANDLER_FAILED`,
`QUERY_HANDLER_FAILED` and `UNKNOWN_EVENT_TYPE` are logged and dropped.

The README's audit table lists `HANDLER_FAILED` and `QUERY_HANDLER_FAILED` without distinction and
says every loss "writes an audit report instead". For three of seven `AuditKind` values that is not
true today.

*Fix:* pass the sink into `SurfEventBusImpl` from the builder — `rabbitApi?.connection?.auditSink ?:
LoggingAuditSink` — or introduce a `CompositeAuditSink`. Note this also removes the last reason
`SurfEventBusImpl` cannot be unit-tested with a fake sink. *Complexity:* low. *Worth doing:* yes.

### C6. Redis reads its per-plugin config from the wrong directory, and from a process-wide singleton

`RedisConfigAccess.kt`:

```kotlin
val redisConfig: RedisSettings by lazy {
    val dataPath = EventBusInstance.orNull()?.dataPath          // the PLATFORM plugin's folder
    …
    resolveEventBusConfig(
        global = EventBusConfigFiles.global(dataPath),
        plugin  = EventBusConfigFiles.plugin(dataPath),         // …used for the plugin layer too
    ).redis
}
```

Compare `SurfRabbitApiBuilder.resolveConfig` (line 103-107), which correctly passes
`platform.dataPath` for the global layer and the *consumer plugin's* `dataPath` for the plugin
layer. For Redis both layers read the same file in the same directory, so `eventbus-plugin.yml` in a
plugin's own folder is never read. The README states: *"Both transports use all four layers,
identically."* They do not.

Worse, `redisConfig` is a top-level `by lazy` — a process-wide singleton fixed on first touch. This
is the exact anti-pattern `EventBusConfigFiles`' KDoc says was deliberately removed:

> *"There is deliberately no `getConfig()` here. The old `GlobalRabbitMQConfig.getConfig()` was a
> mutable process-wide singleton … Both layers are passed to `resolveEventBusConfig` explicitly
> instead."*

Rabbit obeys that rule; Redis has the singleton the rule was written against. It is also why the
production `withRedis()` path is untestable — `RedisBusSuite`'s KDoc says so outright, and every
Redis suite therefore exercises the `withRedis(event, query)` test seam instead of the real wiring.

*Fix:* delete the global; resolve `RedisSettings` where `RabbitMQSettings` is resolved (in the
builder) and pass it into `RedisTransportProviderImpl`/`RedisComponentProviderImpl` as a parameter.
This single change fixes the layering bug, removes the singleton, and makes the production path
testable. *Complexity:* medium (touches the SPI signatures). *Worth doing:* yes — highest
architectural value per unit of effort in the repo.

### C7. The published API exposes relocated third-party types

`surf-eventbus-api/api/surf-eventbus-api.api` — the frozen ABI — contains:

```
public fun redisURI ()Lorg/redisson/misc/RedisURI;
public final fun getRedisson ()Lorg/redisson/api/RedissonClient;
public abstract fun decode (Lio/netty/buffer/ByteBuf;)…
public abstract interface class …/SimpleRedisCache : …, reactor/core/Disposable
public abstract class …/AbstractCodec : org/redisson/client/codec/BaseCodec, …
```

Meanwhile `build.gradle.kts:43-57` relocates `org.redisson` → `dev.slne.surf.eventbus.libs.redisson`
and `io.netty` → `dev.slne.surf.eventbus.shaded.io.netty` in every shadow jar, and the api module
publishes `from(components["shadow"])`. A consumer compiling against the published artifact gets
public signatures naming relocated classes; a consumer compiling against the unrelocated module gets
different ones. `reactor.core` is *excluded* from the redisson dependency (`build.gradle.kts:35`)
yet appears in public supertypes.

*Impact:* the ABI validation this module runs is validating a surface that is not stable across
packaging. Redisson upgrades become breaking API changes for consumers. *Fix (in order of
preference):* (a) remove Redisson/Netty/Reactor from the api module's public surface —
`RedisApi.redisson`/`redissonReactive`/`redisOsType` become internal, `RedisCredentials
Provider` returns a plain `RedisAddress`, `Initializable`/`SyncStructure` become `suspend`-based;
(b)
failing that, stop relocating Redisson and declare it a normal `api` dependency. *Complexity:* high.
*Worth doing:* yes — this is the difference between a library and a bundle.

### C8. `SurfEventBusBuilder.build(environment)` accepts a parameter that is ignored

`SurfEventBusBuilder.kt:36` declares `fun build(environment: Map<String, String>? = null)`. The only
implementation, `SurfEventBusBuilderImpl.build` (line 47), never reads it. It is a public API
promise — "you can inject the environment" — that does nothing, and the resolver it would feed
(`resolveEventBusConfig(environment = …)`) does accept exactly such an override.

*Fix:* wire it through to `resolveEventBusConfig`, or delete the parameter. Wiring it is better: it
gives the config layering a test seam it currently lacks. *Complexity:* low. *Worth doing:* yes.

### C9. Redis OS detection is broken by over-escaped Lua

`RedisApi.fetchRedisOs` (line 391-410):

```kotlin
val lua = """
    local info = redis.call('INFO', 'server')
    return string.match(info, 'os:([^\\r\\n]+)')
""".trimIndent()
```

Kotlin raw strings do not process escapes, so Lua receives `'os:([^\\r\\n]+)'`. Lua's string literal
turns `\\` into a single backslash, giving the pattern `[^\r\n]` where `\` is a *literal backslash*
— Lua patterns escape with `%`, not `\`. The character class therefore excludes backslash, `r` and
`n`, not CR/LF. `os:Linux 5.15…` matches `"Li"`; `os:Windows…` matches `"Wi"`.

Consequently `os.contains("Windows")` is never true and `redisOsType` stays `null` on Windows. The
`os == null` branch that would have caught it is also never taken, because the match succeeds — with
the wrong value.

*Fix:* `'os:([^\r\n]+)'` (single backslashes in the raw string). Add a unit test asserting the
extracted value for representative `INFO server` output. *Complexity:* trivial. *Worth doing:* yes.

### C10. Blocking connection establishment inside a coroutine, inside a monitor

`RabbitConnectionProvider.awaitOpen` is `suspend` and calls `connection()` directly (line 83).
`connection()` takes `synchronized(lock)` and calls `factory.newConnection(connectionName)` — a
blocking TCP connect + AMQP handshake, bounded only by `connectionTimeout` (default 30 s).

This runs on whatever dispatcher the caller is on: `Dispatchers.Default` (a work-stealing pool sized
to CPU count) via `RabbitPublisher.publish` and `RabbitConsumer.getChannel`. Under a broker outage,
several publishers can park `Dispatchers.Default` threads for 30 s each while holding a monitor.

*Fix:* `withContext(Dispatchers.IO) { … }` around the connect, and prefer a `Mutex` over
`synchronized` so the suspension point is legal. Both are small changes with large blast-radius
reduction. *Complexity:* low. *Worth doing:* yes.

---

## High Priority

### H1. `SurfEventBus.disconnect()` has no failure containment

```kotlin
override suspend fun disconnect() {
    eventTransport?.disconnect()
    queryTransport?.disconnect()
    rabbitApi?.disconnect()
}
```

`connect()` immediately above it wraps each step in `runCatching` on failure — the author clearly
reasoned about half-connected states. `disconnect()` does not: the first throw leaks the Redis query
subscription, the AMQP connection, four consumer threads, two publisher threads and the audit sink's
scope. On Paper this happens during server shutdown, where it produces the "leaked RabbitClient"
warning and a **10-second `Thread.sleep`** (`RabbitClient.kt:238`). *Fix:* same `runCatching`
pattern, rethrowing the first failure with the rest suppressed.

### H2. `RabbitAuditSink`'s scope and drain loop are never cancelled

`RabbitAuditSink.kt:39` creates `CoroutineScope(Dispatchers.Default + SupervisorJob())` and launches
a `for (next in pending)` drain loop in `init`. Nothing cancels the scope or closes the channel —
`RabbitConnectionImpl.disconnect()` closes only the client. Every `SurfRabbitApi` ever constructed
leaks one coroutine and one channel for the life of the JVM. On a Paper server that reloads plugins,
this also pins the plugin classloader. *Fix:* make it `AutoCloseable`/`Closeable`, close the channel
and cancel the scope from
`RabbitConnectionImpl.disconnect()`.

### H3. Unbounded caches keyed by remote-supplied strings

- `EventTypeResolver.cache` — `ConcurrentHashMap<String, Optional>`, keyed by `envelope.type`
  straight off the wire, with negative results cached deliberately and no bound.
- `EventDispatcher.warnedTypes` — `ConcurrentHashMap.newKeySet<String>()`, same key, same lack of
  bound.
- `QueryDispatcher.DescriptorCache` — keyed by `Pair<String, ClassLoader?>`; holds **strong
  references to classloaders**, preventing plugin unload on Paper.

Any peer (or anything that can publish to the Redis channel) can grow two unbounded maps by
publishing envelopes with random `type` values. There is no authentication on the Redis channels.
*Fix:* bounded Caffeine caches with a size cap (the project already depends on Caffeine everywhere);
weak classloader keys for the descriptor cache.

### H4. Double retry-publish race on the RPC timeout path

`RabbitListenerHandlerManager.handleRequest`, `replyTo != null` branch:
`handlerJob.invokeOnCompletion`
(line 201) launches `retryOrDeadLetter` when the handler fails, *and* the `TimeoutCancellation`
catch (line 222-228) calls `retryOrDeadLetter` after cancelling `requestJob`. Cancelling the job
triggers the completion handler, so on timeout both paths can run. `RabbitAck` is idempotent
(`settled` CAS), but `RetryPublisher.handleFailure` is not: two audit reports and two copies of the
message on the retry tier, which the origin queue then redelivers twice. *Fix:* one settle path. An
`AtomicBoolean handled` guarding `retryOrDeadLetter`, or restructure so the completion handler is
the only caller.

### H5. Publisher confirms serialise all publishing on two threads

`RabbitPublisher.publish` runs `channel.basicPublish` followed by
`channel.waitForConfirmsOrDie(5s)` inside `withContext(dispatcher)`, where `dispatcher` is a
**single-threaded executor** (line 19-25). With the default `publisherPoolSize = 2`, the process can
have at most two unconfirmed publishes in flight, each blocking a dedicated OS thread for the full
broker round-trip.

For an RPC-heavy service this is the throughput ceiling and it is invisible — the config comment
says *"a few fast publishers can already saturate the broker"*, which is true only because the
implementation cannot use more than one at a time each. *Fix:* asynchronous confirms
(`addConfirmListener` + a `ConcurrentSkipListMap<Long, Deferred>` keyed by delivery tag), keeping
the channel confinement but not the blocking wait. Expect a large throughput win; medium complexity,
and worth benchmarking with the existing JMH harness first.

### H6. `EventDispatcher.dispatch` allocates three collections per received event

```kotlin
val subscriptions = registry.subscriptions()                                    // full list copy
val eventClass = typeResolver.resolve(
    typeName = envelope.type,
    known    = subscriptions.map { it.eventClass },                             // second list
    loaders  = subscriptions.mapTo(mutableSetOf()) { …classLoader }             // a set
)
val matches = registry.subscriptionsFor(eventClass, envelope.topic)             // filter → fourth
```

Then `subscriptionsFor` calls `EventTopics.matches(pattern, topic)` per subscription, which splits
both strings and recurses with `drop()` — allocating a fresh list per pattern segment.

The registry is frozen before any event is dispatched. All of this is computable once at `freeze()`.
*Fix:* at `freeze()`, precompute the immutable subscription list, the known-class set, the loader
set, and a compiled representation of each pattern (`List<String>` segments, or a small state
machine). Cache `(type, topic) → matches` in a bounded map. This is the single hottest path in the
event half of the bus and is currently the most allocation-heavy code in the repo.

### H7. Sync-structure writes are fire-and-forget; failures are logged, not reported

`AbstractStreamSyncStructure.writeToRemote` / `writeBatchToRemote` end in `.subscribe({ … }, { e ->
log.atWarning() … })`. `SyncMap.put`, `SyncSet.add`, `SyncList` mutations therefore return
successfully whether or not the write reached Redis. A caller has no way to learn that a write was
lost — no exception, no return value, no audit row.

For a library whose README is emphatic that events have no durability *and says so loudly*, having
sync-structure writes silently drop is inconsistent with the project's own honesty standard. *Fix:*
make the mutators `suspend` and await the script result, or return a `Deferred`/`Mono` the caller
can await. At minimum, route failures to the `AuditSink` rather than a warning.

### H8. `RpcService` KDoc documents an API that no longer exists

`RpcService.kt` — the most-read annotation in the public surface — carries ~100 lines of KDoc
referencing `ClientRabbitMQApi.createRpcService`, `ServerRabbitMQApi.registerRpcService`,
`serverApi.registerRpcService<UserService>(…)`, `clientApi.createRpcService<UserService>()`, none of
which exist. The final example shows `rabbit.rpc<FactionService>(service = "surf-factions-staging")`
— there is no `service` parameter on `rpc`; the signature is `rpc(target: RabbitTarget?)`. A user
copying it gets a compile error.

`surf-eventbus-ksp/README.md` (342 lines) is worse: titled `surf-rabbitmq-ksp`, documents
`RabbitRpcServiceDescriptor`, `RabbitRpcCallable`, `RabbitMQApi`, and does not mention
`@QueryService` at all — the module's other half. *Fix:* rewrite both against the current API. Add a
`docs` check that compiles KDoc samples (Dokka does not do this; a small `samples/` source set that
is compiled does).

### H9. Five different service-lookup idioms for one concern

| Location                                                                             | Idiom                                                             | Timing         | Absence |
|--------------------------------------------------------------------------------------|-------------------------------------------------------------------|----------------|---------|
| `SurfEventBusFactory`                                                                | `by lazy { requiredService() }`                                   | lazy           | throws  |
| `EventBusInstance.instance`                                                          | `by lazy { requiredService() }`                                   | lazy           | throws  |
| `EventBusInstance.orNull()`                                                          | `ServiceLoader.load(...).firstOrNull()`                           | **every call** | null    |
| `RedisComponentProvider`                                                             | top-level `val = requiredService()` + `companion : X by provider` | class-init     | throws  |
| `RabbitCredentialsProvider`, `RedisCredentialsProvider`, `RabbitMQConnectionFactory` | same                                                              | class-init     | throws  |
| `RabbitRpcServiceFactory`                                                            | `companion { val instance = requiredService() }`                  | class-init     | throws  |
| `StandaloneLifecycleHook.discover()`                                                 | `ServiceLoader.load(...).firstOrNull()`                           | every call     | null    |

Three of these initialise eagerly at class load, which makes the class unloadable in any process
without the registration — the exact problem `StandaloneLifecycleHook`'s KDoc describes and solves
*only for itself*. `EventBusInstance.orNull()` re-runs a full `ServiceLoader` scan on every call and
is called from `RedisComponentProviderImpl.tryExtractPluginNameFromClass`, i.e. once per
`SurfRedisApi`
creation.

Two of them additionally expose both `companion object : X by provider` *and* a redundant
`val INSTANCE get() = provider`. *Fix:* one `internal object Services { inline fun <reified T> required(): T; inline fun <reified T>
optional(): T? }` with lazy caching and an explicit classloader, used everywhere. Delete the
`INSTANCE` duplicates.

### H10. Three copies of "find the generated descriptor"

| File                                          | Cache               | Key                          |
|-----------------------------------------------|---------------------|------------------------------|
| `SurfEventBusImpl.QueryDescriptorCache`       | `ClassValue`        | `Class`                      |
| `QueryDispatcher.DescriptorCache`             | `ConcurrentHashMap` | `Pair<String, ClassLoader?>` |
| `RabbitRpcServiceImpl.ServiceDescriptorCache` | `ClassValue`        | `Class`                      |

All three implement `"${type.packageName}.${type.simpleName}Descriptor"`, all three catch
`ClassNotFoundException` and `LinkageError`, all three return `null` — and they disagree on caching
strategy, classloader choice, and (in `QueryDispatcher`) whether the initial `Class.forName` failure
is caught at all (it is not — it escapes `computeIfAbsent`).

The naming convention is also duplicated in the generator (`ServiceModelFactory` line 134:
`peerClass("${simpleName}Descriptor")`). Four places must agree; nothing enforces it. *Fix:* one
`internal object ServiceDescriptors { fun of(type: Class<*>): ServiceDescriptor<*>? }` in the api
module, with the name-mangling constant shared with the KSP module via a plain string constant and a
test asserting round-trip.

### H11. Exception hierarchy does not hold

`SurfEventBusException`'s KDoc: *"The root of everything this project throws on purpose."* Outside
it:

- `SurfEventBusNotFrozenException`, `SurfEventBusAlreadyFrozenException` → `IllegalStateException`
  (with KDoc justifying the exclusion)
- `CircuitOpenException` → `IllegalStateException`
- `RabbitConnectionGenerationChangedException` → `IllegalStateException`
- Bare `error()` / `check()` / `require()` in `SurfEventBusImpl` (7×), `RabbitRpcServiceImpl` (4×),
  both registries, `SurfRedisApi` (5×), `AbstractStreamSyncStructure` (4×)

A consumer cannot catch "the bus failed". Additionally `serialVersionUID` is present on
`ConnectionExceptions.kt` (all five), `SurfRabbitServiceUnavailableException`,
`CircuitOpenException`
and `RabbitConnectionGenerationChangedException`, and absent on all of `PacketExceptions.kt`,
`RequestExceptions.kt`, `SerializationExceptions.kt` — no rule, just history.

*Fix:* decide one rule and apply it. Recommendation: everything the library throws deliberately
extends `SurfEventBusException`; state-precondition violations get a
`SurfEventBusStateException : SurfEventBusException` that *also* has the `IllegalStateException`
semantics documented; add `serialVersionUID` everywhere or nowhere (nowhere is fine — these are not
serialized) and enforce with Detekt.

### H12. Exception *file* names are actively misleading

- `ProtocolExceptions.kt` contains **zero** protocol exceptions — one frozen-state exception.
- `PacketExceptions.kt` contains **all seven** `SurfRabbitProtocol*` exceptions.
- `ApiExceptions.kt` contains the other half of the frozen-state pair — so the two frozen-state
  exceptions, which cross-reference each other in their KDoc, live in two differently named files.
- `SurfRabbitProtocolVersionMismatchException` lives in `SerializationExceptions.kt`.

*Fix:* `StateExceptions.kt`, `ProtocolExceptions.kt`, `SerializationExceptions.kt`,
`ConnectionExceptions.kt`, `RequestExceptions.kt`, each holding what its name says.

### H13. `freeze()` validates events but not queries or RPC

`SurfEventBusImpl.freeze()` produces an excellent error when `@SurfSubscribe` handlers exist without
`.withRedis()`. It performs no equivalent check for:

- query services registered via `registerService` with no query transport → they are stored in
  `queryRegistry`, `connect()` skips subscription entirely, and every query to this process is a
  silent abstention;
- `rpc()` proxies — checked at call time, not freeze time, so a missing `.withRabbit()` surfaces at
  the first production call rather than at startup.

*Fix:* symmetric checks in `freeze()`, with error messages in the same shape as the event one.

### H14. `SurfRabbitApi.freeze()` is not thread-safe and the lifecycle is one-shot

`private var frozen = false` (line 78) with a check-then-act in `freeze()`, no `@Volatile` — while
`SurfEventBusImpl`, `EventSubscriptionRegistry`, `QueryServiceRegistry` and `SurfRedisApi` all
correctly mark theirs `@Volatile`. One class out of five.

Separately, `disconnect()` calls `scope.cancel(...)`, so the api cannot be reconnected — and
`SurfEventBusImpl.connect()`'s failure path calls `rabbitApi?.disconnect()`, permanently killing the
scope of an api that never connected. A retry of `connect()` after a transient broker outage cannot
work. *Fix:* `AtomicBoolean` (or `@Volatile` + CAS) for `frozen`; create the scope in `connect()`
rather than in the constructor, or make the rollback path not cancel it.

---

## Medium Priority

### M1. Naming is inconsistent in four distinct ways

**Class prefixes.** `SurfRabbitApi` vs `SurfRedisApi`; `SurfRabbitApiBuilder` vs no Redis builder;
`SurfRedisException` vs `SurfRabbitException` (consistent) but `RedisExceptions.kt` holds it while
`SurfRabbitException.kt` is its own file. Pick one: either everything user-facing is `Surf*`
-prefixed or nothing is. Given the package root is already `dev.slne.surf.eventbus`, the prefix is
redundant —
`RabbitApi`/`SurfRedisApi`, `RabbitException`/`RedisException` would be cleaner, but that is a
bigger break. Minimum viable fix: `SurfRedisApi`.

**Acronym casing.** `RabbitMQConnection`, `RabbitMQSettings`, `RabbitMQSection`,
`RabbitMQMicroserviceHealthContributor` vs `RabbitMqVersion`. One file disagrees with eleven.

**Product name in wire-visible identifiers** (these are the expensive ones — changing them later is
a protocol break):

- `RabbitMqVersion.AMQP_HEADER = "x-surf-rabbitmq-version"`
- `AbstractSyncStructure.NAMESPACE = "surf-redis:sync:"` (Redis key prefix)
- `RabbitClient` error text: *"These plugins probably did not call `RabbitMQApi.disconnect()`"* — a
  class that has not existed since before this branch.

**Type-name leakage from a former dependency.** `ServiceTypeKrpc` (and `ClassNames.serviceTypeKrpc`
in the generator) — "krpc" is not a concept in this project.

*Fix:* rename now, while 2.0 is already a wire break, and add a test asserting the header/namespace
constants (a rename after release is a rollout step).

### M2. `ServiceDefaults.kt` does not contain service defaults

It contains `ServiceTypeDefault`, `ServiceTypeKrpc`, `ServiceParameterDefault`,
`ServiceCallableDefault` — four public implementation classes. Nothing in it is a "default value".
Similarly `QueryTransport.kt` declares `QueryFrame` (a wire type) alongside the transport interface,
and `RabbitConnectionProvider.kt` declares `RabbitConnectionGenerationChangedException`. *Fix:* one
public type per file where the file is the type; `ServiceImplementations.kt` or individual files for
the four above.

### M3. Two implementations of Netty transport detection

`RabbitClient.Companion.transport` (`NettyTransport` data class, lines 46-85) and
`TransportInfo.detect()` (lines 23-47) perform byte-identical `IoUring → Epoll → KQueue → NIO`
detection, producing two different types with two different string labels ("IoUring" in both,
coincidentally). Both are then used to build separate `MultiThreadIoEventLoopGroup`s. *Fix:* one
`internal object NettyTransports { val detected: NettyTransport }` in core, exposing the
`IoHandlerFactory`, the channel class, the Redisson `TransportMode` and the label.

Related: `TransportInfo` is a `data class` whose components are an `IoHandlerFactory` and an enum —
`equals`/`hashCode`/`copy` on a factory instance are meaningless. Make it a plain class or an enum
with properties.

### M4. Thread budget is large, hardcoded, and unowned

Per process, with both transports enabled:

| Source                                               | Threads | Configurable                             |
|------------------------------------------------------|--------:|------------------------------------------|
| `RabbitClient.sharedEventLoopGroup`                  |       8 | no (hardcoded)                           |
| `RabbitClient.sharedConsumerExecutor`                |      16 | no (hardcoded)                           |
| `RabbitPublisher` (one per pool slot)                |       2 | via `publisherPoolSize`                  |
| `RabbitConsumer` (declare, reply, service, instance) |       4 | no                                       |
| `RedisRuntime.eventLoopGroup`                        |      16 | no (hardcoded)                           |
| `RedisRuntime.streamPollScheduler`                   |      ≤8 | no                                       |
| `RedisRuntime.ttlRefreshScheduler`                   |       2 | no                                       |
| Redisson connection pool                             |       8 | no (hardcoded in `createRedissonConfig`) |

≈ 56 threads before any application work, on a Paper server that also runs the game. The `declare`
consumer (`RabbitConnectionImpl.connect` line 221) is used once, for topology declaration, and then
holds a thread and a channel forever. *Fix:* derive pool sizes from `Runtime.availableProcessors()`
with config overrides in
`RabbitMQSettings`/`RedisSettings`; close the `declare` consumer after `connect()`; consider sharing
one event loop group between Rabbit and Redisson (both are Netty).

### M5. Redis has 4 config fields; RabbitMQ has 14

`RedisComponentProviderImpl.createRedissonConfig` hardcodes connection pool size (8), minimum idle
(2), retry attempts (10), retry delay, connect timeout (5 s), ping interval (10 s), TCP keepalive
parameters and RESP3. None are configurable, while RabbitMQ exposes prefetch, publisher pool,
timeouts, persistence and chunking. An operator cannot tune Redis at all.

Also `RedissonConfigDetails.serializerModule` is passed in and **never read** by the only consumer —
a dead field in an `@InternalEventBusApi` data class.

### M6. `EventBusSettings` / `RedisSettings` have a non-deterministic default

`RedisSettings.clientName: String = EventBusDefaults.redisClientName()` where
`redisClientName() = "surf-eventbus-client-${UUID.randomUUID()}"`. Because it is a default argument,
every `RedisSettings()` gets a different value, so `RedisSettings() != RedisSettings()` and
`EventBusSettings() != EventBusSettings()`. Data classes whose `equals` depends on construction time
are a trap in tests and in any dedupe/caching. *Fix:* `clientName: String? = null`, resolved to a
generated name at the point of use.

### M7. Three hand-written `toString()` overrides for redaction

`RabbitMQSection`, `RedisSection`, `RabbitMQSettings`, `RedisSettings` and `RabbitCredentials` each
hand-write a `toString` listing every field, purely to print `password=<redacted>`. Fourteen fields
in one of them. Adding a field and forgetting the `toString` silently drops it from every log line;
adding a *secret* field and forgetting it leaks it. *Fix:* a `@JvmInline value class Secret(private val value: String) { override fun toString() =
"<redacted>" ; fun reveal() = value }`. All five `toString` overrides then disappear and the data
class default is correct and complete. This also removes the risk in `redisUriOf`, which currently
embeds the password into a `RedisURI` whose `toString` the project does not control.

### M8. `AuditReport` is a data class with a deliberately partial `equals`

`AuditReport.equals` compares 4 of 25 properties (`messageUuid`, `attempt`, `kind`, `payload`). Two
reports differing in service, instance, timestamp, handler, exception and stacktrace compare equal.
The KDoc explains the `ByteArray` handling but not the omissions. *Fix:* full `equals` (a
`@Serializable` DTO should have value semantics), plus an explicit
`fun sameIncident(other: AuditReport): Boolean` if the partial comparison is genuinely needed. The
current shape will produce a wrong answer the first time someone puts reports in a `Set`.

### M9. `AuditReports.base` reads three AMQP properties nothing ever writes

```kotlin
originService   = properties.appId ?: serviceName          // appId is never set
originInstance  = properties.headers?.get("x-surf-instance")  // never written
payloadEncoding = properties.contentEncoding               // never set
messageType     = properties.type                          // never set
```

`RabbitConnectionImpl.properties()` sets only `deliveryMode`, `correlationId`, `replyTo`,
`messageId`, `expiration` and the version header. So four report fields are permanently
null/fallback.
`AuditReportsTest` constructs `BasicProperties.Builder().appId(...)` by hand and passes — testing a
path production never takes. *Fix:* set `appId = serviceName`, `x-surf-instance = instanceId`,
`type = packet class name`,
`contentEncoding = "cbor"` in `properties()`; or delete the fields. Setting them is cheap and makes
the audit rows useful.

### M10. `EventDispatcher` reports `payloadEncoding = "JSON"` for binary events

Both report builders hardcode `payloadEncoding = "JSON"` and `payload = envelope.payload?…`, but for
a `BusEventCodec` event the envelope payload is `null` and the body is the binary frame. Binary
events produce audit rows claiming JSON with no payload.

### M11. `AuditKind` and the README disagree

README table: `HANDLER_FAILED`, `QUERY_HANDLER_FAILED`, `UNROUTABLE`, `UNDESERIALIZABLE`,
`CHUNK_SERIES_EXPIRED` (5). Enum: those plus `UNKNOWN_EVENT_TYPE` and `EVENT_HANDLER_FAILED` (7).
The README says `HANDLER_FAILED` covers "an RPC **or event** handler threw"; the code has a separate
`EVENT_HANDLER_FAILED`. A consumer building a dashboard from the README misses two kinds.

### M12. `Pair<RabbitRequestPacket<*>, CompletableDeferred<…>?>` as a domain type

`RabbitConnectionImpl.pendingRequests` is a Caffeine cache of that pair. Every use site reads
`.first`/`.second` and null-checks the deferred — five times in the file. The deferred is never
actually null: only `awaitResponse` inserts, always with one. So the nullability is dead and forces
a null check at every touch point. *Fix:* `private data class PendingRequest(val request: RabbitRequestPacket<*>, val response:
CompletableDeferred<ReceivedResponse>)`.

### M13. `RabbitConnectionImpl` is a god class (616 lines, 11 responsibilities)

Connection lifecycle, audit sink construction, retry publisher, circuit breaker, return listener,
two chunk assemblers, two serializer caches, correlation id generation, pending-request bookkeeping,
reply consumption, request consumption, AMQP property construction. `send()` and `awaitResponse()`
duplicate the same chunk-decision + publish-loop block. *Fix (composition, not inheritance):*
`RpcCaller` (correlation ids, pending map, `sendRequest`,
`send`), `RpcResponder` (request consumers, reply publishing), `ReplyChannel` (reply queue +
endpoint state), leaving `RabbitConnectionImpl` as the assembly point. Extract
`publishChunked(exchange, routingKey, body, properties, mandatory)` to kill the duplication.

### M14. `RabbitRequestPacket` is a wire DTO that is also a `CoroutineScope` and a response channel

`@Serializable abstract class RabbitRequestPacket<R> : RabbitPacket(), CoroutineScope` with
`override var coroutineContext by Delegates.notNull()` and a `@Transient CompletableDeferred`.
Constructing one on the client side and calling `launch {}` on it throws
`IllegalStateException: Property coroutineContext should be initialized before get`.

Also `RabbitPacket.timestamp = OffsetDateTime.now()` is serialised on every packet (`@Contextual`)
and read by nothing. *Fix:* split into `RpcRequest` (pure data) and a server-side
`RequestContext(scope, response)` the executor holds. Delete `timestamp` or start using it.

### M15. `RabbitAck.nack` and `RabbitAck.nackIfUnsettled` are identical

Byte-for-byte the same implementation. `reject` is dead (declaration only). *Fix:* delete
`nackIfUnsettled` (keep `nack`, which already only acts when unsettled) and `reject`.

### M16. `RetryPolicy`'s ladder is encoded twice

`MAX_RETRIES = 3` and a `when (attempts) { 0 -> TEN_SECONDS; 1 -> ONE_MINUTE; 2 -> FIVE_MINUTES;
else -> DeadLetter }`. Adding a tier to `RetryTier` requires editing three places (enum, `when`,
`MAX_RETRIES`) plus `EventBusDefaults.RETRY_TTL_MILLIS`, and only one of those is checked
(`declareRetryTiers` requires matching sizes). *Fix:*
`RetryTier.entries.getOrNull(attempts)?.let(RetryDecision::Retry) ?: DeadLetter`, and
`MAX_RETRIES get() = RetryTier.entries.size`.

Also: `retryEnabled` is threaded through `RabbitListenerHandlerManager.retryEnabledFor()` (which
ignores its parameter and returns a literal `true`), `RetryPublisher.handleFailure` and
`RetryPolicy.decide`. Three layers carrying a constant. Delete or implement.

### M17. Two logging frameworks

Everything uses `dev.slne.surf.api.core.util.logger()` (Flogger) except `RpcServiceExecutor`, which
uses `net.kyori.adventure.text.logger.slf4j.ComponentLogger`. One file out of ~40.

### M18. Reactor and coroutines coexist without a boundary

`Mono`/`Flux`/`Disposable`/`Scheduler` throughout the Redis half (`SyncStructure`, `Initializable`,
`SimpleRedisCache`, `AbstractSyncStructure`, `LuaScriptExecutor`, `RedisExpirableUtils`,
`RedisStreamsUtils`, `DisposableAware`); `suspend`/`CoroutineScope`/`Deferred` everywhere else. The
boundary is crossed ad hoc with `awaitFirstOrNull`, `awaitSingle`, and two blocking `.block()` calls
(`RedisApi:732,802`, `AbstractSyncStructure:64`) — the last of which throws if it lands on a Reactor
non-blocking thread.

`RedisUtils.kt` even contains a hand-written Reactive-Streams `Subscriber` implementing
`Mono.asDeferred()` next to a KDoc saying *"Prefer `asDeferred(scope)`"* — two implementations of a
function `kotlinx-coroutines-reactor` already provides. Its `onComplete` throws
`NoSuchElementException`, which violates the Reactive Streams specification (§2.13). *Fix:* make
Reactor an internal implementation detail. The public `SyncStructure`/`SimpleRedisCache`
surfaces become `suspend`. This is a prerequisite for C7 anyway.

### M19. `SurfRedisApi` is a 810-line god class with stale documentation

- 70 lines of class KDoc describing `RedisService`, `registerRequestHandler`, `subscribeToEvents`,
  `RequestContext` — none of which exist. The example will not compile.
- Six `create` overloads, one of them `@Deprecated(level = ERROR)` and therefore uncallable — it can
  be deleted outright.
- `getCallingPluginName()` uses `getCallerClass(1)`, a fixed stack depth, from inside a companion
  reached through up to two other overloads. From `RedisTransportProviderImpl`'s
  `RedisApi.create()` the attributed "plugin" is effectively arbitrary.
- `lateinit var redisson` / `redissonReactive` public with private setters — touching them before
  `connect()` throws `UninitializedPropertyAccessException` rather than a domain error.
- `initializables`/`disposables` are Caffeine caches with **weak keys** used as a lifecycle
  registry:
  a sync structure the caller does not retain can be collected before `init()` ever runs.
- `disconnect()` returns early when `!isConnected()`, so a bus that froze but never connected leaks
  both coroutine scopes.

### M20. `RabbitClient.closeSharedResources()` sleeps 10 seconds on the shutdown path

When any client is still active — which is the *normal* case on Paper, because nothing tracks the
buses plugins created — it logs two warnings, blocks for 10 s, then logs a stack trace per leak. On
a game server this is 10 s of shutdown hang, on the main thread if that is who called it. *Fix:*
register buses for shutdown and disconnect them properly; drop the sleep, or make it a short,
configurable grace period.

### M21. `decodeOrNull` throws

`RabbitPacketChunking.decodeOrNull` returns `null` for three conditions and throws
`SurfRabbitProtocol*Exception` for six others. Callers reading the name will not guard. *Fix:*
rename to `decodeChunk`, or return a sealed `ChunkDecodeResult`.

### M22. Chunk header size is specified twice

`CHUNK_HEADER_SIZE` (line 47) and the `exactSize` expression in `encodeChunk` (line 230) compute the
same value from the same seven terms, with a comment saying *"remember to update decodeOrNull if
changing this"* — an instruction to a human where the compiler could do it.

### M23. `QueryDispatcher.failureReport` puts instance ids in service-name fields

```kotlin
originService     = frame.originInstanceId    // an instance id
reportedByService = instanceId                // an instance id
```

`EventDispatcher` does this correctly with a real `serviceName`. The query dispatcher does not
receive one.

### M24. KSP: annotations are matched by simple name; the default timeout is duplicated

`ServiceModelFactory.readServiceAttribute`/`readTimeoutAttribute`/`fireAndForget` all match
`shortName.asString() == "RpcService"` / `"QueryService"` / `"FireAndForget"`. Any same-named
annotation from another package matches. `ContractKind` already carries the fully qualified names —
use them.

`DEFAULT_TIMEOUT_MILLIS = 5_000L` in the processor duplicates `QueryService(timeoutMillis = 5_000)`
in the api. Changing the annotation default silently desynchronises generated descriptors.

### M25. KSP: asymmetric contract rules and asymmetric generation

`QueryRules.acceptsContract` silently skips private/local contracts (no log at all — the developer
gets a runtime *"no generated descriptor found"* much later). `RpcRules.acceptsContract` returns
`true` unconditionally, so a private `@RpcService` generates a descriptor referencing a private
type → a compile error in generated code.

`RpcDescriptorCodegen` applies `addInternalDeprecation()` + `suppressInternalDeprecation()`;
`QueryDescriptorCodegen` applies neither. `QueryClientCodegen` is instantiated inside
`QueryDescriptorCodegen`; `RpcClientCodegen` is instantiated in `ServiceProcessor`.

### M26. KSP generates `!!`

`QueryClientCodegen:93` emits `val callable = __descriptor__.getCallable("x")!!`. The descriptor is
generated from the same model, so it cannot be null — which is exactly why it should be
`getCallable("x") ?: error("generated descriptor is missing callable x")`, or the callables map
should be a `Map<String, ServiceCallable>` accessed with `getValue`.

---

## Low Priority

- **L1.** Redundant self-package imports in `RabbitConnectionImpl` (5), `RabbitClient` (3),
  `RabbitListenerHandlerManager` (4), `RabbitRpcServiceImpl` (5), `RabbitPacketSerializer` (3),
  `RpcServiceExecutor` (1), `EventTransport` (1), `SurfEventBusBuilderImpl` (1). A linter would
  remove all of them.
- **L2.** Fully-qualified inline type references where an import exists elsewhere in the same file
  or module: `java.util.UUID.randomUUID()` (`RabbitConnectionImpl:286`),
  `java.util.concurrent.ConcurrentHashMap` (`QueryDispatcher:95`), `java.util.ServiceLoader`
  (`EventBusInstance:33`), `java.util.Optional` (`VelocityEventBusInstance`), `java.nio.file.Path`
  (`RedisBusSuite:81`), `java.lang.reflect.Method` (`EventDispatcher:110`).
- **L3.** German in an English codebase: `EventSubscription.displayName` KDoc (*"Klasse#Methode"*);
  every plan/spec document under `docs/superpowers/` is German while the README, KDoc and rollout
  doc are English.
- **L4.** Dead code confirmed by grep (declaration is the only reference):
  `SurfRabbitConnectionFailedException`, `SurfRabbitConnectionClosedException`,
  `EventTypeResolver.isKnownUnresolvable`, `RabbitConsumer.reject`,
  `RabbitConnectionImpl.EMPTY_BYTE_ARRAY`, `ksp/codegen/Util.kt::applyIf` (the file's only
  declaration), `RedisEventTransport.log`, `SurfRabbitPublishException(attempts, cause)`,
  `RabbitRpcService.unregisterService`, `CircuitBreakerRegistry.names()`/`resetAll()`,
  `RabbitAuditSink.droppedCount()`, `SurfEventBusImpl.dataPath` (suppressed `unused`).
  Production-dead but test-referenced: `AuditMessageIdentity.stamp`,
  `QueryServiceRegistry.timeoutOf`.
- **L5.** `ServiceDescriptor.getCallable(name)` duplicates `callables[name]`; every generated
  descriptor implements both.
- **L6.** `ServiceCallable.parameters: Array<out ServiceParameter>` — a mutable array in a public
  interface. `List<ServiceParameter>` is the Kotlin answer.
- **L7.** `RabbitIdentity` is a plain class with a hand-written `toString` and no `equals`/
  `hashCode`
  — a `data class` in all but declaration.
- **L8.** `RabbitConnectionProvider.createChannel` casts to `RecoverableChannel` and returns
  `Channel` — the cast has no effect.
- **L9.** `EventTypeResolver.Optional` shadows `java.util.Optional` by name.
- **L10.** `RabbitPublisherPool` names publishers `"0"`, `"1"` → two clients in one JVM produce
  identically named `rabbit-publisher-0` threads.
- **L11.** `RabbitTopologyDeclarer.declareRetryTiers` uses `tier.queueName` as both queue *and*
  exchange name; the property name says queue.
- **L12.** `EventSubscriptionRegistry.patterns()` is recomputed by both `exactTopics()` and
  `wildcardPatterns()` → three passes where one suffices.
- **L13.** `EventSubscriptionRegistry.register` iterates `javaClass.methods` without filtering
  bridge/synthetic methods, and does not dedupe — registering the same listener twice doubles
  delivery silently.
- **L14.** `RabbitRpcServiceImpl.handleRequest` answers an unknown service with `NoSuchMethodError`
  (a JVM linkage `Error`) for what is a routing miss.
- **L15.** Caffeine used as a plain concurrent map with no eviction policy in `RabbitRpcServiceImpl`
  (`rpcServices`, `proxies`) — `ConcurrentHashMap` is clearer and cheaper.
- **L16.** `ServiceSerializerCache` is instantiated per component (`QueryDispatcher`,
  `RabbitRpcServiceImpl`, `RpcServiceExecutor`, and once per generated query client). Same for
  `KotlinSerializerCache` (four `ClassValue` instances). One shared cache per `SurfEventBus` would
  do.
- **L17.** `ParametersSerializer.descriptor` uses one fixed serial name
  (`"surf.eventbus.ParametersSerializer"`) for every callable of every contract.
- **L18.** `RabbitPacketChunkAssembler` defaults `serviceName = ""`, `instanceId = ""` — blank
  identity fields in audit rows if a caller forgets. Make them required.
- **L19.** `RabbitPacketChunkAssembler` defines a private `NoOpAuditSink` while `LoggingAuditSink`
  exists; a third sink implementation for the same interface.
- **L20.** `RetryPublisher.handleFailure` constructs a new `AuditReports` on every failure; the
  constructor arguments are fields. Hoist it.
- **L21.** `EventBusPlatformInstance.onEnable()` is an empty `@MustBeInvokedByOverriders` method.
- **L22.** `VelocityMain` runs `runBlocking { onLoad() }` inside its constructor (Guice injection
  thread) and declares `@Subscribe suspend fun` handlers — Velocity's event bus does not natively
  support suspend listeners; verify the surf-api adapter covers this, or the shutdown hook never
  runs.
- **L23.** Three different mechanisms for "where is my data directory": Paper `lateinit var` set
  from a bootstrapper, Velocity a global `lateinit var plugin`, standalone a `@Volatile` static
  configured by a side-channel `configure()`.
- **L24.** `LuaScriptRegistry` loads resources through
  `EventBusInstance.instance.getResourceAsStream`
  — a `requiredService` lookup and the *platform's* classloader, for resources that live in
  `surf-eventbus-core`. It works only because they end up in the same jar.
  `LuaScriptRegistry::class.java.getResourceAsStream` has neither dependency.
- **L25.** `.gitignore` lists `.idea` twice (line 14 as a directory of specific files, line 49
  whole).
- **L26.** `SurfRedisApi` carries `@Suppress("unused")` at class level, masking real dead members.
- **L27.** `EventEnvelope.decodeFromString` includes the entire offending wire text in the exception
  message, which is then logged at SEVERE — unbounded, attacker-influenced, potentially PII.
- **L28.** No `equals`/`hashCode` contract test for `PacketChunk`, `AuditReport`,
  `ChunkAcceptResult.Complete` — all three hand-write both.

---

## Cross-Cutting Improvements

### X1. One service-locator abstraction (fixes H9)

Seven declaration sites, five idioms, three eager. One `internal object Services` with
`required<T>()`/`optional<T>()`, lazy caching, explicit classloader, and a single documented failure
message. Removes ~40 lines and one whole class of startup-order bug.

### X2. One transport-plumbing abstraction for the two Redis transports (fixes C3)

`RedisEventTransport` and `RedisQueryTransport` both: call `ensureConnected()`, get a topic, publish
with `awaitFirstOrNull`, add a listener, track it, remove it on disconnect. They disagree on three
of those five — and each disagreement is a bug in the event one. Extract
`RedisChannel(redis, codec)` with `publish(payload)`, `subscribe(handler): ListenerHandle`, and
`ListenerHandle.close()`.

### X3. One stream-backed structure base (largest duplication in the repo)

`SimpleRedisCacheImpl` (435 lines) and `SimpleSetRedisCacheImpl` (807 lines) independently
re-implement what `AbstractStreamSyncStructure` (397 lines) already does: `init` → validate codec →
fetch latest stream id → poll continuously → parse `type`/`msg` fields → version gating → resync on
fault → TTL refresh loop → `dispose0`. Method-for-method:
`startPolling`, `handleStreamFault`, `processStreamMessage`, `startRefreshingTtl`,
`clearNearCacheOnly`, and a private sealed `CacheEntry` exist in both caches.

That is roughly 1,240 lines duplicating 400. Rebasing both caches onto
`AbstractStreamSyncStructure` (or a shared `AbstractStreamBackedStructure` extracted from it) is the
single highest-value refactor available, and it would also give the caches the version-gap resync
logic they currently approximate.

### X4. One audit-report factory (fixes C4, M9, M10, M23)

`AuditReports` exists and is good. It is used by `RetryPublisher`, `ReturnListenerBridge` and
`RabbitPacketChunkAssembler`, and **bypassed** by `RabbitListenerHandlerManager` (two hand-built
15-line `AuditReport(...)` blocks), `EventDispatcher` (two more) and `QueryDispatcher` (one more).
Five hand-built reports, and every one of them gets at least one field wrong (M9, M10, M23). Move
`AuditReports` into the api module (it only needs `AMQP.BasicProperties` for one overload — split
that off), give it `eventHandlerFailed`, `queryHandlerFailed`, `unknownEventType`, and make it the
only way to construct an `AuditReport`.

### X5. One `awaitCondition` test helper

`FireAndForgetTest`, `RetryIntegrationTest` and `ConsumerDeathTest` each define a private polling
loop (`while (!condition()) delay(…)`). The Redis suites use blind `delay(SETTLE_MILLIS)` instead.
One shared `suspend fun awaitAtMost(timeout, poll, condition)` in `testing/`, used everywhere,
removes both the duplication and most of the flakiness.

### X6. Precomputed subscription plan (fixes H6)

`freeze()` should produce an immutable `SubscriptionPlan` — the subscription list, the known-class
set, the loader set, compiled patterns, and an exact-topic index — that `EventDispatcher` reads
without allocating. The registry is already frozen; nothing else needs to change semantically.

### X7. One Netty transport detection (fixes M3)

### X8. A `Secret` value class (fixes M7)

---

## Testing Plan

### Current state

277 test methods across 73 files. Genuinely strong areas: chunking protocol (`ChunkingTest`,
`ChunkSeriesTest`), topology (`RabbitTopologyDeclarerTest`, `QueueArgumentsTest`), retry
(`RetryPolicyTest`, `RetryQueueTest`, `RetryIntegrationTest`), circuit breaker (including a Lincheck
concurrency test), config layering, KSP validation, and the four container suites.

Structural problems:

1. **Nothing runs them.** (C1)
2. **The production Redis wiring is untestable and untested.** `RedisBusSuite`'s own KDoc explains
   that `withRedis()` cannot be pointed at a container, so every Redis suite uses the
   `withRedis(event, query)` seam. `RedisTransportProviderImpl`, `RedisComponentProviderImpl`,
   `redisConfig` and `RedisCredentialsProviderImpl.redisURI()` have zero coverage. Fixing C6 fixes
   this.
3. **Blind sleeps.** `delay(SETTLE_MILLIS)` ×10 in `EventSuiteTest`, `delay(5_000)` in
   `FireAndForgetTest`, `delay(3_000)` twice, `Thread.sleep(500)` in `QueueOverflowTest`. Slow and
   timing-dependent. (X5)
4. **Claimed coverage exceeds actual.** `EventSuiteTest`'s KDoc says *"Spec tests 1–12c"*; tests 6,
   7, 8, 12b and 12c do not exist.
5. **Tests that validate unused code.** `AuditMessageIdentityTest` (`stamp` is production-dead),
   `QueryServiceRegistryTest.timeoutOf`, `AuditReportsTest`'s `appId` case (never set in
   production).

### Missing tests, by priority

**Tier 1 — untested production classes on the critical path**

| Class                                                            | Lines | What to test                                                                                                                                                                                          |
|------------------------------------------------------------------|------:|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SimpleSetRedisCacheImpl`                                        |   807 | add/removeById/removeByIndex/removeIf/invalidateAll; index consistency; near-cache expiry per entry kind; Lua flag/touched parsing; concurrent add of the same id; stream fault → resync              |
| `SimpleRedisCacheImpl`                                           |   435 | get/put/putNull/invalidate/cachedOrLoad(Nullable); null-marker semantics; TTL refresh gate; stream message parsing incl. malformed                                                                    |
| `SyncMapImpl` / `SyncListImpl` / `SyncSetImpl` / `SyncValueImpl` |  ~800 | local/remote convergence; version gap → resync; own-origin suppression; batch mutation version range; listener notification on remote change only                                                     |
| `RabbitPublisher`                                                |   154 | confirm timeout → `SurfRabbitPublishException`; channel reset on failure; `channelOpenAttempts` exhaustion; generation mismatch mid-publish; return listener installed on a *new* channel after reset |
| `RabbitConnectionProvider`                                       |   217 | generation increments on recovery; `awaitOpen` rejects a stale generation; `close()` is idempotent; listeners fire in order; concurrent `connection()` creates exactly one                            |
| `RabbitConsumer`                                                 |   170 | nack-on-handler-error path; `prefetchCount` applied only when `!autoAck`; `onCancelled` invoked; `close()` cancels in-flight processing                                                               |
| `RabbitPacketChunkAssembler`                                     |   265 | duplicate chunk delivery; two series on one correlation id; expiry → `CHUNK_SERIES_EXPIRED` audit; `discard` mid-series; concurrent completion (only one `Complete`)                                  |
| `ReturnListenerBridge`                                           |    90 | return before confirm ordering; audit suppression for the audit service itself; `unregister` clears a recorded return                                                                                 |
| `RetryPublisher`                                                 |   152 | attempt header increment; expiration dropped; identity header preserved (this is C4's regression test); re-chunking on the retry path                                                                 |
| `RpcServiceExecutor`                                             |   116 | fire-and-forget rethrows; non-F&F responds with an error; `CancellationException` propagates; unknown callable message                                                                                |

**Tier 2 — regression tests for the defects in this document**

| Test                                                                  | Guards           |
|-----------------------------------------------------------------------|------------------|
| `suspend @SurfSubscribe is rejected (or invoked)`                     | C2               |
| `RedisEventTransport removes its listeners on disconnect`             | C3               |
| `an event published immediately after connect is delivered`           | C3               |
| `the first failure report and the retry stamp share one messageUuid`  | C4               |
| `event handler failures reach the configured sink`                    | C5               |
| `a plugin-local eventbus-plugin.yml overrides redis.host`             | C6               |
| `build(environment) overrides the resolved settings`                  | C8               |
| `fetchRedisOs extracts the full OS string`                            | C9               |
| `disconnect() disconnects every transport even when the first throws` | H1               |
| `a timed-out handler republishes exactly once`                        | H4               |
| `freeze() rejects a query service without the Redis transport`        | H13              |
| `AMQP_HEADER and sync NAMESPACE constants are unchanged`              | M1 (wire compat) |

**Tier 3 — thin but valuable**

`RabbitMqVersion` (parse/compare/`fromHeaders` edge cases — currently untested despite being wire
protocol), `EventTypeResolver` (registry hit, classloader hit, negative caching, non-`SurfBusEvent`
class rejected), `BusEventCodecs` (companion detection, absence caching), `KotlinSerializerCache` /
`KotlinSerializerNameCache`, `ParametersSerializer` (optional omission, missing required field,
contextual/`@Serializable(with=)` annotations preserved), `RabbitPacketSerializer` (round trip,
unknown class name, corrupt frame), `MessageKind` (exists), `RedisChannels` (exists),
`LuaScriptRegistry` (missing resource message), `DisposableAware` (double dispose,
track-after-dispose).

**Tier 4 — properties worth property-based testing**

`EventTopics.matches` against a reference AMQP implementation or a generated corpus (the recursive
`#` handling is the subtlest logic in the repo and has one unit test file);
`RabbitPacketChunking.split → decode → assemble` round-trip over random sizes including exact
multiples of the chunk size; `RabbitTopology.sanitize` idempotence (documented, untested).

**I have not written these test files.** The audit itself was the requested deliverable and writing
~35 production-quality test classes is a substantial separate piece of work — several of them (the
cache and sync suites) need container fixtures that do not exist yet. Say the word and I will work
through them in the tier order above, starting with the Tier 2 regression tests, which are small and
pin the defects before the fixes land.

---

## Architecture Roadmap

In dependency order. Each stage is independently shippable.

**Stage 0 — make the work verifiable (blocks everything)**
CI on pull requests running `./gradlew check` with Docker; fail on skipped integration tests. Add
Detekt + Ktlint (or Spotless) with a baseline, so the ~60 mechanical findings in "Low Priority"
are enforced rather than re-audited.

**Stage 1 — stop the silent failures (C2–C5, C8–C10, H1, H4)**
Small, independent, high-value fixes. Each gets a Tier-2 regression test first.

**Stage 2 — one configuration path (C6)**
Delete `redisConfig`; resolve `EventBusSettings` once in `SurfEventBusBuilderImpl` and pass
`RedisSettings` into the Redis SPI. Unlocks testing the production Redis wiring, fixes the layering
bug, and removes the last process-wide mutable singleton.

**Stage 3 — one of each shared concern (X1, X4, X7, X8, H10, H11, H12)**
Service locator, audit factory, Netty detection, `Secret`, descriptor lookup, exception hierarchy,
exception files. Mostly mechanical, large consistency payoff, low risk.

**Stage 4 — decompose the two god classes (M13, M19)**
`RabbitConnectionImpl` → `RpcCaller` + `RpcResponder` + `ReplyChannel`.
`SurfRedisApi` → `RedisConnection` (lifecycle) + `RedisStructures` (factories) + `RedisDiagnostics`.
Do this after Stage 3 so the extracted pieces use the unified helpers.

**Stage 5 — one concurrency model at the boundary (M18, C7)**
Make Reactor internal: `Initializable`, `SyncStructure`, `SimpleRedisCache`, `SimpleSetRedisCache`
become `suspend`-based. Then remove Redisson/Netty/Reactor from the public ABI and re-dump
`surf-eventbus-api.api`. This is the breaking change that should land *in* 2.0, not after it.

**Stage 6 — collapse the duplicated stream machinery (X3)**
Rebase both caches onto the shared stream-backed base. Largest single reduction in the repo (~800
lines), but it must come after Stage 5 or it will be redone.

**Stage 7 — performance (H5, H6, X6, M4)**
Async publisher confirms, precomputed subscription plan, configurable and shared thread pools. Use
the existing JMH harness (`EventTransportBenchmark`, `codecBenchmark`) to measure before and after;
extend it with a publish-throughput benchmark, which does not exist.

---

## Refactoring Plan (executable, ordered to minimise conflicts)

Ordering rule: mechanical/global changes before structural ones; file moves last within a stage.

### Step 1 — CI and tooling

1. Add `.github/workflows/ci.yml`: `pull_request` + `push`, JDK from the toolchain, `./gradlew check
   --stacktrace`, Docker available. Cache Gradle.
2. Add `-PrequireIntegration` and make `DockerAvailableCondition` *fail* rather than skip when it is
   set; pass it on CI.
3. Add Detekt and Ktlint with a generated baseline; add `detekt` and `ktlintCheck` to `check`.
4. Add a `docker-compose.yml` (rabbitmq:management + redis) and a `docs/development.md` describing
   the local loop. Neither exists today.

### Step 2 — regression tests for the known defects

Write the Tier-2 tests listed above. They must fail against `HEAD`.

### Step 3 — silent-failure fixes (one commit each, each referencing its test)

1. `EventSubscriptionRegistry` — reject suspend handlers with a message naming the method (C2).
2. `RedisEventTransport` — `awaitSingle()` the listener ids, track `(topic, id)`, `removeListener`
   on disconnect (C3).
3. `AuditReports.base` — take `messageUuid` as a parameter; `RetryPublisher` resolves it once and
   uses `AuditMessageIdentity.stamp` (C4).
4. `SurfEventBusImpl` — accept an `AuditSink` constructor parameter; `SurfEventBusBuilderImpl`
   passes
   `rabbitApi?.connection?.auditSink ?: LoggingAuditSink` (C5).
5. `SurfEventBusBuilderImpl.build` — pass `environment` into config resolution (C8).
6. `RedisApi.fetchRedisOs` — fix the Lua escaping (C9).
7. `RabbitConnectionProvider` — `withContext(Dispatchers.IO)` around `newConnection`, `Mutex`
   instead of `synchronized` (C10).
8. `SurfEventBusImpl.disconnect` — `runCatching` each step, rethrow first with suppressed (H1).
9. `RabbitListenerHandlerManager` — single settle path for retry/dead-letter (H4).
10. `RabbitAuditSink` — implement `AutoCloseable`; close it from `RabbitConnectionImpl.disconnect`
    (H2).
11. `EventTypeResolver`, `EventDispatcher.warnedTypes`, `QueryDispatcher.DescriptorCache` — bounded
    Caffeine caches (H3).
12. `SurfEventBusImpl.freeze` — symmetric transport checks for queries and RPC (H13).
13. `SurfRabbitApi.frozen` — `AtomicBoolean` (H14).

### Step 4 — configuration unification (C6)

1. Add `RedisSettings` parameters to `RedisComponentProvider.createRedissonConfig` and
   `RedisCredentialsProvider.redisURI(settings)`.
2. `SurfEventBusBuilderImpl` resolves `EventBusSettings` once (global from the platform path, plugin
   from the builder's path) and passes both halves down.
3. Delete `RedisConfigAccess.kt`.
4. Rewrite the Redis suites to use the production `withRedis()` path; keep `withRedis(event, query)`
   only for the fake-transport unit tests.

### Step 5 — shared abstractions (mechanical, large diff, low risk)

1. `internal object Services` + replace all seven lookup sites; delete the `INSTANCE` duplicates
   (X1/H9).
2. `internal object ServiceDescriptors` + replace all three descriptor caches; share the
   `"…Descriptor"` suffix constant with the KSP module (X4/H10).
3. `NettyTransports` + delete `TransportInfo` and `RabbitClient.NettyTransport` (X7/M3).
4. `Secret` value class + delete the five `toString` overrides (X8/M7).
5. Move `AuditReports` to the api module; add `eventHandlerFailed`/`queryHandlerFailed`/
   `unknownEventType`; replace the five hand-built reports; set `appId`/`type`/`contentEncoding`/
   `x-surf-instance` in `RabbitConnectionImpl.properties()` (X4/M9/M10/M23).
6. Exception hierarchy: re-parent the four strays; regroup the five exception files by what they
   contain (H11/H12).
7. Delete the confirmed dead code in L4; delete `nackIfUnsettled`/`reject` (M15); collapse
   `RetryPolicy`'s ladder onto `RetryTier.entries` and delete the `retryEnabled` flag chain (M16).

### Step 6 — naming (do it while 2.0 is already breaking)

1. `RabbitMqVersion` → `RabbitMQVersion`; `ServiceTypeKrpc` → `ServiceTypeWithSerializers`.
2. `SurfRedisApi` → `SurfRedisApi` (or drop the prefix from both — decide once).
3. Wire constants: `x-surf-rabbitmq-version` → `x-surf-eventbus-version`;
   `surf-redis:sync:` → `surf.eventbus.sync:`. Add the constant-pinning test *and* a rollout note —
   both are breaking on the wire.
4. `ServiceDefaults.kt` → `ServiceImplementations.kt`; move `QueryFrame` out of `QueryTransport.kt`;
   move `RabbitConnectionGenerationChangedException` into `ConnectionExceptions.kt`.
5. Fix the stale user-facing strings in `RabbitClient` (`RabbitMQApi.disconnect()`).

### Step 7 — documentation

1. Rewrite `RpcService`'s KDoc against the current API; add a compiled `samples/` source set so it
   cannot rot again (H8).
2. Rewrite `surf-eventbus-ksp/README.md` for both annotations.
3. Rewrite `SurfRedisApi`'s class KDoc; delete the `@Deprecated(ERROR)` overload.
4. Reconcile the README audit table with `AuditKind` (M11); document the fifth config layer
   (`surf.rabbitmq.outgoing*ChunkingEnabled` system properties in `EventBusConfigResolver:89-103`)
   or delete it — the README says four layers and there are five, with pre-2.0 names.
5. Fix the stale module names in KDoc (`surf-eventbus-bus-core`, `surf-eventbus-redis-core`,
   `surf-eventbus-rabbitmq-api`) and the broken `[RedisTransportProvider]` link in
   `SurfEventBusFactory`.

### Step 8 — decomposition, then Reactor removal, then the cache merge

Stages 4–6 of the roadmap. Each needs its own design pass; do not start before Steps 1–3 are merged.

---

## File-by-File Review

Reviewed individually below: all Kotlin and Java sources (≈200 files), build scripts, CI and prose
docs. The 21 Lua scripts under `surf-eventbus-core/src/main/resources/lua/` are covered as one entry
(they share one set of issues), as are the 14 planning documents under `docs/superpowers/` and the
Gradle wrapper. Files with nothing to say beyond "fine as is" are grouped.

### Root and build

| File                                         | Purpose                                                    | Findings                                                                                                                                                                                                                                                                                                                                                                                          |
|----------------------------------------------|------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `build.gradle.kts`                           | Root config, shadow relocation, Netty native-name mangling | Relocation + `api(redisson)` + public Redisson types = C7. The `doLast` zip-rewriting block (lines 59-107) is 50 lines of untested build logic with a silent `if (!jar.exists()) return`; extract to a `buildSrc` task with a test. `version` read via `findProperty as String` will NPE-cast if absent. `optIn` applied to every subproject means consumers of the *build* also opt in silently. |
| `settings.gradle.kts`                        | Module includes                                            | Fine. Consider `includeBuild` for `buildSrc` once the shadow logic moves.                                                                                                                                                                                                                                                                                                                         |
| `gradle.properties`                          | `version=2.0.0`, `kotlin.stdlib.default.dependency=false`  | No `org.gradle.caching`, no `org.gradle.configuration-cache`, no JVM args. Build performance is unconfigured.                                                                                                                                                                                                                                                                                     |
| `gradle/libs.versions.toml`                  | Version catalog                                            | Good, but partial: kotlinpoet (2.3.0), buildconfig (6.0.9 in core, **6.0.10** in api — two versions of one plugin), kctfork, fastutil, kotlinx-serialization (1.11.0, declared five times as a string), velocity-api, surf-api (`+`), mccoroutine are all outside it. `surf-microservice = "2.+"` and `surf-api:+` are dynamic versions — non-reproducible builds.                                |
| `surf-eventbus-api/build.gradle.kts`         | api module                                                 | `api(libs.redisson)` (C7). Two long comments explain test-classpath workarounds — legitimate, but they describe a fragile setup. `failOnNoDiscoveredTests = false` hides an empty test run.                                                                                                                                                                                                       |
| `surf-eventbus-core/build.gradle.kts`        | core module                                                | 174 lines; the eight `runtimeOnly` native-classifier blocks are copy-paste (loop over a list). `@Suppress("RedundantSuppression", "AvoidDuplicateDependencies")` at the top. `afterEvaluate { configurations.named("jmhRuntimeClasspath") { setExtendsFrom(…) } }` is a fragile classpath hack. JMH `failOnError = true` with a smoke mode is good.                                               |
| `surf-eventbus-ksp/build.gradle.kts`         | processor                                                  | `dev.zacsweers.kctfork:ksp:+` — dynamic version on a test dependency. Depends on **both** api and core for tests (core only for fixtures?) — check whether the core dependency is needed.                                                                                                                                                                                                         |
| `surf-eventbus-platform/*/build.gradle.kts`  | three plugins                                              | Paper and Velocity are three lines each — good. Standalone declares its own publication block while the other two rely on convention plugins; inconsistent.                                                                                                                                                                                                                                       |
| `.github/workflows/publish.yml`              | release                                                    | C1: this is the *only* workflow. Also publishes only the paper and velocity jars, while api/core/standalone declare publications.                                                                                                                                                                                                                                                                 |
| `.gitignore`                                 | —                                                          | `.idea` listed twice (L25).                                                                                                                                                                                                                                                                                                                                                                       |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | wrapper                                                    | Standard; wrapper jar is committed as intended.                                                                                                                                                                                                                                                                                                                                                   |

### `surf-eventbus-api` — root package

| File                     | Purpose                | Findings                                                                                                                                                                                                                                                                                                                                                                                         |
|--------------------------|------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SurfEventBus.kt`        | The public entry point | Good shape and good KDoc. Stale module name `surf-eventbus-bus-core`. `registerService` takes `KClass` with a reified extension — consistent. The `subscribe<L>()` reified overload requires a Kotlin `object` and `error()`s otherwise; a compile-time constraint would be better but there is no way to express it. `connect()`/`disconnect()` have no KDoc while everything around them does. |
| `SurfEventBusBuilder.kt` | Builder contract       | C8 (ignored `environment`). `withRedis(event, query)` is a test seam in the *public* API — mark `@InternalEventBusApi` or move it behind a testing artifact. Stale module name.                                                                                                                                                                                                                  |
| `SurfEventBusFactory.kt` | SPI                    | KDoc references `[RedisTransportProvider]`, which lives in core and does not resolve. Stale module names ×3.                                                                                                                                                                                                                                                                                     |
| `InternalEventBusApi.kt` | Opt-in marker          | Good. Missing `AnnotationTarget.PROPERTY_GETTER` and `VALUE_PARAMETER`, both used elsewhere in the project.                                                                                                                                                                                                                                                                                      |

### `surf-eventbus-api` — `config`

| File                        | Findings                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
|-----------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `EventBusConfig.kt`         | Excellent KDoc. Two hand-written `toString` overrides (M7). 14 + 4 fields with `@JvmField` — fine for Configurate. `serverPrefetchCount` max is `Short.MAX_VALUE` while AMQP allows unsigned short; harmless.                                                                                                                                                                                                                                                                       |
| `EventBusConfigResolver.kt` | The four-layer resolution is clear and correct for Rabbit. **Undocumented fifth layer**: `systemBoolean("surf.rabbitmq.outgoingRequestChunkingEnabled")` (lines 89-103) — pre-2.0 property names, contradicting both the README ("four layers") and the rollout doc's rename table. The two chunking fields are the only ones with it. Decide: document or delete. Repetition of `?: yaml(...) ?: default` 18 times invites a small `layered(env, plugin, global, default)` helper. |
| `EventBusConfigFiles.kt`    | Good, and its KDoc states the rule Redis breaks (C6). `globalCache` is unbounded and keyed by `(Path, String)` — fine in practice. `plugin()` is deliberately uncached; note the asymmetry in the KDoc.                                                                                                                                                                                                                                                                             |
| `EventBusSettings.kt`       | M6 (non-deterministic default), M7 (two `toString`s). `EventBusDefaults` is the right idea; `redisClientName()` being a function among constants is the tell for M6.                                                                                                                                                                                                                                                                                                                |

### `surf-eventbus-api` — `event`, `transport`

| File                | Findings                                                                                                                                                                                                                                                                                                                                                        |
|---------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BusEvent.kt`       | Clean. No validation that the topic is wildcard-free at the annotation level (checked at runtime in `EventTopics`); a KSP check would move it to compile time.                                                                                                                                                                                                  |
| `BusEventCodec.kt`  | Clean, good example. Exposes `io.netty.buffer.ByteBuf` in the public ABI (C7).                                                                                                                                                                                                                                                                                  |
| `EventTopics.kt`    | The recursion in `matches` allocates a new list per segment via `drop()`; called per subscription per event (H6). `segmentPattern` is recompiled per call via `matches` on each segment — actually compiled once, fine. Consider a compiled-pattern type. Good validation and good KDoc.                                                                        |
| `SurfBusEvent.kt`   | Two public `var`s with `@InternalEventBusApi set` — the mutation is a dispatcher concern leaking into the user-facing base type. The values are also carried by `EventEnvelope`, so they are on the wire twice for JSON events (the abstract class is `@Serializable`). Consider making the base type immutable and passing metadata to the handler separately. |
| `SurfSubscribe.kt`  | Clean; the `includeSelf` rationale is the best comment in the repo.                                                                                                                                                                                                                                                                                             |
| `EventEnvelope.kt`  | L27 (raw text in the exception). `encodeToString(json)` taking `Json` as a parameter on an instance method is slightly awkward; a `EventEnvelopeCodec` would be cleaner.                                                                                                                                                                                        |
| `EventTransport.kt` | Redundant self-import (L1). Good KDoc. The `onEvent: suspend (EventEnvelope, ByteArray?) -> Unit` signature encodes "binary or JSON" as a nullable second parameter — a sealed `EventPayload` would be clearer.                                                                                                                                                 |
| `QueryTransport.kt` | Declares `QueryFrame` (M2). Typo in `answer`'s KDoc: *"Called only when the handler abstained not."*                                                                                                                                                                                                                                                            |

### `surf-eventbus-api` — `service`, `query`

| File                                                         | Findings                                                                                                                                                                    |
|--------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ServiceDescriptor.kt`                                       | `getCallable` duplicates `callables[…]` (L5).                                                                                                                               |
| `ServiceCallable.kt`                                         | `Array<out ServiceParameter>` (L6).                                                                                                                                         |
| `ServiceDefaults.kt`                                         | M2 (name), `ServiceTypeKrpc` (M1). Four public classes with no `equals` — used as `ConcurrentHashMap` keys in `ServiceSerializerCache`, relying on identity; document that. |
| `ServiceInvoker.kt`, `ServiceParameter.kt`, `ServiceType.kt` | Clean. `ServiceType`'s KDoc is a good example of explaining *why*.                                                                                                          |
| `query/QueryService.kt`                                      | KDoc references "plan 3" — an internal planning document leaking into the public API surface. The `null = abstain` contract is well explained.                              |
| `query/descriptor/QueryServiceDescriptor.kt`                 | Clean. `createInstance(instanceId, json, transport)` takes three unrelated parameters that could be one `QueryContext`.                                                     |

### `surf-eventbus-api` — `rabbitmq`

| File                                      | Findings                                                                                                                                                                                                                                                                                                                                                                                                                                     |
|-------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SurfRabbitApi.kt`                        | H14 (unsynchronised `frozen`, one-shot scope). The class exists in parallel with `SurfEventBus` as a second public entry point with its own builder and its own config resolution — the README does not mention it. Decide whether it is public API or an internal detail of `SurfEventBus`; if public, it needs the same treatment (audit sink injection, transport checks); if not, mark it internal. `createCbor` in a companion is fine. |
| `SurfRabbitApiBuilder.kt`                 | Duplicates `SurfEventBusBuilderImpl`'s job. `resolveConfig` is the *correct* per-plugin layering that Redis lacks (C6) — this is the code to copy. `configFileName` override exists here and nowhere else.                                                                                                                                                                                                                                   |
| `connection/RabbitMQConnection.kt`        | Nested `@InternalEventBusApi companion` inside an already-internal interface — redundant.                                                                                                                                                                                                                                                                                                                                                    |
| `connection/RabbitMQConnectionFactory.kt` | H9 (eager class-init lookup, duplicate `INSTANCE`).                                                                                                                                                                                                                                                                                                                                                                                          |
| `identity/RabbitIdentity.kt`              | L7 (should be a data class). The random-suffix logic (`nextInt().toLong().and(0xFFFFFFFF)`) is a roundabout way to get 8 hex chars; `Integer.toHexString` reads better.                                                                                                                                                                                                                                                                      |
| `target/RabbitTarget.kt`                  | Clean sealed interface, good KDoc. KDoc references `[RabbitIdentity.instanceId]` without importing it — unresolved link.                                                                                                                                                                                                                                                                                                                     |
| `version/RabbitMqVersion.kt`              | M1 (casing, `x-surf-rabbitmq-version`). KDoc says "surf-rabbitmq library" throughout. `isNewerThan`/`isOlderThan`/`isAtLeast`/`isAtMost` are four wrappers over `Comparable` — keep `isAtLeast(major, minor, patch)`, drop the rest. No tests despite being wire protocol (Tier 3).                                                                                                                                                          |
| `packet/RabbitPacket.kt`                  | Unused `timestamp` on every packet (M14).                                                                                                                                                                                                                                                                                                                                                                                                    |
| `packet/RabbitRequestPacket.kt`           | M14 (DTO + `CoroutineScope` + response channel).                                                                                                                                                                                                                                                                                                                                                                                             |
| `packet/RabbitResponsePacket.kt`          | Empty marker; fine.                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `rpc/FireAndForget.kt`                    | Excellent KDoc.                                                                                                                                                                                                                                                                                                                                                                                                                              |
| `rpc/RabbitRpcCall.kt`                    | `Array<Any?>` arguments; fine for the generated path.                                                                                                                                                                                                                                                                                                                                                                                        |
| `rpc/RabbitRpcService.kt`                 | `unregisterService` is dead (L4) and contradicts the freeze model.                                                                                                                                                                                                                                                                                                                                                                           |
| `rpc/RabbitRpcServiceFactory.kt`          | H9 (eager `val instance` in a companion — the fifth idiom).                                                                                                                                                                                                                                                                                                                                                                                  |
| `rpc/RpcService.kt`                       | H8 — 100 lines of KDoc for an API that no longer exists.                                                                                                                                                                                                                                                                                                                                                                                     |
| `rpc/descriptor/RpcServiceDescriptor.kt`  | Clean.                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| `exception/*.kt` (8 files)                | H11, H12, L4. `ApiExceptions.kt` and `ProtocolExceptions.kt` hold one class each, misfiled.                                                                                                                                                                                                                                                                                                                                                  |

### `surf-eventbus-api` — `redis`

| File                                                                                   | Findings                                                                                                                                                                                                                                                                     |
|----------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `RedisApi.kt`                                                                          | M19 (god class, stale KDoc, overload explosion, weak-key lifecycle registry, `lateinit` public), C7, C9. The single most-improvable file in the repo.                                                                                                                        |
| `RedisComponentProvider.kt`                                                            | 12-method SPI mixing thread pools, config, plugin-name extraction and six structure factories — ISP violation. H9. Split into `RedisRuntimeProvider`, `RedisStructureFactory`, `RedisConfigFactory`.                                                                         |
| `RedisExceptions.kt`                                                                   | One abstract class in a plural-named file (M2); nothing extends it — `SurfRedisException` has zero subclasses, so the Redis half still throws bare `IllegalStateException`/`IllegalArgumentException` (H11).                                                                 |
| `internal/RedissonConfigDetails.kt`                                                    | `serializerModule` is never read (M5).                                                                                                                                                                                                                                       |
| `cache/SimpleRedisCache.kt`, `cache/SimpleSetRedisCache.kt`                            | Public interfaces extending `reactor.core.Disposable` (C7, M18). `SimpleSetRedisCache` has 12 methods including four `…OrLoad`/`…OrLoadNullable` variants — the nullable/non-nullable × by-id/by-index cross-product; a single `find(spec, loader): T?` would collapse them. |
| `cache/RedisSetIndexes.kt`                                                             | Reasonable.                                                                                                                                                                                                                                                                  |
| `codec/RedisCodec.kt`                                                                  | Good contract, well documented. `codecId get() = javaClass.name` is a sensible default but ties the wire identity to the class name — the KDoc says so.                                                                                                                      |
| `codec/AbstractCodec.kt`                                                               | Extends `org.redisson.client.codec.BaseCodec` in the public ABI (C7).                                                                                                                                                                                                        |
| `codec/ByteBufExtensions.kt`                                                           | 454 lines of extension functions — the largest utility file. Needs a table of contents in the KDoc, or splitting by type (`ByteBufPrimitives`, `ByteBufCollections`, `ByteBufOptional`). Partially tested via `AbstractCodecTest`/`RedisUtf8StringTest`.                     |
| `codec/CodecExtensions.kt`, `codec/RedisUtf8String.kt`, `codec/RedisCodecException.kt` | Fine.                                                                                                                                                                                                                                                                        |
| `codec/JsonKotlinCodec.kt`                                                             | Fine.                                                                                                                                                                                                                                                                        |
| `codec/default/*.kt` (7 files)                                                         | Uniform 14-16 line codecs; good. `VarIntBinaryCodec`/`VarLongBinaryCodec` duplicate the Java `RedisVarInt`/`RedisVarLong` logic across languages.                                                                                                                            |
| `java/…/RedisVarInt.java`, `RedisVarLong.java`, `UUIDCodec.java`                       | The only Java in the project (three files, 202 lines), presumably for hot-path reasons that are not documented. Either document why they are Java or convert them — Kotlin compiles to the same bytecode for this.                                                           |
| `sync/SyncStructure.kt`                                                                | Reactor in the public ABI (C7, M18). Good KDoc.                                                                                                                                                                                                                              |
| `sync/{list,map,set,value}/Sync*.kt`, `Sync*Change.kt` (8 files)                       | Consistent shape, good. `Sync*Change` types are a nice sealed model.                                                                                                                                                                                                         |
| `util/Initializable.kt`                                                                | `Mono<Void>` in the public ABI (C7).                                                                                                                                                                                                                                         |
| `util/RedisUtils.kt`                                                                   | M18 (hand-rolled `Subscriber`, spec violation, duplicate of the library function).                                                                                                                                                                                           |
| `credentials/CredentialsProvider.kt`                                                   | An empty marker interface whose KDoc explains a symmetry that the two sub-interfaces then break (one returns a plain data class, the other a Redisson type).                                                                                                                 |
| `credentials/RabbitCredentialsProvider.kt`                                             | Not marked `@InternalEventBusApi` while its Redis twin is. M7 (`toString`).                                                                                                                                                                                                  |
| `credentials/RedisCredentialsProvider.kt`                                              | Returns `org.redisson.misc.RedisURI` (C7), contradicting the design principle stated in its twin's KDoc. H9.                                                                                                                                                                 |
| `platform/EventBusInstance.kt`                                                         | `orNull()` re-scans `ServiceLoader` on every call (H9). Default `getResourceAsStream` delegating to `javaClass` is subtle — it resolves against the *implementation's* classloader (L24).                                                                                    |
| `platform/StandaloneLifecycleHook.kt`                                                  | Good — the only lookup that handles absence correctly.                                                                                                                                                                                                                       |
| `platform/JavaPluginLoaderProxy.kt`, `SerializedPluginDescriptionProxy.kt`             | Paper-internal reflection proxies sitting in the transport-neutral api module with no KDoc explaining what they are for or when they run. Three references each. Document or move to the Paper module.                                                                       |
| `audit/AuditReport.kt`                                                                 | M8 (partial `equals`), `terminal = true` default is dangerous.                                                                                                                                                                                                               |
| `audit/AuditKind.kt`                                                                   | M11 (README drift).                                                                                                                                                                                                                                                          |
| `audit/AuditService.kt`                                                                | An `audit`-package type importing `rabbitmq.rpc` annotations — the "transport-free" claim in `AuditReport`'s KDoc does not extend to the service.                                                                                                                            |
| `audit/AuditSink.kt`                                                                   | Contract says "must never throw"; nothing enforces it and `LoggingAuditSink` is the only implementation that honours it structurally.                                                                                                                                        |
| `circuitbreaker/CircuitBreaker.kt`                                                     | Well built and well tested (including Lincheck). `refreshState` needs `java.time.Duration` fully qualified because of the `kotlin.time` import — use an import alias. Belongs in a shared utility module rather than the bus's public API.                                   |
| `circuitbreaker/CircuitBreakerRegistry.kt`                                             | `names()`/`resetAll()` are dead (L4). No eviction: a process calling `rpc(InstanceTarget(id))` with many ids grows the map unboundedly.                                                                                                                                      |
| `circuitbreaker/CircuitState.kt`, `CircuitOpenException.kt`                            | Fine; the exception is outside the hierarchy (H11).                                                                                                                                                                                                                          |
| `serialization/KotlinSerializerCache.kt`                                               | Good use of `ClassValue`. Four instances exist across the codebase (L16).                                                                                                                                                                                                    |

### `surf-eventbus-core`

| File                                                                                                                                           | Findings                                                                                                                                                                                                                                                                                                                                                                       |
|------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `core/SurfEventBusImpl.kt`                                                                                                                     | C2 (via the dispatcher), C5, H1, H13, `!!` at line 148, unused `dataPath`, third descriptor cache (H10). Otherwise the clearest class in the repo — the `require*` error messages are a model for the rest.                                                                                                                                                                    |
| `core/SurfEventBusBuilderImpl.kt`                                                                                                              | C8. `withRedis()` eagerly resolves three SPI objects at builder time, before `build()`; a failure surfaces at the wrong place.                                                                                                                                                                                                                                                 |
| `core/SurfEventBusFactoryImpl.kt`                                                                                                              | Fine.                                                                                                                                                                                                                                                                                                                                                                          |
| `core/RedisTransportLocator.kt`                                                                                                                | Stale module name. Declares `RedisTransportProvider` in the same file (M2) — and that interface is what `SurfEventBusFactory`'s KDoc tries to link to from another module.                                                                                                                                                                                                     |
| `core/audit/LoggingAuditSink.kt`                                                                                                               | Fine; nine positional `%s` in one log line is at the limit of readability.                                                                                                                                                                                                                                                                                                     |
| `core/dispatch/EventDispatcher.kt`                                                                                                             | C2, H3, H6, M10, X4. The `warnedTypes` "warn once" mechanism duplicates `EventTypeResolver`'s negative cache (L4).                                                                                                                                                                                                                                                             |
| `core/dispatch/QueryDispatcher.kt`                                                                                                             | H3, H10, M23, X4. `Class.forName` outside the `try` in `DescriptorCache` (H10).                                                                                                                                                                                                                                                                                                |
| `core/dispatch/BusEventCodecs.kt`                                                                                                              | Neat. `getDeclaredField("Companion").isAccessible = true` will hit module access restrictions on a JPMS classpath; catch `InaccessibleObjectException` too.                                                                                                                                                                                                                    |
| `core/dispatch/EventTypeResolver.kt`                                                                                                           | H3. The `SurfBusEvent` assignability guard before caching is a genuinely good security decision, and the comment says so. `isKnownUnresolvable` is dead.                                                                                                                                                                                                                       |
| `core/registry/EventSubscription.kt`                                                                                                           | German KDoc (L3).                                                                                                                                                                                                                                                                                                                                                              |
| `core/registry/EventSubscriptionRegistry.kt`                                                                                                   | C2, L12, L13. `trySetAccessible` with the explanatory comment is excellent.                                                                                                                                                                                                                                                                                                    |
| `core/registry/QueryServiceRegistry.kt`                                                                                                        | `timeoutOf` is production-dead (L4). Otherwise clean.                                                                                                                                                                                                                                                                                                                          |
| `platform/EventBusPlatformInstance.kt`                                                                                                         | L21 (empty `onEnable`). Correctly uses `NonCancellable` on shutdown.                                                                                                                                                                                                                                                                                                           |
| `rabbitmq/connection/RabbitConnectionImpl.kt`                                                                                                  | C10 (indirect), H2, M12, M13, L1, L4. The single most complex file; the comments are good enough that the complexity is *legible*, which is why decomposition is safe.                                                                                                                                                                                                         |
| `rabbitmq/connection/RabbitClient.kt`                                                                                                          | M4 (hardcoded 8 + 16 threads in a static initialiser), M20 (10 s sleep), M1 (stale `RabbitMQApi` in user-facing text), M3. `activeClients` retains clients strongly for leak reporting — the leak detector prevents the collection it detects.                                                                                                                                 |
| `rabbitmq/connection/RabbitConnectionProvider.kt`                                                                                              | C10. Declares an exception (M2). Otherwise a careful, correct state machine.                                                                                                                                                                                                                                                                                                   |
| `rabbitmq/connection/ReturnListenerBridge.kt`                                                                                                  | Unbounded `scope.launch` per returned message — a routing outage becomes a coroutine storm. The self-audit guard (`routingKey != auditServiceName`) is a good catch.                                                                                                                                                                                                           |
| `rabbitmq/connection/RabbitConnectionListener.kt`, `RabbitConnectionSnapshot.kt`, `RabbitConnectionStatus.kt`, `RabbitClientHealthSnapshot.kt` | Small and clean.                                                                                                                                                                                                                                                                                                                                                               |
| `rabbitmq/connection/RabbitConnectionFactoryImpl.kt`                                                                                           | Fine.                                                                                                                                                                                                                                                                                                                                                                          |
| `rabbitmq/consumer/RabbitConsumer.kt`                                                                                                          | M4 (a thread per consumer; the `declare` consumer is never released). `getChannel` is `suspend` and blocks transitively (C10).                                                                                                                                                                                                                                                 |
| `rabbitmq/consumer/RabbitAck.kt`                                                                                                               | M15 (duplicate methods, dead `reject`).                                                                                                                                                                                                                                                                                                                                        |
| `rabbitmq/consumer/RabbitListenerHandlerManager.kt`                                                                                            | H4, X4 (two hand-built reports), `retryEnabledFor` ignores its parameter (M16), `(api.rpcService as RabbitRpcServiceImpl)` breaks its own abstraction. The nested try/catch/`invokeOnCompletion`/`AtomicReference` control flow is the hardest code in the repo to verify; `runCatching { handlerJob.join() }` expresses most of it.                                           |
| `rabbitmq/publisher/RabbitPublisher.kt`                                                                                                        | H5. `channel` is a non-volatile `var` confined to the dispatcher thread — correct but undocumented; add a comment or `@GuardedBy`.                                                                                                                                                                                                                                             |
| `rabbitmq/publisher/RabbitPublisherPool.kt`                                                                                                    | L10 (thread naming). Round-robin via `getAndIncrement` can overflow — `floorMod` handles it correctly; good.                                                                                                                                                                                                                                                                   |
| `rabbitmq/publisher/MessageKind.kt`                                                                                                            | Well designed, well documented, tested.                                                                                                                                                                                                                                                                                                                                        |
| `rabbitmq/publisher/RabbitPublisherOptions.kt`                                                                                                 | Not reachable from configuration — `RabbitClient.create` takes a default and nothing overrides it. Either expose it or inline the constants.                                                                                                                                                                                                                                   |
| `rabbitmq/packet/RabbitPacketChunking.kt`                                                                                                      | M21 (`decodeOrNull` throws), M22 (duplicated header size). The metadata validation against remote input is thorough and correct — the best security work in the repo.                                                                                                                                                                                                          |
| `rabbitmq/packet/RabbitPacketChunkAssembler.kt`                                                                                                | L18, L19. The lock-free `PartialPacket` is correct and well commented. Opportunistic cleanup means a stalled series survives until the next `accept`.                                                                                                                                                                                                                          |
| `rabbitmq/packet/RabbitPacketSerializer.kt`                                                                                                    | `readRemainingBytes` copies the payload on every deserialisation; decoding from the `ByteBuf` slice would avoid it. `buf.array()` assumes a zero-offset heap buffer — true here, fragile. L1.                                                                                                                                                                                  |
| `rabbitmq/packet/RabbitPacketPropertiesInjector.kt`                                                                                            | A 10-line object that mutates two fields on a wire packet (M14). Disappears if `RabbitRequestPacket` is split.                                                                                                                                                                                                                                                                 |
| `rabbitmq/rpc/RabbitRpcServiceImpl.kt`                                                                                                         | H10, L15, L14, L1. `serviceIdCounter`/`callCounter` are per-instance `AtomicLong`s whose values go on the wire — document their scope.                                                                                                                                                                                                                                         |
| `rabbitmq/rpc/RpcServiceExecutor.kt`                                                                                                           | M17 (different logger), L16. The `fireAndForget` rethrow path and its comment are correct and subtle.                                                                                                                                                                                                                                                                          |
| `rabbitmq/rpc/BreakerGuardedRpc.kt`                                                                                                            | Very good — the `isTransportFailure` KDoc explaining why `SurfRabbitRequestTimeoutException` is deliberately excluded is exemplary. Well tested.                                                                                                                                                                                                                               |
| `rabbitmq/rpc/RpcErrorResponses.kt`                                                                                                            | Fine; version-gated legacy support is clean.                                                                                                                                                                                                                                                                                                                                   |
| `rabbitmq/rpc/RabbitRpcServiceFactoryImpl.kt`                                                                                                  | L1 (two self-imports in a 13-line file).                                                                                                                                                                                                                                                                                                                                       |
| `rabbitmq/rpc/exception/SerializedException.kt`                                                                                                | 199 lines reconstructing exceptions from the wire. Not reviewed line-by-line for this audit beyond confirming it is only reachable from the RPC error path; **it deserves its own security review** — it is remote-input-driven exception construction, and the class-name → `Throwable` path is exactly where deserialization gadgets live. Add it to the test plan (Tier 1). |
| `rabbitmq/rpc/packet/RpcCallRequestPacket.kt`, `RpcCallResponsePacket.kt`                                                                      | Clean sealed response model.                                                                                                                                                                                                                                                                                                                                                   |
| `rabbitmq/topology/RabbitTopology.kt`                                                                                                          | Good. `sanitize` idempotence is documented and untested (Tier 4).                                                                                                                                                                                                                                                                                                              |
| `rabbitmq/topology/RabbitTopologyDeclarer.kt`                                                                                                  | Excellent KDoc — the `406` explanation and the "no alternate exchange" rationale are the kind of comment that prevents a regression. L11.                                                                                                                                                                                                                                      |
| `rabbitmq/topology/QueueArguments.kt`                                                                                                          | Good. `MAX_QUEUE_BYTES` (256 MiB) is not configurable; on a shared broker an operator will want it to be.                                                                                                                                                                                                                                                                      |
| `rabbitmq/retry/RetryPolicy.kt`                                                                                                                | M16. The `x-death` investigation write-up is the best comment in the repo.                                                                                                                                                                                                                                                                                                     |
| `rabbitmq/retry/RetryPublisher.kt`                                                                                                             | C4, L20.                                                                                                                                                                                                                                                                                                                                                                       |
| `rabbitmq/audit/AuditReports.kt`                                                                                                               | M9, X4. Good design, wrong home (core, should be api).                                                                                                                                                                                                                                                                                                                         |
| `rabbitmq/audit/RabbitAuditSink.kt`                                                                                                            | H2. The flood control and self-audit suppression are well reasoned. `enabled` is a dead parameter.                                                                                                                                                                                                                                                                             |
| `rabbitmq/audit/AuditMessageIdentity.kt`                                                                                                       | C4; `stamp` is production-dead (L4).                                                                                                                                                                                                                                                                                                                                           |
| `rabbitmq/health/RabbitMQMicroserviceHealthContributor.kt`                                                                                     | Fine. No Redis equivalent — an operator gets half a health picture.                                                                                                                                                                                                                                                                                                            |
| `rabbitmq/credentials/RabbitCredentialsProviderImpl.kt`                                                                                        | Fine.                                                                                                                                                                                                                                                                                                                                                                          |
| `redis/RedisRuntime.kt`                                                                                                                        | M4 (16 Netty threads + 8 + 2 schedulers, none configurable). The context-classloader swap in `init` is a real Redisson workaround and is well commented.                                                                                                                                                                                                                       |
| `redis/TransportInfo.kt`                                                                                                                       | M3, `data class` over a factory (M3 note).                                                                                                                                                                                                                                                                                                                                     |
| `redis/RedisComponentProviderImpl.kt`                                                                                                          | M5 (hardcoded Redisson tuning, unused `serializerModule`). `clientId` derived from the first two UUID segments is 16 hex chars of entropy — fine, but it is the `origin` field in every sync stream message, so document the collision domain.                                                                                                                                 |
| `redis/config/RedisConfigAccess.kt`                                                                                                            | C6 — the file to delete.                                                                                                                                                                                                                                                                                                                                                       |
| `redis/credentials/RedisCredentialsProviderImpl.kt`                                                                                            | The colon-prefix comment documents a real, previously-shipped bug — good. The password ends up inside a `RedisURI` whose `toString` is not ours (M7). No username/ACL support.                                                                                                                                                                                                 |
| `redis/bus/RedisEventTransport.kt`                                                                                                             | C3, L4 (`log`), X2.                                                                                                                                                                                                                                                                                                                                                            |
| `redis/bus/RedisQueryTransport.kt`                                                                                                             | Correct where the event transport is not. Decodes JSON on the Redisson listener thread in `subscribeReply` while `subscribeQuery` launches into a scope — inconsistent.                                                                                                                                                                                                        |
| `redis/bus/RedisChannels.kt`                                                                                                                   | Good, tested; the "why two families" KDoc is exactly right.                                                                                                                                                                                                                                                                                                                    |
| `redis/bus/BinaryFrame.kt`                                                                                                                     | `String(header)` uses the platform default charset — should be `Charsets.UTF_8` (it round-trips with `toByteArray()`, which is also platform-default; both are wrong together, so it works until two processes have different defaults). **Fix this**: it is a latent cross-platform wire bug.                                                                                 |
| `redis/bus/RedisTransportProviderImpl.kt`                                                                                                      | Eagerly constructs `RedisApi.create()` at `ServiceLoader` instantiation, so `getCallingPluginName()` attributes the connection to the wrong class (M19). `ensureConnected` freezes the api implicitly.                                                                                                                                                                         |
| `redis/sync/AbstractSyncStructure.kt`                                                                                                          | `.block()` on dispose (M18). `NAMESPACE = "surf-redis:sync:"` (M1).                                                                                                                                                                                                                                                                                                            |
| `redis/sync/AbstractStreamSyncStructure.kt`                                                                                                    | H7 (fire-and-forget writes), X3. 397 lines, six responsibilities. The hand-rolled `parseVersion` and delimiter scanning are optimisations without a benchmark behind them; `StreamEventData.payload(index)` is O(n·index). Inconsistent malformed-message handling (log-and-return vs throw).                                                                                  |
| `redis/sync/SyncValueCodec.kt`                                                                                                                 | Base64 over a `StringCodec` stream costs 33% on the wire; a `ByteArrayCodec` stream would avoid it. Bounds checks are good.                                                                                                                                                                                                                                                    |
| `redis/sync/{list,map,set,value}/Sync*Impl.kt`                                                                                                 | Consistent, readable, entirely untested (Tier 1). All inherit H7.                                                                                                                                                                                                                                                                                                              |
| `redis/cache/SimpleRedisCacheImpl.kt`, `SimpleSetRedisCacheImpl.kt`                                                                            | X3 — 1,242 lines duplicating `AbstractStreamSyncStructure`'s machinery. Untested. `SimpleSetRedisCacheImpl` at 807 lines with 20 public methods is the largest class in the repo.                                                                                                                                                                                              |
| `redis/cache/SimpleSetRedisCacheLuaScripts.kt`                                                                                                 | 448 lines of Lua in Kotlin string constants — no syntax highlighting, no linting, no tests, and it duplicates the `resources/lua/` approach used everywhere else. Move these to `.lua` resources for consistency (the loader already exists).                                                                                                                                  |
| `redis/util/DisposableAware.kt`                                                                                                                | Correct; the post-add re-check is subtle enough to deserve its comment.                                                                                                                                                                                                                                                                                                        |
| `redis/util/LuaScriptExecutor.kt`                                                                                                              | `NOSCRIPT` retry handling is good. Nested weak Caffeine caches keyed by `SurfRedisApi` and `LuaScriptRegistry` — clever, and the KDoc is longer than the code; a plain field on `SurfRedisApi` would be simpler.                                                                                                                                                               |
| `redis/util/LuaScriptRegistry.kt`                                                                                                              | L24 (platform-classloader resource loading).                                                                                                                                                                                                                                                                                                                                   |
| `redis/util/RedisExpirableUtils.kt`                                                                                                            | `.repeat().subscribe()` — an unkillable refresh loop per structure unless the `Disposable` is tracked (it is). Fine.                                                                                                                                                                                                                                                           |
| `redis/util/RedisStreamsUtils.kt`                                                                                                              | `Result<T>.handler()` as a callback shape is unusual but readable. 250 ms polling per structure per process — with many structures this is a lot of Redis traffic; note it.                                                                                                                                                                                                    |
| `serialization/KotlinSerializerNameCache.kt`                                                                                                   | Fine; merge with `KotlinSerializerCache` (L16).                                                                                                                                                                                                                                                                                                                                |
| `service/serialization/ServiceSerializerCache.kt`                                                                                              | L16 (one per component).                                                                                                                                                                                                                                                                                                                                                       |
| `service/serialization/ParametersSerializer.kt`                                                                                                | L17 (shared serial name). The optional/required handling is correct and is the merge of two former implementations — good.                                                                                                                                                                                                                                                     |
| `service/serialization/SerializationUtils.kt`                                                                                                  | Small; `buildContextual` is the right seam.                                                                                                                                                                                                                                                                                                                                    |
| `java/…/RedisInvokerLookupProvider.java`                                                                                                       | The second piece of Java; no KDoc explaining why it must be Java (`MethodHandles.Lookup` privileges, presumably). Document it.                                                                                                                                                                                                                                                 |
| `resources/lua/**` (21 scripts)                                                                                                                | Consistent and small. Not covered by any test that executes them against a real Redis except indirectly (`BulkMutationScriptTest`). No linting. Two conventions coexist: files here, and inline strings in `SimpleSetRedisCacheLuaScripts.kt`. Unify.                                                                                                                          |
| `resources/META-INF/services/*`                                                                                                                | Generated by AutoService; fine.                                                                                                                                                                                                                                                                                                                                                |
| `jmh/…/EventTransportBenchmark.kt`                                                                                                             | Good that it exists. Benchmarks the codec/transport only — there is no publish-throughput or dispatch benchmark, which is where H5 and H6 live.                                                                                                                                                                                                                                |

### `surf-eventbus-ksp`

| File                                                                                                                   | Findings                                                                                                                                                                                                                                                                         |
|------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ServiceProcessor.kt`                                                                                                  | Clean, and the one-pass rationale is right. `deferred` handling is correct.                                                                                                                                                                                                      |
| `ServiceProcessorProvider.kt`                                                                                          | Fine.                                                                                                                                                                                                                                                                            |
| `model/ContractKind.kt`                                                                                                | Good; the FQ names it holds should be used for annotation matching (M24).                                                                                                                                                                                                        |
| `model/ContractRules.kt`                                                                                               | M25 (asymmetric contract acceptance, silent skip).                                                                                                                                                                                                                               |
| `model/ServiceModel.kt`                                                                                                | Clean data model.                                                                                                                                                                                                                                                                |
| `model/ServiceModelFactory.kt`                                                                                         | M24 (simple-name matching, duplicated default). Kotlin 2.x guard syntax (`"toString" if parameters.isEmpty()`) — confirm the language version is pinned high enough in CI.                                                                                                       |
| `codegen/QueryDescriptorCodegen.kt` / `RpcDescriptorCodegen.kt`                                                        | ~80% identical (M25); extract a `DescriptorCodegen` base taking the differing properties and the `createInstance` signature.                                                                                                                                                     |
| `codegen/QueryClientCodegen.kt` / `RpcClientCodegen.kt`                                                                | M26 (`!!`), duplicated constructor-property boilerplate, raw `java.util.UUID.randomUUID()` instead of a `MemberName`.                                                                                                                                                            |
| `codegen/InvokerCodegen.kt`, `CallableCodegen.kt`, `AnnotationCodegen.kt`, `TypeCodegen.kt`                            | The annotation/type reproduction (177 lines) is the cleverest code in the module and has the least documentation. Add a KDoc explaining what `@Serializable(with=)` and `@Contextual` reproduction has to preserve and why.                                                      |
| `codegen/Names.kt`                                                                                                     | `ServiceTypeKrpc` (M1). One-constant `Names` object could merge into `ClassNames`.                                                                                                                                                                                               |
| `codegen/Util.kt`                                                                                                      | Entirely dead (L4) — delete the file.                                                                                                                                                                                                                                            |
| `README.md`                                                                                                            | H8 — stale module name, stale type names, no `@QueryService`.                                                                                                                                                                                                                    |
| `test/CompilationSupport.kt`, `OneProcessorTest.kt`, `QueryServiceValidationTest.kt`, `FireAndForgetValidationTest.kt` | Good compile-testing coverage of the *rejection* rules. Missing: golden-file tests of the *generated output* (a change to `QueryClientCodegen` cannot break a test today), and a test that the descriptor naming convention matches what the three runtime lookups expect (H10). |

### `surf-eventbus-platform`

| File                                             | Findings                                                                                                                                                                                                                                                                 |
|--------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `paper/PaperMain.kt`                             | Fine. Top-level `val plugin` accessor is unused.                                                                                                                                                                                                                         |
| `paper/PaperBootstrap.kt`                        | Sets `dataPath` on a `lateinit var` reached through a `ServiceLoader` cast (L23).                                                                                                                                                                                        |
| `paper/PaperEventBusInstance.kt`                 | `EventBusInstance.instance as PaperEventBusInstance` — an unchecked cast in a `get()` used everywhere; if two `EventBusInstance` implementations are on the classpath (Paper + standalone, both `@AutoService`), this throws `ClassCastException` at a random call site. |
| `velocity/VelocityMain.kt`                       | L22 (`runBlocking` in a constructor; `@Subscribe suspend fun`). Global mutable `lateinit var plugin`.                                                                                                                                                                    |
| `velocity/VelocityEventBusInstance.kt`           | Same cast issue. Private `Optional.getOrNull` shadow (L2).                                                                                                                                                                                                               |
| `standalone/StandaloneEventBusInstance.kt`       | Two constructors plus a static `configure()` side channel to get a value into a `ServiceLoader`-created instance — works, but it is the third data-path mechanism (L23) and the most fragile.                                                                            |
| `standalone/StandaloneLifecycleHookImpl.kt`      | `runBlocking` in `onInit` (acceptable — it is a bootstrap entry point). `configure(StandaloneEventBusInstance.serviceName, dataPath)` passes the *current* service name back into itself, so the name can never actually be set by a caller before this runs.            |
| `standalone/…/StandaloneEventBusInstanceTest.kt` | The only platform test in the repo.                                                                                                                                                                                                                                      |

### Tests (73 files)

Covered by the Testing Plan above. Per-file notes worth recording:

- `suite/RedisBusSuite.kt` — the `connectedBus` helper captures a non-volatile `var connected`
  mutated from coroutines; use an `AtomicBoolean` or a `Mutex`. Its KDoc documents C6's testability
  cost.
- `suite/EventSuiteTest.kt` — claims spec tests 1–12c, implements 1-5, 9, 10, 11, 12a.
- `rabbitmq/testing/RabbitBrokerExtension.kt`, `TestConfig.kt` — good fixtures; `TestConfig`
  shrinking the retry ladder to sub-second values is exactly the reason `retryTtlMillis` is
  configurable.
- `testing/RequiresDocker.kt` — good; its own message admits the skip risk (C1).
- `circuitbreaker/CircuitBreakerConcurrencyTest.kt` + `MutableClock.kt` + `MutableClockTest.kt` — a
  test helper with its own test. This is the standard the rest of the suite should meet.
- `structure/PackageLayoutTest.kt`, `PackageNamingTest.kt`, `InternalNotInAbiTest.kt`,
  `RelocationBaseTest.kt` — architecture tests. Excellent idea, and they are why the package rename
  held. They do **not** currently assert the things this audit found (naming casing, exception
  hierarchy membership, no Redisson in the ABI) — extend them; they are the cheapest possible
  enforcement mechanism for M1, H11 and C7.
- `core/FakeEventTransport.kt` — the fake exists and is good; there is no `FakeQueryTransport` or
  `FakeAuditSink`, which is why C5 was never caught.

### Docs

| File                                                                            | Findings                                                                                                                                                                                                                                                                                                                                                                          |
|---------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `README.md`                                                                     | Genuinely good — the "Events have no durability" section is the right thing to put first. Drift: the audit table (M11), "four layers" vs the fifth system-property layer, `HANDLER_FAILED` vs `EVENT_HANDLER_FAILED`, and it does not mention `SurfRabbitApi.builder(...)` existing as a parallel entry point. No installation/coordinates section, no "how to run this locally". |
| `docs/rollout-2.0.md`                                                           | Operationally excellent — ordered, honest about silent failures, has a checklist. Will need a new step if M1's wire renames land.                                                                                                                                                                                                                                                 |
| `docs/superpowers/plans/*.md`, `specs/*.md`, `notes/*.md` (14 files, 21k lines) | Planning artefacts in German, committed to the repo, referenced from *public API KDoc* ("see plan 3", "Task 10", "Plan 4"). Either move them out of the shipped repo, or stop referencing them from KDoc that users read. `notes/2026-08-01-audit-microservice-blocked.md` is correctly referenced from a code comment — that one is fine.                                        |
| `surf-eventbus-api/api/surf-eventbus-api.api`                                   | The ABI dump; C7 is visible in it. Good that it exists and is checked.                                                                                                                                                                                                                                                                                                            |

---

## Closing assessment

The engineering *judgement* in this codebase is above average — the reasoning comments, the retry
design, the chunk validation, the circuit breaker, the topology decisions, and the README's honesty
about durability are all better than typical. The problems are not judgement problems. They are
integration problems: a merge that renamed two projects into one namespace without finishing the
job, shipped without any automated verification.

The two changes with the highest ratio of value to effort are, in order: **CI that runs the tests**,
and **deleting the `redisConfig` global**. The first turns every other item in this document into
something that can be fixed once and stay fixed. The second unblocks testing half the product.
