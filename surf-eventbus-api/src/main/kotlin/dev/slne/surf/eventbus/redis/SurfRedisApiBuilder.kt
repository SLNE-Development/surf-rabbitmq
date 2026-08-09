package dev.slne.surf.eventbus.redis

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.config.EventBusConfigFiles
import dev.slne.surf.eventbus.config.resolveEventBusSettings
import dev.slne.surf.eventbus.config.settings.RedisSettings
import dev.slne.surf.eventbus.credentials.provider.RedisCredentialsProvider
import dev.slne.surf.eventbus.platform.EventBusInstance
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

/**
 * Builds a [SurfRedisApi].
 *
 * The counterpart of `SurfRabbitApiBuilder`, and the reason it exists: the `create(...)`
 * overloads answer "which address" seven different ways and "which of the four config layers
 * apply" not at all. A caller that wants its own `eventbus-plugin.yml` respected had no way to
 * say so, because none of the overloads takes a data folder.
 *
 * ```kotlin
 * val redis = SurfRedisApi.builder("surf-factions", dataPath)
 *     .serializers(FactionsSerializers)
 *     .build()
 * ```
 */
class SurfRedisApiBuilder internal constructor(
    private val pluginName: String,
    private val dataPath: Path?,
) {
    private var serializers: SerializersModule = EmptySerializersModule()
    private var settingsOverride: RedisSettings? = null
    private var configFileName: String = EventBusConfigFiles.GLOBAL_FILE_NAME
    private var environment: EnvironmentVariables = EnvironmentVariables.system

    /** Additional serializers for sync structure, cache and event payload types. */
    fun serializers(module: SerializersModule): SurfRedisApiBuilder =
        apply {
            serializers = module
        }

    /** Overrides the global config file name (standalone mode only). */
    fun configFileName(name: String): SurfRedisApiBuilder =
        apply {
            configFileName = name
        }

    /** Supplies settings directly, bypassing file loading. Intended for tests. */
    fun settings(settings: RedisSettings): SurfRedisApiBuilder =
        apply {
            settingsOverride = settings
        }

    /**
     * Resolves the environment layer from [variables] instead of the real process environment.
     *
     * The top layer of `env > plugin yaml > global yaml > default` is otherwise only reachable
     * by actually setting environment variables, which a test cannot do portably and a caller
     * embedding the bus may not want to do at all.
     */
    fun environment(variables: Map<String, String>): SurfRedisApiBuilder =
        apply {
            environment = EnvironmentVariables.from(variables)
        }

    fun build(): SurfRedisApi {
        require(pluginName.isNotBlank()) { "pluginName must not be blank" }

        return SurfRedisApi.create(settingsOverride ?: resolveSettings(), pluginName, serializers)
    }

    /**
     * Resolution stays four-layered: `env > plugin yaml > global yaml > default`.
     *
     * Goes through [RedisCredentialsProvider] rather than [resolveEventBusSettings] directly
     * when there is no data folder, so the answer is the one the rest of the Redis half already
     * caches per consumer instead of a second, differently-cached copy.
     */
    private fun resolveSettings(): RedisSettings {
        val platform = EventBusInstance.orNull()
        if (dataPath == null) return RedisCredentialsProvider.settings(null)

        return resolveEventBusSettings(
            pluginDataPath = dataPath,
            platformDataPath = platform?.dataPath,
            environment = environment,
            globalFileName = configFileName,
        ).redis
    }
}
