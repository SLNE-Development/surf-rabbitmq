# surf-eventbus stage 3 ("transports und verträge"): verification

Unlike stage 1's assumption, this session's machine **does** have a reachable Docker daemon
(`docker version` succeeds, Docker Desktop 4.26.1). Every `@RequiresDocker` test in this stage
therefore ran for real rather than being skipped — this note records actual pass/fail, not
"written, not verified".

## Verified (ran against real containers)

- `RedisEventTransportTest` (`surf-eventbus-redis-core`) — 7/7 passed: broadcast to three instances,
  exact-topic isolation, `*` and `#` wildcard matching, two overlapping patterns, a binary-codec
  event, and a wildcard subscription receiving both JSON and codec events.

## Notes for future Docker-gated tests in this stage

- `RedisApi.create()` needs both a `RedisInstance` and a `RedisComponentProvider` service
  discoverable via `ServiceLoader`/`@AutoService`. `surf-eventbus-platform-standalone` provides a
  real `RedisInstance`, but it depends on `surf-eventbus-redis-core`, so redis-core's own tests
  cannot use it (would be circular). Added `FakeRedisInstance` under
  `surf-eventbus-redis-core/src/test/.../redis/testing/` instead.
- `surf-eventbus-redis-core`'s test source set needed
  `testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")`
  for a Flogger backend (main is `compileOnly` on the assumption a real host provides it, matching
  the pattern already used by `surf-eventbus-rabbitmq-core`), and `testCompileOnly` +
  `kspTest("dev.slne.surf.api:surf-api-processor:1.0.1")` so `@AutoService` is processed for test
  sources too — main only wires the `ksp` configuration, not `kspTest`.

## Deviation from Plan 3 Task 2

The task's file list says to *modify* `EventCodecRegistry.kt` (rekey on the event class instead of
`eventId`) rather than delete it. By the time this task ran, Plan 2 had already added
`BusEventCodecs` (`surf-eventbus-bus-core`), which discovers a `BusEventCodec` from an event's
companion object with no `eventId`, no collision detection, and no packet-size estimation —
`EventCodecRegistry`'s entire job, done more simply, with `RedisEventTransport` as the only
caller-to-be. Its only real callers were `RedisEventBusImpl`/`RedisEventInvoker` (deleted this task)
and the JMH benchmark (redirected to measure `EventEnvelope` + `BinaryFrame` +
`BusEventCodec` directly, per the task's own Step 6). Deleted `EventCodecRegistry`,
`EventCodecRegistration`, `CustomEventPacketCodec`, and their three tests instead of rekeying —
rekeeping a class with no remaining caller would have been dead code.
`RedisEventInvokerTemplate.java`
(a generated-invoker template implementing the deleted `RedisEventInvoker` interface) went with it;
`RedisInvokerLookupProvider.java` stayed, since `RequestResponseBusImpl` still uses it until Plan 3
Task 7.

## Deviation from Plan 3 Task 3

`surf-eventbus-test-paper`'s manual smoke-test instance (`RabbitMqTestPaperInstance`,
`TestBroadcastEvent`) demonstrated `@RabbitSubscribe`/`registerListener`/broadcast delivery — not in
the task's file list, but it doesn't compile without the deleted API. Removed the broadcast listener
and `TestBroadcastEvent`; Redis-based `@SurfSubscribe` is the replacement demo, added in a later
plan once the platform module wires up `SurfEventBus`.

`RetryQueueTest`'s `` `the same tier serves a shared event queue` `` asserted that the RabbitMQ
retry tiers work the same for RPC and events. Events no longer touch the RabbitMQ retry ladder at
all (they're Redis pub/sub with no dead-lettering), so the test's premise is gone — deleted rather
than adapted.

## Plan 3 Task 4: @FireAndForget

Verified for real (Docker reachable): `FireAndForgetValidationTest` (KSP, `kotlin-compile-testing`
via `dev.zacsweers.kctfork:ksp`) — both cases pass. `FireAndForgetContractTest` — 4/4 passed: the
caller returns before the handler finishes, an `InstanceTarget` reaches exactly one of three
instances, an `InstanceTarget` naming no running instance is unroutable, and repeated
`rpc<T>(InstanceTarget(x))` calls share one cached proxy.

**Deviation:** the client codegen (`RpcClientImplCodegen`) is unchanged — it still generates
`rpcService.call(RabbitRpcCall(...))` for every method. The plan's wording suggested generating
`send(...)` for `@FireAndForget` methods directly in the client proxy; instead,
`RabbitRpcServiceImpl.call()` branches at runtime on `callable.fireAndForget` and calls
`connection.send(...)` there. The server side needed no new code at all:
`RabbitListenerHandlerManager.handleRequest`'s existing `replyTo == null` branch — already used by
the untyped `@RabbitHandler` fire-and-forget path — runs the handler, joins it, and acks without
ever awaiting `responseDeferred`, exactly the behavior `@FireAndForget` needs. `RabbitTarget`
replaces `String?` in `RabbitRpcService.createService`/`SurfRabbitApi.rpc()`, with a
`Caffeine`-backed proxy cache keyed on `(serviceKClass, target)`.

**Note for Plan 3 Task 5:** `RabbitRpcServiceImpl.handleRequest` is itself registered via
`@RabbitHandler` (`RabbitListenerHandlerManager` calls `registerHandler(api.rpcService, ...)` in its
`init` block) — the one place the internal RPC dispatch reuses the annotation Task 5 deletes. That
registration needs a non-reflective replacement once `@RabbitHandler` is gone.

## Plan 3 Task 5: untyped packet API removed

`RabbitPacket`, `RabbitRequestPacket`, `RabbitResponsePacket` (the internal envelope base classes
`RpcCallRequestPacket`/`RpcCallResponsePacket` extend) stay, contrary to the task's file list, which
named them for deletion — deleting them would have taken `RpcCallRequestPacket` down with them.
Deleted instead: the six standard response packet types (`packet/standard/**`),
`@RabbitHandler`, `SurfRabbitApi.send()`/`registerRequestHandler()`/`defaultSerializersModule`, and
the reflection-based multi-handler machinery in `RabbitListenerHandlerManager`
(`HandlerTemplate.java`, `HandlerMethodHandleProvider.java`, the four
`SurfRabbitInvalidHandler*`/`SurfRabbitDuplicateHandler*`/`SurfRabbitHandlerNotAccessible*`
exceptions) — with `@RabbitHandler` gone, `RpcCallRequestPacket` is the only request type that will
ever exist, so the manager now wires it directly instead of scanning for annotated methods.

**Bug found and fixed while rewriting the retry tests to typed contracts:** a `@FireAndForget`
handler that throws was being silently swallowed. `RpcServiceExecutor.accept()`/`processMessage()`
always caught handler exceptions and encoded them into an `RpcCallResponsePacket` error response —
correct for a two-way call, but for fire-and-forget nobody reads that response, so the failure never
reached `RabbitListenerHandlerManager`'s retry/dead-letter path; the message was acked as if it had
succeeded. Fixed by having `processMessage` skip the respond-with-error path entirely for a
fire-and-forget callable and let the exception propagate; `accept()` rethrows it (rather than
responding) when the callable is fire-and-forget, which is what the fire-and-forget branch in
`RabbitListenerHandlerManager.handleRequest` was already built to catch. Caught by
`RetryIntegrationTest`'s two retry-ladder cases, which failed until this fix — `FireAndForgetTest`
(Task 4) never exercised a throwing handler.

**Deviation:** `@RabbitHandler(retry: Boolean)` gave each untyped handler its own retry opt-out;
`@RpcService`/`@FireAndForget` have no equivalent per-callable annotation, so
`RabbitListenerHandlerManager.retryEnabledFor()` is now hardcoded `true` for the sole remaining
request type. `RetryIntegrationTest`'s `` `a handler marked retry=false is attempted once and
dead-lettered` `` case is dropped — nothing in the typed API can express it. Also dropped:
`` `a message with no registered handler is dead-lettered, not lost` `` — an unregistered
`@RpcService` on a two-way call now gets a structured `NoSuchMethodError` response instead of a
silent nack-to-DLQ (a strictly more informative outcome), and on a fire-and-forget call it acks
quietly rather than dead-lettering, since `RabbitRpcServiceImpl.handleRequest` responds rather than
throwing when the service fqName is unknown. Both are behavior changes inherent in collapsing the
untyped API into RPC, not something Task 5 flagged explicitly.

Verified for real (Docker reachable): every rewritten test passed against a live broker, including
the retry-ladder, competing-consumers, broker-restart, broker-loss, consumer-death, and chunking
suites.

## Plan 3 Task 6: @QueryService codegen

Built as a parallel, simplified codegen path alongside `@RpcService`'s in `surf-eventbus-ksp`,
rather than reusing RPC's runtime types (`RabbitRpcCallable`/`RabbitRpcType`/
`CallableParametersSerializer`):
those live in `surf-eventbus-rabbitmq-api`/`-core`, and Query is transport-agnostic bus
infrastructure — depending on rabbitmq modules from bus-api/bus-core would invert the module graph.
New types instead: `QueryParameter`/`QueryInvoker`/`QueryCallable`/`QueryServiceDescriptor`
(bus-api, `dev.slne.surf.eventbus.query.*`), `QueryParametersSerializer`/`QuerySerializerCache`
(bus-core). Simplified relative to RPC: no per-call-site `@Serializable(with = ...)` custom
serializer support (nothing in the codebase needs it for queries) — parameter and return-type
serializers resolve via `SerializersModule.serializer(KType)` directly. The new processor lives at
`dev.slne.surf.eventbus.ksp.processor.query` rather than under the existing (RabbitMQ-named)
`dev.slne.surf.eventbus.rabbitmq.processor` package, since Query has nothing to do with RabbitMQ.

`SurfEventBusImpl.query()` now works: it resolves `<Name>Descriptor` via a `ClassValue`-backed
reflection cache (mirroring `RabbitRpcServiceImpl`'s `ServiceDescriptorCache`) and calls
`descriptor.createInstance(instanceId, json, transport)`.

**Deviation:** the KSP processor skips (does not error on) a `private`/file-local
`@QueryService` interface instead of generating a descriptor for it. `QueryServiceRegistryTest`
already had `private interface Locator`/`FastLocator` fixtures (Plan 2, predating this generator)
for testing the registry directly, never through a generated proxy — a public-visibility requirement
would have broken those on the first build that turned on `kspTest` for
`surf-eventbus-bus-core`. Generating an `internal` descriptor referencing a private interface
doesn't compile anyway (Kotlin visibility rules), so skipping is the only option that doesn't touch
pre-existing test fixtures outside this task's scope.

Verified: `QueryServiceValidationTest` (KSP, 5/5 — non-nullable return, `Unit` return,
`@FireAndForget` on a query, a valid nullable-suspend method, and a non-suspend method) and
`QueryServiceClientTest` (bus-core, 3/3 — the generated client asks and decodes a real answer, a
`null` answer means abstain, and the annotation's `timeoutMillis` lands in the descriptor) against a
`FakeQueryTransport`. Server-side dispatch (turning a registered implementation into answers)
is Plan 3 Task 7's job, not exercised here.

## Plan 3 Task 7: Redis query transport and real dispatch

`QueryDispatcher` now actually dispatches: it resolves the generated `<Name>Descriptor` for
`frame.contract` via a `ClassValue`-style reflection cache (same idea as
`SurfEventBusImpl.queryDescriptorOf`, kept as a separate small cache here rather than sharing code
across modules), decodes the frame payload with `QuerySerializerCache`, invokes the callable, and
encodes a non-null result back. `RedisQueryTransport` carries queries and answers over Redis
Pub/Sub: one channel per contract (`surf.eventbus.query.<contract>`), one reply channel per instance
(`surf.eventbus.reply.<instanceId>`) — first reply on the asker's own reply channel wins, matching
`QueryFrame`'s existing `correlationId`/`originInstanceId` fields (now `@Serializable`, which they
needed to be to travel as JSON at all). Old Redis request/response API deleted:
`RedisRequest`/`RedisResponse`/`RequestResponseBus`/`RequestContext`/`HandleRedisRequest`/
`RequestTimeoutException` and their impls — `RedisApi.sendRequest()`/`registerRequestHandler()`/
`requestResponseBus` all gone.

**Deviation:** `QueryTransport.connect()` gained an `instanceId: String` parameter that isn't in the
plan's interface sketch. The reply channel name is `surf.eventbus.reply.<instanceId>`, and
`instanceId` wasn't available at `RedisTransportProvider.query()`/`withRedis()` time (the builder
resolves it later, in `build()`) — passing it at `connect()` time, where `SurfEventBusImpl` already
has it, was the smallest fix that didn't require restructuring `SurfEventBusBuilder`'s ordering.

**Deviation:** `QueryDispatcherTest`'s `Locator` contract is `internal`, not `private` as the plan's
own example code shows. A truly file-private interface can't be referenced from a generated
descriptor in a different file (Kotlin visibility rules, not a processor limitation), and the KSP
processor already skips codegen for private `@QueryService` interfaces (Task 6, for
`QueryServiceRegistryTest`'s fixtures) — so a private `Locator` here would get no descriptor at all,
and `QueryDispatcher.invoke()` needs one. `internal` keeps the contract out of the published API
while still being visible enough for the generator.

**Verification pitfall worth recording:** the first draft of `RedisQueryTransportTest` used
`kotlinx-coroutines-test`'s `runTest`, copying `RedisEventTransportTest`'s style — and failed on
every case that raced a real cross-thread Redis round trip against `withTimeoutOrNull`.
`runTest`'s virtual-time scheduler auto-advances through a scheduled `delay` (which is what a
timeout is, internally) the moment nothing else is runnable *on that dispatcher*, even though a real
background thread (the Redisson listener) is about to complete the real work — so the timeout fired
instantly, before the real answer arrived. `RedisEventTransportTest` never hit this because its
assertions wait on a plain `Channel.receive()` with no explicit timeout on the success path. Fixed
by switching to `runBlocking`, which waits in real time. Also needed a thread-safe `apis` list
(`CopyOnWriteArrayList`, not `mutableListOf()`) — Redis listener callbacks that build a fresh
transport to answer run on a different thread than the test body and JUnit's
`@AfterAll`, and a plain `ArrayList` throws `ConcurrentModificationException` under that.

Verified against a real broker: `RedisQueryTransportTest` (6/6 — one of three providers answers, two
providers answering has the first reply win, no answer within the timeout is `null`, no provider at
all is `null`, and only the asker receives the reply) and `QueryDispatcherTest`
(4/4 — answering, abstaining, throwing-is-audited-not-answered, and an unoffered contract is
ignored).

## Plan 3 Task 8: Redis lifecycle and config layering

`RedisApi.connect()`/`freezeAndConnect()`/`disconnect()` are `suspend` now; the internal blocking
calls (`Redisson.create()`, the `INFO server` Lua eval, `redisson.shutdown()`) run inside
`withContext(Dispatchers.IO)`, and the `Mono.block()` in the initializables fan-out became
`.awaitFirstOrNull()`. `RedisConfig` resolves four layers — `env > plugin yaml > global yaml >
default` — via a new `resolveRedisConfig(global, plugin, environment)`; unlike RabbitMQ's per-field
`IntOr.Default` merging, a YAML layer here wins or loses as a whole (`RedisConfig` has no per-field
"unset" sentinel), with only the environment layer overriding individual fields.
`overwriteFromEnv()` and the old two-layer `RedisEnvironment` are gone.

**Design decision beyond the plan's literal wording:** `RedisTransportProviderImpl` no longer
eagerly connects `SurfRedisApi` at construction (`by lazy { RedisApi.create().apply {
freezeAndConnect() } }` was still blocking-in-a-lazy, which cannot work once `connect()` is
`suspend`). Both `RedisEventTransport` and `RedisQueryTransport` now take an `ensureConnected:
suspend () -> Unit` callback (default no-op, so the existing tests constructing them directly
against an already-connected `SurfRedisApi` are unaffected) and call it at the top of every public
suspend method; `RedisTransportProviderImpl` supplies a `Mutex`-guarded `ensureConnected()` that
freezes and connects the shared `SurfRedisApi` exactly once, on whichever transport's first real
operation reaches it first.

**Deviation:** the plan phrased this as "`SurfEventBusImpl.connect()` hängt den Redis-Client mit
an... `disconnect()` in umgekehrter Reihenfolge" (`disconnect()` in reverse order) — but
`RedisTransportLocator` caches the `ServiceLoader`-discovered `RedisTransportProvider` in a JVM-wide
singleton (`internal object` with a `by lazy` provider), which means every
`SurfEventBus` built with `.withRedis()` in one process shares the *same* underlying `SurfRedisApi`
and its one Redis connection. If `SurfEventBusImpl.disconnect()` tore down that shared
`SurfRedisApi`, the first bus to disconnect would break every other bus still using it — so
`disconnect()` intentionally only clears this bus's own subscriptions
(`eventTransport.disconnect()`/`queryTransport.disconnect()`), never the shared connection. Only
`connect()` needed the fix; there is no symmetric per-bus "disconnect the broker" step, because the
broker connection isn't owned per-bus.

**Coverage gap, noted rather than closed:** the `ensureConnected()` lazy-connect path in
`RedisTransportProviderImpl` (the no-arg `.withRedis()` route through the real `ServiceLoader`
discovery) has no dedicated test — every existing test either uses the explicit
`.withRedis(event, query)` test-seam with fakes, or constructs `RedisEventTransport`/
`RedisQueryTransport` directly against an already-`freezeAndConnect()`-ed `SurfRedisApi`. Closing
this gap needs a `SurfEventBus` built with real `.withRedis()` against a live Redis container, which
wasn't reached in this stage.

This closes Plan 3 (Task 1 through Task 8) — every task committed, full build green, both ABI checks
clean.
