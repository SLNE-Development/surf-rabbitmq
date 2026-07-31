# surf-redis inventory (as absorbed into surf-eventbus-redis)

Read-only inventory of `S:\Workspaces\surf-redis` at the commit it was copied from. surf-redis
itself was never modified — every file below was copied out and renamed.

## Commit

```
b7a087d Merge pull request #75 from SLNE-Development/feat/env-vars
```

`gradle.properties` reports `version=1.10.1`, matching the plan's assumption.

## Source count

- 81 non-test `.kt` files under `surf-redis-api`, `surf-redis-core`, `surf-redis-paper`,
  `surf-redis-standalone`, `surf-redis-velocity`.
- Seven test classes, all under `surf-redis-api` and `surf-redis-core`:

```
surf-redis-api/src/test/kotlin/dev/slne/surf/redis/codec/AbstractCodecTest.kt
surf-redis-api/src/test/kotlin/dev/slne/surf/redis/codec/RedisUtf8StringTest.kt
surf-redis-core/src/test/kotlin/dev/slne/surf/redis/event/CustomEventPacketCodecTest.kt
surf-redis-core/src/test/kotlin/dev/slne/surf/redis/event/EventCodecRegistryLincheckTest.kt
surf-redis-core/src/test/kotlin/dev/slne/surf/redis/event/EventCodecRegistryTest.kt
surf-redis-core/src/test/kotlin/dev/slne/surf/redis/sync/BinarySyncValueCodecTest.kt
surf-redis-core/src/test/kotlin/dev/slne/surf/redis/sync/BulkMutationScriptTest.kt
```

## Dependency versions

```
redisson = "4.6.1"
netty = "4.2.15.Final" # https://github.com/redisson/redisson/blob/master/pom.xml#L195
```

surf-rabbitmq (now surf-eventbus) pins Netty `4.2.16.Final`. Task 7 converges both transports on
`4.2.16.Final` — Rabbit's pin wins, since Redisson 4.6.1 already runs against a Netty version one
patch newer than the one it tests against upstream.

## `@Blocking` usage

Four call sites in `surf-redis-api/src/main/kotlin/dev/slne/surf/redis/RedisApi.kt`, at lines
365, 420, 446, 486 (pre-copy line numbers).

## Platform modules

`surf-redis-paper` and `surf-redis-velocity` are **not** copied. Their only project-specific
files were the two Velocity reflection proxies (`JavaPluginLoaderProxy`,
`SerializedPluginDescriptionProxy`), already absorbed into `surf-eventbus-common` in Task 4. The
job of providing a running instance on each platform is picked up by the existing
`surf-eventbus-platform-paper`/`-velocity` modules in Plan 4.
