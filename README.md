# surf-rabbitmq

RabbitMQ configuration is loaded from the existing YAML configuration and can be overridden at
runtime with environment variables. This is useful for Coolify and other container platforms where
connection details and secrets should be supplied at deployment time.

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

## Runtime environment variables

Environment variables take precedence over plugin-specific `rabbitmq.yml` values, which take
precedence over the global RabbitMQ configuration and built-in defaults. For the two outgoing
chunking settings, the legacy JVM system properties remain supported below YAML and above the
built-in default:

`environment > plugin YAML > global YAML > legacy system property > built-in default`

When no plugin-specific configuration is involved, the plugin YAML layer is simply omitted. A
present but malformed environment value fails startup and names the offending variable. Boolean
values accept only case-insensitive `true` or `false`.

| Environment variable                               | YAML option                       | Built-in/default behavior                                           |
|----------------------------------------------------|-----------------------------------|---------------------------------------------------------------------|
| `SURF_RABBITMQ_HOST`                               | `host`                            | `localhost`                                                         |
| `SURF_RABBITMQ_PORT`                               | `port`                            | `5672`; valid range `1..65535`                                      |
| `SURF_RABBITMQ_USERNAME`                           | `username`                        | `guest`                                                             |
| `SURF_RABBITMQ_PASSWORD`                           | `password`                        | `guest`; configure as a secret in production                        |
| `SURF_RABBITMQ_VHOST`                              | `vhost`                           | `/`                                                                 |
| `SURF_RABBITMQ_TIMEOUT`                            | `timeout`                         | `30` seconds; must be positive                                      |
| `SURF_RABBITMQ_REQUEST_TIMEOUT_SECONDS`            | `requestTimeoutSeconds`           | `60` seconds; must be positive                                      |
| `SURF_RABBITMQ_PUBLISHER_POOL_SIZE`                | `publisherPoolSize`               | `2`; must be positive                                               |
| `SURF_RABBITMQ_SERVER_PREFETCH_COUNT`              | `serverPrefetchCount`             | `128`; valid range `0..32767`                                       |
| `SURF_RABBITMQ_PERSIST_REQUESTS`                   | `persistRequests`                 | `true`                                                              |
| `SURF_RABBITMQ_PERSIST_RESPONSES`                  | `persistResponses`                | `false`                                                             |
| `SURF_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED`  | `outgoingRequestChunkingEnabled`  | legacy `surf.rabbitmq.outgoingRequestChunkingEnabled`, then `false` |
| `SURF_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED` | `outgoingResponseChunkingEnabled` | legacy `surf.rabbitmq.outgoingResponseChunkingEnabled`, then `true` |

## Coolify example

Configure the following in the Coolify service environment. Store the password as a secret and use
the host name assigned to the external RabbitMQ service:

```dotenv
SURF_RABBITMQ_HOST=rabbitmq.internal
SURF_RABBITMQ_PORT=5672
SURF_RABBITMQ_USERNAME=surf-service
SURF_RABBITMQ_PASSWORD=
SURF_RABBITMQ_VHOST=/surf
SURF_RABBITMQ_TIMEOUT=30
SURF_RABBITMQ_REQUEST_TIMEOUT_SECONDS=60
SURF_RABBITMQ_PUBLISHER_POOL_SIZE=2
SURF_RABBITMQ_SERVER_PREFETCH_COUNT=128
SURF_RABBITMQ_PERSIST_REQUESTS=true
SURF_RABBITMQ_PERSIST_RESPONSES=false
SURF_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED=false
SURF_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED=true
```

Do not commit deployment credentials. The application container connects to RabbitMQ as an external
service; it does not install or start RabbitMQ itself.

## Events

Events go to the `surf.events` topic exchange. The publisher does not know who listens, so
adding or removing a subscriber never touches the publisher.

```kotlin
@Serializable
@RabbitEvent("faction.disbanded")
class FactionDisbandedEvent(val factionId: UUID) : RabbitEventPacket()

rabbit.publish(FactionDisbandedEvent(id))
```

### Choosing a subscription mode

This is the decision that matters. It controls whether a handler runs once or once per running
instance.

| Mode | Runs on | Survives downtime | Use for |
|---|---|---|---|
| `SHARED` (default) | exactly one instance | yes, durable queue | database writes, statistics, webhooks |
| `BROADCAST` | every instance | no, ephemeral queue | cache invalidation, config reload, kicking a player |

With eight instances of `surf-transaction` running:

```kotlin
// wrong - writes to the database eight times
@RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
suspend fun onBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}

// right - exactly one instance writes
@RabbitSubscribe
suspend fun onBanned(event: PlayerBannedEvent) {
    database.freezeAccount(event.playerId)
}
```

On Paper and Velocity, `BROADCAST` is usually what you want: every server has to invalidate its
own local state.

### Patterns

`*` matches exactly one segment, `#` matches zero or more.

```kotlin
@RabbitSubscribe(topic = "faction.*.disbanded")   // faction.abc.disbanded
@RabbitSubscribe(topic = "player.#")              // player.punish.ban, player.join, player
```

## Fire-and-forget

Delivered to exactly one instance, with no reply awaited:

```kotlin
rabbit.send(PlayerKilledPacket(killer, victim))
rabbit.send(TransferPlayerPacket(uuid), target = InstanceTarget("lobby-3"))
```

Unlike an event, this waits in a durable queue when no instance is running, so the work happens
once the service comes back. Choose it over a broadcast whenever the message must not be lost.

## What happens when things fail

| Situation | Behaviour |
|---|---|
| All instances of a service are down, RPC | The message waits in the durable queue. The caller gets `SurfRabbitRequestTimeoutException` after the request timeout; the message's TTL then removes it |
| All instances down, fire-and-forget | The message waits and is processed once the service returns |
| All instances down, broadcast | The event is lost — no queue is bound |
| The service does not exist at all (RPC **and** fire-and-forget) | Immediate `SurfRabbitServiceUnavailableException`, plus a copy in `surf.unroutable` |
| A handler throws | Retried after 10 s, 60 s and 300 s, then moved to `surf.dlq.<service>` |
| A handler marked `retry = false` throws | Straight to `surf.dlq.<service>` |
| A service queue is full | `reject-publish` — the publisher gets a `SurfRabbitPublishException` rather than older messages being dropped |
| A service's *event* queue is full | `drop-head` — that service loses its oldest events; publishers and other subscribers are unaffected |
| A request times out | `SurfRabbitRequestTimeoutException` — **not** retried and not counted by the breaker: the handler may already have run |
| Repeated transport failures to one service | Its circuit breaker opens for 30 s; other services are unaffected |
| The broker is unreachable | Automatic recovery with exponential backoff and jitter |

Note the distinction in rows one and four: a service whose instances have all stopped still has
its durable queue, so its messages wait. Only a service that was never deployed — or a
misspelled name — produces an unroutable message.

### Retries and idempotency

Retries mean **at-least-once** delivery: a handler can run more than once for the same message.
That is harmless for "kick this player" and a bug for "deduct 100 coins" unless the handler is
idempotent.

```kotlin
@RabbitHandler(retry = false)
suspend fun onTransfer(packet: TransferPacket) { … }

@RabbitSubscribe(retry = false)
suspend fun onCoinsTransferred(event: CoinsTransferredEvent) { … }
```

### Scaling

Running more instances of a service increases throughput with no configuration: they all
consume the same queue and the broker distributes the work.

There is **no ordering guarantee** across instances. Two messages concerning the same player can
be processed simultaneously on different instances. For state-changing services this has to be
handled inside the service — a database transaction or a per-entity lock — no messaging system
solves it for you.

## Architecture: no microservice chains

A microservice does not call another microservice over RPC. The intended shape is:

```
Paper plugin surf-punish    ──RPC──▶  microservice surf-punish
Paper plugin surf-factions  ──RPC──▶  microservice surf-factions

microservice surf-transaction ──publish──▶ surf.events ──▶ whoever subscribed
```

Each plugin exposes a local API (`Faction.create()`) that internally calls its own
microservice. A microservice knows nothing about other microservices.

Synchronous chains between services tie their availability together: one link fails and the
whole chain fails, while latencies add up. The result is a distributed monolith.

**Publishing events is the intended alternative.** The publisher does not know its subscribers,
no availability coupling is created, and with nobody subscribed simply nothing happens.

```kotlin
// don't: surf-punish waiting on surf-transaction
val tx = rabbit.rpc<TransactionService>()
tx.refund(playerId)

// do: announce what happened and let whoever cares react
rabbit.publish(PunishmentRevokedEvent(playerId))
```
