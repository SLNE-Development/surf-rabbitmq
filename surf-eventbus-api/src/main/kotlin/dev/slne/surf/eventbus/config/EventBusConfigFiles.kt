package dev.slne.surf.eventbus.config

import dev.slne.surf.api.core.config.createSpongeYmlConfig
import dev.slne.surf.api.core.config.surfConfigApi
import dev.slne.surf.eventbus.InternalEventBusApi
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads the two yaml layers.
 *
 * The global loader is keyed by `(path, fileName)` rather than holding one instance: the first
 * load used to win and every later `(path, fileName)` was silently ignored, which forced every
 * `SurfRabbitApi` in one JVM — a Paper server full of plugins — to share whichever file loaded
 * first.
 *
 * There is deliberately no `getConfig()` here. The old `GlobalRabbitMQConfig.getConfig()` was a
 * mutable process-wide singleton that `PluginRabbitMQConfig` consulted once per field, so the
 * value a plugin config resolved to depended on which config had been loaded most recently.
 * Both layers are passed to [resolveEventBusConfig] explicitly instead.
 */
@InternalEventBusApi
object EventBusConfigFiles {
    const val GLOBAL_FILE_NAME = "eventbus.yml"
    const val PLUGIN_FILE_NAME = "eventbus-plugin.yml"

    private val globalCache = ConcurrentHashMap<Pair<Path, String>, EventBusConfig>()

    /** The broker-wide file every service on this host reads. */
    fun global(
        path: Path,
        fileName: String = GLOBAL_FILE_NAME,
    ): EventBusConfig {
        val key = path.toAbsolutePath().normalize() to fileName

        return globalCache.computeIfAbsent(key) {
            surfConfigApi.createSpongeYmlConfig<EventBusConfig>(
                configFolder = path,
                configFileName = fileName,
            )
        }
    }

    /**
     * The per-plugin file that overrides it, field by field.
     */
    fun plugin(
        path: Path,
        fileName: String = PLUGIN_FILE_NAME,
    ): EventBusConfig =
        surfConfigApi.createSpongeYmlConfig<EventBusConfig>(
            configFolder = path,
            configFileName = fileName,
        )
}
