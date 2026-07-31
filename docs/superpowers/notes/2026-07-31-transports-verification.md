# surf-eventbus stage 3 ("transports und verträge"): verification

Unlike stage 1's assumption, this session's machine **does** have a reachable Docker daemon
(`docker version` succeeds, Docker Desktop 4.26.1). Every `@RequiresDocker` test in this stage
therefore ran for real rather than being skipped — this note records actual pass/fail, not
"written, not verified".

## Verified (ran against real containers)

- `RedisEventTransportTest` (`surf-eventbus-redis-core`) — 7/7 passed: broadcast to three
  instances, exact-topic isolation, `*` and `#` wildcard matching, two overlapping patterns, a
  binary-codec event, and a wildcard subscription receiving both JSON and codec events.

## Notes for future Docker-gated tests in this stage

- `RedisApi.create()` needs both a `RedisInstance` and a `RedisComponentProvider` service
  discoverable via `ServiceLoader`/`@AutoService`. `surf-eventbus-platform-standalone` provides
  a real `RedisInstance`, but it depends on `surf-eventbus-redis-core`, so redis-core's own tests
  cannot use it (would be circular). Added `FakeRedisInstance` under
  `surf-eventbus-redis-core/src/test/.../redis/testing/` instead.
- `surf-eventbus-redis-core`'s test source set needed `testRuntimeOnly("dev.slne.surf.api:surf-api-standalone:+")`
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
caller-to-be. Its only real callers were `RedisEventBusImpl`/`RedisEventInvoker` (deleted this
task) and the JMH benchmark (redirected to measure `EventEnvelope` + `BinaryFrame` +
`BusEventCodec` directly, per the task's own Step 6). Deleted `EventCodecRegistry`,
`EventCodecRegistration`, `CustomEventPacketCodec`, and their three tests instead of rekeying —
rekeeping a class with no remaining caller would have been dead code. `RedisEventInvokerTemplate.java`
(a generated-invoker template implementing the deleted `RedisEventInvoker` interface) went with it;
`RedisInvokerLookupProvider.java` stayed, since `RequestResponseBusImpl` still uses it until Plan 3
Task 7.

## Deviation from Plan 3 Task 3

`surf-eventbus-test-paper`'s manual smoke-test instance (`RabbitMqTestPaperInstance`,
`TestBroadcastEvent`) demonstrated `@RabbitSubscribe`/`registerListener`/broadcast delivery — not
in the task's file list, but it doesn't compile without the deleted API. Removed the broadcast
listener and `TestBroadcastEvent`; Redis-based `@SurfSubscribe` is the replacement demo, added in
a later plan once the platform module wires up `SurfEventBus`.

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
`@RabbitHandler` (`RabbitListenerHandlerManager` calls `registerHandler(api.rpcService, ...)` in
its `init` block) — the one place the internal RPC dispatch reuses the annotation Task 5 deletes.
That registration needs a non-reflective replacement once `@RabbitHandler` is gone.

## Plan 3 Task 5: untyped packet API removed

`RabbitPacket`, `RabbitRequestPacket`, `RabbitResponsePacket` (the internal envelope base
classes `RpcCallRequestPacket`/`RpcCallResponsePacket` extend) stay, contrary to the task's file
list, which named them for deletion — deleting them would have taken `RpcCallRequestPacket` down
with them. Deleted instead: the six standard response packet types (`packet/standard/**`),
`@RabbitHandler`, `SurfRabbitApi.send()`/`registerRequestHandler()`/`defaultSerializersModule`,
and the reflection-based multi-handler machinery in `RabbitListenerHandlerManager`
(`HandlerTemplate.java`, `HandlerMethodHandleProvider.java`, the four
`SurfRabbitInvalidHandler*`/`SurfRabbitDuplicateHandler*`/`SurfRabbitHandlerNotAccessible*`
exceptions) — with `@RabbitHandler` gone, `RpcCallRequestPacket` is the only request type that
will ever exist, so the manager now wires it directly instead of scanning for annotated methods.

**Bug found and fixed while rewriting the retry tests to typed contracts:** a `@FireAndForget`
handler that throws was being silently swallowed. `RpcServiceExecutor.accept()`/`processMessage()`
always caught handler exceptions and encoded them into an `RpcCallResponsePacket` error response —
correct for a two-way call, but for fire-and-forget nobody reads that response, so the failure
never reached `RabbitListenerHandlerManager`'s retry/dead-letter path; the message was acked as if
it had succeeded. Fixed by having `processMessage` skip the respond-with-error path entirely for a
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
quietly rather than dead-lettering, since `RabbitRpcServiceImpl.handleRequest` responds rather
than throwing when the service fqName is unknown. Both are behavior changes inherent in
collapsing the untyped API into RPC, not something Task 5 flagged explicitly.

Verified for real (Docker reachable): every rewritten test passed against a live broker,
including the retry-ladder, competing-consumers, broker-restart, broker-loss, consumer-death, and
chunking suites.

## Plan 3 Task 6: @QueryService codegen

Built as a parallel, simplified codegen path alongside `@RpcService`'s in `surf-eventbus-ksp`,
rather than reusing RPC's runtime types (`RabbitRpcCallable`/`RabbitRpcType`/`CallableParametersSerializer`):
those live in `surf-eventbus-rabbitmq-api`/`-core`, and Query is transport-agnostic bus
infrastructure — depending on rabbitmq modules from bus-api/bus-core would invert the module
graph. New types instead: `QueryParameter`/`QueryInvoker`/`QueryCallable`/`QueryServiceDescriptor`
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
for testing the registry directly, never through a generated proxy — a public-visibility
requirement would have broken those on the first build that turned on `kspTest` for
`surf-eventbus-bus-core`. Generating an `internal` descriptor referencing a private interface
doesn't compile anyway (Kotlin visibility rules), so skipping is the only option that doesn't
touch pre-existing test fixtures outside this task's scope.

Verified: `QueryServiceValidationTest` (KSP, 5/5 — non-nullable return, `Unit` return,
`@FireAndForget` on a query, a valid nullable-suspend method, and a non-suspend method) and
`QueryServiceClientTest` (bus-core, 3/3 — the generated client asks and decodes a real answer, a
`null` answer means abstain, and the annotation's `timeoutMillis` lands in the descriptor) against
a `FakeQueryTransport`. Server-side dispatch (turning a registered implementation into answers)
is Plan 3 Task 7's job, not exercised here.
