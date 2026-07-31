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
