# Developing surf-eventbus

Everything you need to build, test and run the bus locally.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25 | The toolchain targets class-file major 69. CI uses Temurin 25. |
| Docker | any recent | Required for the integration suite. Without it those tests **skip**. |
| Gradle | — | Use the wrapper (`./gradlew`); it pins 9.6.1. |

## Building

```bash
./gradlew build          # compile + test + ABI check
./gradlew check          # everything CI runs
./gradlew assemble       # jars only, no tests
```

## Running the tests

```bash
./gradlew check                        # the full suite, including integration tests
./gradlew check -PskipIntegration      # skip everything that needs Docker
./gradlew check -PrequireIntegration   # fail (don't skip) if Docker is missing — what CI does
./gradlew lincheckTest                 # the concurrency tests, excluded from `test` by default
```

### About `-PrequireIntegration`

Integration tests are tagged `integration` and guarded by `DockerAvailableCondition`. Without a
Docker daemon they report *"integration test skipped, NOT verified"* and the build still goes
green. That is a reasonable default on a laptop and a dangerous one on CI, so
`.github/workflows/ci.yml` passes `-PrequireIntegration`, which turns the skip into a failure.

Pass at most one of `-PskipIntegration` / `-PrequireIntegration`; the build rejects both together.

The integration suite starts its own throwaway containers via Testcontainers. It does **not** use
`docker-compose.yml` and does not need the brokers below to be running.

## Formatting

```bash
./gradlew ktlintCheck                   # part of `check`, so CI runs it
./gradlew ktlintFormat                  # fix what can be fixed automatically
./gradlew ktlintGenerateBaseline        # re-record the accepted findings
```

Each module keeps a baseline at `<module>/config/ktlint/baseline.xml` holding the findings that
predate the linter. The gate is therefore only about *new* code: a linter that arrives with two
thousand findings is a linter everyone learns to scroll past. Do not regenerate the baseline to
silence something you just wrote — fix it, or if the rule is wrong, disable the rule.

There is no Detekt. `1.23.8` embeds Kotlin 1.9's compiler, whose `JvmTarget` stops at 22, and it
reads that target from the JVM it runs on — the Gradle daemon, because it invokes its CLI
in-process instead of forking. Against this project's JDK 25 toolchain every task fails with a
bare `IllegalArgumentException: 25` before opening a file, and there is no fork to point
elsewhere. Add it when a release supports JDK 25.

## Running brokers locally

For driving a real application against the bus by hand:

```bash
docker compose up -d       # rabbitmq:4-management on 5672/15672, redis:7 on 6379
docker compose down -v     # tear down, including the rabbit volume
```

| Service | Address | Credentials |
|---|---|---|
| RabbitMQ | `localhost:5672` | `surf` / `surf`, vhost `/` |
| RabbitMQ management UI | http://localhost:15672 | `surf` / `surf` |
| Redis | `localhost:6379` | password `surf` |

These credentials are deliberately trivial. Do not expose the compose stack beyond localhost.

## Pointing the bus at them

Configuration resolves per field as `env > plugin yaml > global yaml > built-in default`
(`EventBusConfigResolver`). The quickest local route is the environment:

```bash
export SURF_EVENTBUS_RABBITMQ_HOST=localhost
export SURF_EVENTBUS_RABBITMQ_PORT=5672
export SURF_EVENTBUS_RABBITMQ_USERNAME=surf
export SURF_EVENTBUS_RABBITMQ_PASSWORD=surf
export SURF_EVENTBUS_RABBITMQ_VHOST=/

export SURF_EVENTBUS_REDIS_HOST=localhost
export SURF_EVENTBUS_REDIS_PORT=6379
export SURF_EVENTBUS_REDIS_PASSWORD=surf
```

The yaml layers are `eventbus.yml` (global, next to the platform plugin's data folder) and
`eventbus-plugin.yml` (per consuming plugin, in that plugin's own folder). Both are generated
full of sentinels on first run and override nothing until edited.

Two chunking flags additionally honour JVM system properties as a last resort before the built-in
default — `surf.rabbitmq.outgoingRequestChunkingEnabled` and
`surf.rabbitmq.outgoingResponseChunkingEnabled`. They are the only fields with this extra layer.

## Module layout

| Module | Contains |
|---|---|
| `surf-eventbus-api` | Public surface: `SurfEventBus`, annotations, config, codecs, ABI dump |
| `surf-eventbus-core` | Implementations: dispatchers, RabbitMQ and Redis transports, retry, audit |
| `surf-eventbus-ksp` | The `@RpcService` / `@QueryService` descriptor generator |
| `surf-eventbus-platform` | Paper, Velocity and standalone bootstrap |

## The ABI check

`surf-eventbus-api` has a frozen ABI dump at `surf-eventbus-api/api/surf-eventbus-api.api`. Any
change to the public surface fails `check` until the dump is regenerated:

```bash
./gradlew :surf-eventbus-api:checkKotlinAbi    # what `check` runs
./gradlew :surf-eventbus-api:updateKotlinAbi   # regenerate, then review the diff before committing
```

Review that diff. It is the only automated guard against accidentally publishing an
implementation type.

## Benchmarks

```bash
./gradlew :surf-eventbus-core:jmh
```
