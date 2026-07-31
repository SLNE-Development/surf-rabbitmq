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
