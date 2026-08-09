package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.config.settings.RedisSettings
import dev.slne.surf.eventbus.config.resolveEventBusSettings
import dev.slne.surf.eventbus.platform.EventBusInstance
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The Redis half of the resolved settings, cached per consumer.
 *
 * Deliberately thin: which files feed the four layers is decided once, in
 * [resolveEventBusSettings], and both transports ask it the same question. This file used to
 * answer that question itself and answer it wrongly — it passed the platform plugin's folder as
 * both the global *and* the plugin layer, so the `eventbus-plugin.yml` it opened belonged to no
 * consumer and nothing a plugin wrote could reach Redis.
 *
 * Keyed by the consumer's data folder rather than computed once per process. The plugin layer
 * is per plugin by definition, so a single process-wide value cannot express it: on a Paper
 * server with two plugins, whichever touched Redis first would have fixed the answer for both.
 * The global layer is still shared — `EventBusConfigFiles.global` caches by `(path, fileName)`,
 * so `eventbus.yml` is parsed once however many consumers ask.
 */
private val settingsByPluginPath = ConcurrentHashMap<Path, RedisSettings>()

private val settingsWithoutPlugin: RedisSettings by lazy { resolveRedisSettings(null) }

/**
 * The settings a consumer rooted at [pluginDataPath] connects with.
 *
 * @param pluginDataPath the consumer's own data folder, source of the plugin layer. `null` for
 *   a caller with no folder of its own, which resolves `env > global > default`.
 */
fun redisSettingsFor(pluginDataPath: Path?): RedisSettings {
    if (pluginDataPath == null) return settingsWithoutPlugin

    return settingsByPluginPath.computeIfAbsent(pluginDataPath.toAbsolutePath().normalize()) {
        resolveRedisSettings(it)
    }
}

/**
 * The settings for a caller with no per-plugin folder.
 *
 * The [redisSettingsFor] `null` case under the name the Redis half already used, not a second
 * mechanism — so it cannot drift from the per-plugin answer.
 */
val redisConfig: RedisSettings get() = redisSettingsFor(null)

/**
 * Uncached, so a caller can resolve against an explicit environment.
 *
 * The cache is keyed by path alone; storing a result that also depended on an injected
 * environment would hand the next caller someone else's answer. This is the seam
 * `SurfEventBusBuilder.build(environment)` reaches for, and the one that lets the layering be
 * tested without mutating the real process environment.
 */
fun resolveRedisSettings(
    pluginDataPath: Path?,
    environment: EnvironmentVariables = EnvironmentVariables.system,
): RedisSettings = resolveEventBusSettings(
    pluginDataPath = pluginDataPath,
    platformDataPath = EventBusInstance.orNull()?.dataPath,
    environment = environment,
).redis
