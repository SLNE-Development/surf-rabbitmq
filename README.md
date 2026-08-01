# surf-eventbus

One bus, two transports, three verbs.

```kotlin
val bus = SurfEventBus.builder("surf-factions", dataPath)
    .withRedis()
    .withRabbit()
    .build()

bus.freezeAndConnect()
```

A process declares which transports it needs. What it can then *do* follows from that, not from
a provider choice: `publish` and `query` ride Redis, `rpc` rides RabbitMQ. Calling a verb whose
transport was never enabled is a loud error naming the builder call you forgot, not a silent
no-op.

---

## The three verbs

### `publish` — tell everyone

```kotlin
@Serializable
@BusEvent("faction.disbanded")
class FactionDisbanded(val factionId: String) : SurfBusEvent()

bus.publish(FactionDisbanded("f-17"))
```

```kotlin
class FactionListener {
    @SurfSubscribe("faction.disbanded")
    fun onDisbanded(event: FactionDisbanded) { … }
}

bus.subscribe(FactionListener())
```

**The promise: none.** Read [Events have no durability](#events-have-no-durability) before using
this for anything that matters.

`*` matches exactly one topic segment, `#` matches zero or more. A handler does **not** see
events published by its own process unless you write `@SurfSubscribe(includeSelf = true)` —
forgetting that was the usual cause of feedback loops before 2.0, so the default is off.

### `query` — ask everyone, take the first answer

```kotlin
@QueryService(timeoutMillis = 2_000)
interface WorldOwner {
    suspend fun ownerOf(world: String): String?
}

val owner: String? = bus.query<WorldOwner>().ownerOf("world-1")
```

**The promise: the first answer, or `null`.** Every process implementing the contract is asked.
The first to answer wins; later answers are discarded without error. If nobody answers within
the timeout you get `null`.

### `rpc` — call one named service

```kotlin
@RpcService(service = "surf-punish")
interface PunishService {
    suspend fun ban(player: UUID, reason: String): BanResult

    @FireAndForget
    suspend fun recordLogin(player: UUID)
}

val result = bus.rpc<PunishService>().ban(playerId, "cheating")
```

**The promise: exactly one instance handles it, durably.** The request waits in a durable queue
until an instance takes it. Three instances of `surf-punish` compete; one wins.

`@FireAndForget` returns as soon as the request is queued — no reply is sent or awaited. It must
return `Unit`; the processor rejects anything else at compile time.

**Addressing one named instance.** `RabbitTarget.InstanceTarget` sends to a specific instance
rather than to whichever one is free:

```kotlin
bus.rpc<PunishService>(RabbitTarget.InstanceTarget("lobby-3")).ban(playerId, "cheating")
```

This is for servers you already know by name — a lobby you are moving a player to, a process you
are asking about its own local state. It works only if that instance was given a stable id via
`.instanceName(...)`; a caller cannot address an instance whose id it cannot predict. It is
**not** a general server-to-server channel: the instance queue is exclusive, so a message to an
instance that is down is unroutable and produces an audit row rather than waiting.

---

## Which verb, and what happens when it fails

| | `publish` | `query` | `rpc` |
|---|---|---|---|
| Transport | Redis | Redis | RabbitMQ |
| Reaches | every subscriber | every implementor | exactly one instance |
| Answer | none | first, or `null` | the handler's return value |
| Survives an offline recipient | **no** | no — counts as abstention | **yes**, durable queue |
| A handler throws | audit row; neighbours unaffected | audit row; looks like abstention | exception travels to the caller |
| Nobody is listening | nothing happens | `null` after the timeout | the request waits in the queue |

---

## Events have no durability

This is the first thing to know about this project, because it is the thing most likely to cost
you data if you assume otherwise.

- A process that is offline when an event is published **never receives it**. No replay, no
  backlog, no redelivery.
- A handler that throws is **not** retried. It produces one audit row; its neighbours run
  normally.
- There is no acknowledgement. `publish` returning tells you the event reached Redis, not that
  anybody processed it.

**If it must not be lost, it is not an event.** Use a `@FireAndForget` RPC call: the same
fire-and-forget shape at the call site, but the request waits in a durable queue for a service
that is currently down.

Events are right for "something happened, react if you care": cache invalidation, UI refreshes,
presence changes.

---

## Writing a `@QueryService`

A query is a broadcast question. Every implementor is asked at once and the first answer wins,
so the contract must make **"I have no answer"** distinguishable from **"the answer is no"**.

`null` means *abstain* — not "no", and not an answer. That is why:

- every method must be `suspend`;
- every return type must be nullable;
- `Unit` is rejected — a question without an answer is an event;
- `@FireAndForget` is rejected, for the same reason.

All four are compile-time errors from the KSP processor, not runtime surprises.

**The trap.** With `Boolean?`, `null` is abstention and `false` is a real "no", so a handler
that means "no" must return `false`, never `null`:

```kotlin
@QueryService
interface BanCheck {
    // null = "I don't know this player", false = "not banned", true = "banned"
    suspend fun isBanned(player: UUID): Boolean?
}
```

If your answer type has no natural "no" value, give it one with a result type rather than
overloading `null`.

A contract is also its own channel: a process that does not implement `WorldOwner` never
subscribes to that contract's channel and never sees the traffic.

---

## Configuration

One file, `eventbus.yml`, in two sections:

```yaml
rabbitmq:
  host: rabbit.internal
  port: 5672
  username: surf
  password: __default__
redis:
  host: redis.internal
  port: 6379
```

`__default__` is the sentinel meaning *"I have no opinion"*: the value falls through to the next
layer. Every field carries it, which is what makes layering work **field by field** rather than
file by file.

### The four layers

`environment > eventbus-plugin.yml > eventbus.yml > built-in default`

- **`eventbus.yml`** — the host-wide file, in the platform plugin's data folder. Every service on
  the host reads it.
- **`eventbus-plugin.yml`** — a per-plugin file in that plugin's own data folder. It overrides
  the global file *only where it says something*; keys left at `__default__` keep the global
  value.
- **Environment** — wins over both. Intended for container platforms where secrets arrive at
  deployment time.

Both transports use all four layers, identically.

### Environment variables

| Variable | Field | Default |
|---|---|---|
| `SURF_EVENTBUS_RABBITMQ_HOST` | `rabbitmq.host` | `localhost` |
| `SURF_EVENTBUS_RABBITMQ_PORT` | `rabbitmq.port` | `5672`; range `1..65535` |
| `SURF_EVENTBUS_RABBITMQ_USERNAME` | `rabbitmq.username` | `guest` |
| `SURF_EVENTBUS_RABBITMQ_PASSWORD` | `rabbitmq.password` | `guest`; use a secret in production |
| `SURF_EVENTBUS_RABBITMQ_VHOST` | `rabbitmq.vhost` | `/` |
| `SURF_EVENTBUS_RABBITMQ_TIMEOUT` | `rabbitmq.timeout` | `30` seconds; must be positive |
| `SURF_EVENTBUS_RABBITMQ_REQUEST_TIMEOUT_SECONDS` | `rabbitmq.requestTimeoutSeconds` | `60` seconds; must be positive |
| `SURF_EVENTBUS_RABBITMQ_PUBLISHER_POOL_SIZE` | `rabbitmq.publisherPoolSize` | `2`; must be positive |
| `SURF_EVENTBUS_RABBITMQ_SERVER_PREFETCH_COUNT` | `rabbitmq.serverPrefetchCount` | `128`; range `0..32767` |
| `SURF_EVENTBUS_RABBITMQ_PERSIST_REQUESTS` | `rabbitmq.persistRequests` | `true` |
| `SURF_EVENTBUS_RABBITMQ_PERSIST_RESPONSES` | `rabbitmq.persistResponses` | `false` |
| `SURF_EVENTBUS_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED` | `rabbitmq.outgoingRequestChunkingEnabled` | `false` |
| `SURF_EVENTBUS_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED` | `rabbitmq.outgoingResponseChunkingEnabled` | `true` |
| `SURF_EVENTBUS_REDIS_HOST` | `redis.host` | `localhost` |
| `SURF_EVENTBUS_REDIS_PORT` | `redis.port` | `6379`; range `1..65535` |
| `SURF_EVENTBUS_REDIS_PASSWORD` | `redis.password` | none |
| `SURF_EVENTBUS_REDIS_CLIENT_NAME` | `redis.clientName` | generated per process |
| `SURF_EVENTBUS_AUDIT_SERVICE` | `rabbitmq.auditServiceName` | `surf-eventbus-audit` |

Every variable lives under `SURF_EVENTBUS_`. The pre-2.0 names (`SURF_RABBITMQ_*`,
`SURF_REDIS_*`) are **no longer read** — see `docs/rollout-2.0.md` before deploying.

---

## Redis sync structures: create them before `freeze()`

`SyncMap`, `SyncSet`, `SyncList` and `SyncValue` must be created while the API is still mutable:

```kotlin
val onlinePlayers = redis.syncSet<UUID>("online-players")   // before freeze
bus.freezeAndConnect()                                      // now immutable
```

Creating one afterwards throws. The reason is the same one behind freezing at all: the structure
needs a subscription, and adding one to a connected client leaves a window where updates are
missed silently. Failing loudly at construction beats losing writes quietly.

---

## The audit trail

There is no dead-letter queue. Every path where a message is lost writes an audit report
instead:

| Kind | When |
|---|---|
| `HANDLER_FAILED` | an RPC or event handler threw |
| `QUERY_HANDLER_FAILED` | a query handler threw |
| `UNROUTABLE` | a published message reached no queue |
| `UNDESERIALIZABLE` | a message could not be read, including a protocol-version mismatch |
| `CHUNK_SERIES_EXPIRED` | a chunked message never completed |

**The service that writes these to a database does not exist yet.** Reports go out with
`mandatory = false`, so they are discarded while nothing consumes them. That costs nothing and
blocks nothing — but it means **a missing audit row is not evidence that nothing went wrong**.
Treat the audit trail as best effort and the logs as the actual record until the writer ships.

---

## Modules

| Module | What it is |
|---|---|
| `surf-eventbus-api` | the surface consumers compile against |
| `surf-eventbus-core` | the implementation of both transports |
| `surf-eventbus-ksp` | the processor behind `@QueryService` and `@RpcService` |
| `surf-eventbus-platform-{paper,velocity,standalone}` | one plugin per platform, serving both transports |

A server loads **one** plugin, and therefore one Netty. Before 2.0 it loaded two.

---

## Upgrading from 1.6.x

Version 2.0 is neither wire- nor ABI-compatible with 1.6.x; all services must be deployed
together. **`docs/rollout-2.0.md` is the checklist** — renamed environment variables, queues that
must be deleted first, and an audit path that has no writer yet.

The headline API changes:

```kotlin
// before - one API instance per target service, two TCP connections
val factions = ClientRabbitMQApi.create("surf-factions", dataPath)
val punish   = ClientRabbitMQApi.create("surf-punish", dataPath)

// after - one bus, addressed per call
val bus = SurfEventBus.builder("surf-web", dataPath).withRabbit().build()
bus.rpc<FactionService>()
bus.rpc<PunishService>()
```

`@RabbitSubscribe` and `SubscriptionMode` are gone. The choice they encoded — run on one
instance or on all of them — is now the choice between the verbs: `rpc` (one instance, durable)
versus `publish` (all instances, no durability).
