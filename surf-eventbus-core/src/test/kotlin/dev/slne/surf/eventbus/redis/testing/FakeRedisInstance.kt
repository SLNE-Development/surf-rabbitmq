package dev.slne.surf.eventbus.redis.testing

import dev.slne.surf.eventbus.redis.RedisInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `RedisInstance` service a real platform (Paper, Velocity, standalone) provides at runtime.
 *
 * `surf-eventbus-platform-standalone` already has one, but it depends on this module, so tests
 * here need their own — a project dependency the other way round would be circular.
 *
 * Registered by hand in `META-INF/services` rather than via `@AutoService`: running that
 * processor alongside `surfEventbusKsp` on the same `kspTest` task triggers a KSP2 analysis-API
 * lifetime bug (`KaInvalidLifetimeOwnerAccessException`) in this Kotlin version.
 */
class FakeRedisInstance : RedisInstance() {
    override val dataPath: Path = Files.createTempDirectory("surf-eventbus-redis-test")

    override fun tryExtractPluginNameFromClass(clazz: Class<*>): String = "surf-eventbus-redis-test"
}
