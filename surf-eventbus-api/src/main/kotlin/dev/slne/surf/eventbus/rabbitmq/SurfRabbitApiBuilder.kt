package dev.slne.surf.eventbus.rabbitmq

import dev.slne.surf.api.core.environment.EnvironmentVariables
import dev.slne.surf.eventbus.config.EventBusConfigFiles
import dev.slne.surf.eventbus.config.RabbitMQSettings
import dev.slne.surf.eventbus.config.resolveEventBusSettings
import dev.slne.surf.eventbus.platform.EventBusInstance
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.identity.RabbitIdentity
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path

/**
 * Builds a [SurfRabbitApi].
 *
 * ```kotlin
 * val rabbit = SurfRabbitApi.builder("surf-factions", dataPath)
 *     .serializers(FactionsSerializers)
 *     .build()
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
class SurfRabbitApiBuilder internal constructor(
    private val serviceName: String,
    private val dataPath: Path
) {
    private var serializers: SerializersModule = EmptySerializersModule()
    private var configOverride: RabbitMQSettings? = null
    private var configFileName: String = EventBusConfigFiles.GLOBAL_FILE_NAME
    private var instanceName: String? = null
    private var standaloneHook: StandaloneLifecycleHook? = null
    private var environment: EnvironmentVariables = EnvironmentVariables.system

    /** Additional serializers for packet, event and RPC payload types. */
    fun serializers(module: SerializersModule): SurfRabbitApiBuilder = apply {
        serializers = module
    }

    /**
     * Gives this process a stable instance id instead of a random suffix.
     *
     * Required for processes that must be addressable via `InstanceTarget` — a caller can
     * only target an instance whose id it can predict. Take the name from the process's own
     * configuration (e.g. the Paper server name). A duplicated name fails loudly at connect,
     * because the instance queues are exclusive.
     */
    fun instanceName(name: String): SurfRabbitApiBuilder = apply {
        instanceName = name
    }

    /** Overrides the global config file name (standalone mode only). */
    fun configFileName(name: String): SurfRabbitApiBuilder = apply {
        configFileName = name
    }

    /** Supplies settings directly, bypassing file loading. Intended for tests. */
    fun config(config: RabbitMQSettings): SurfRabbitApiBuilder = apply {
        configOverride = config
    }

    /**
     * Resolves the environment layer from [variables] instead of the real process environment.
     *
     * The top layer of `env > plugin yaml > global yaml > default` is otherwise only reachable
     * by actually setting environment variables, which a test cannot do portably and a caller
     * embedding the bus may not want to do at all.
     */
    fun environment(variables: Map<String, String>): SurfRabbitApiBuilder = apply {
        environment = EnvironmentVariables.from(variables)
    }

    /**
     * Uses [hook] instead of looking one up via `ServiceLoader`.
     *
     * Without it a unit test had to register a double in `META-INF/services` by hand, because
     * running `@AutoService`'s processor next to this project's own on one `kspTest` task hits
     * a KSP2 analysis-API lifetime bug. Injection removes the reason to run either.
     */
    fun standaloneHook(hook: StandaloneLifecycleHook): SurfRabbitApiBuilder = apply {
        standaloneHook = hook
    }

    fun build(): SurfRabbitApi {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val platform = EventBusInstance.orNull()
        val standalone = platform == null && configOverride == null
        val hook = standaloneHook
            ?: StandaloneLifecycleHook.discover()
            ?: StandaloneLifecycleHook.NoOp
        val config = configOverride ?: resolveConfig(platform, hook)

        return SurfRabbitApi(
            identity = RabbitIdentity.create(serviceName, instanceName),
            config = config,
            cbor = SurfRabbitApi.createCbor(serializers),
            standalone = standalone,
            standaloneHook = hook,
        )
    }

    /**
     * Resolution stays four-layered: `env > plugin yaml > global yaml > default`.
     *
     * On Paper/Velocity the global file lives in the platform plugin's data folder
     * ([EventBusInstance.dataPath]) and the per-plugin overrides in this builder's [dataPath].
     * Standalone there is no plugin layer and the global file lives in [dataPath], along with
     * the [StandaloneLifecycleHook] init.
     */
    private fun resolveConfig(
        platform: EventBusInstance?,
        hook: StandaloneLifecycleHook,
    ): RabbitMQSettings {
        // Standalone brings its data folder up before anything reads out of it.
        if (platform == null) hook.onInit(dataPath)

        return resolveEventBusSettings(
            pluginDataPath = dataPath,
            platformDataPath = platform?.dataPath,
            environment = environment,
            globalFileName = configFileName,
        ).rabbitmq
    }
}
