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
