package dev.slne.surf.eventbus.redis.config

import dev.slne.surf.eventbus.config.EventBusConfigFiles
import dev.slne.surf.eventbus.config.RedisSettings
import dev.slne.surf.eventbus.config.resolveEventBusConfig
import dev.slne.surf.eventbus.platform.EventBusInstance

/**
 * `env > plugin yaml > global yaml > default`, resolved once per process.
 *
 * The plugin layer used to be wired here as a literal `null` — four layers in the KDoc, three
 * in the code. Both yaml layers are read now, and both are optional: a standalone process with
 * no platform gets the environment and the built-in defaults, which is what it should get
 * rather than a `ServiceConfigurationError` at class-init.
 */
val redisConfig: RedisSettings by lazy {
    val dataPath = EventBusInstance.orNull()?.dataPath

    if (dataPath == null) {
        resolveEventBusConfig().redis
    } else {
        resolveEventBusConfig(
            global = EventBusConfigFiles.global(dataPath),
            plugin = EventBusConfigFiles.plugin(dataPath),
        ).redis
    }
}
