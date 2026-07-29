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
