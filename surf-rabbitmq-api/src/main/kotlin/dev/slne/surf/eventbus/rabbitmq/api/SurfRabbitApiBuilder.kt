package dev.slne.surf.eventbus.rabbitmq.api

import dev.slne.surf.eventbus.rabbitmq.api.identity.RabbitIdentity
import dev.slne.surf.eventbus.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.api.internal.StandaloneLifecycleHook
import dev.slne.surf.eventbus.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.eventbus.rabbitmq.api.internal.config.GlobalRabbitMQConfig
import dev.slne.surf.eventbus.rabbitmq.api.internal.config.PluginRabbitMQConfig
import dev.slne.surf.eventbus.rabbitmq.api.internal.config.resolveRabbitMQConfig
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import java.nio.file.Path
import java.util.ServiceLoader

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
    private var configOverride: CommonRabbitMQConfig? = null
    private var configFileName: String = "rabbitmq.yml"
    private var instanceName: String? = null

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

    /** Supplies a config directly, bypassing file loading. Intended for tests. */
    fun config(config: CommonRabbitMQConfig): SurfRabbitApiBuilder = apply {
        configOverride = config
    }

    fun build(): SurfRabbitApi {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }

        val platform = platformInstanceOrNull()
        val config = configOverride ?: resolveConfig(platform)

        return SurfRabbitApi(
            identity = RabbitIdentity.create(serviceName, instanceName),
            config = config,
            cbor = SurfRabbitApi.createCbor(serializers),
            standalone = platform == null && configOverride == null
        )
    }

    /**
     * Resolution stays four-layered, exactly as before the client/server merge:
     * `env > plugin YAML > global YAML > default`.
     *
     * On Paper/Velocity the global YAML lives in the platform plugin's data folder
     * ([RabbitMQInstance.dataPath], file `config.yml`) and the per-plugin overrides in this
     * builder's [dataPath] — the former `ClientRabbitMQApi.create` behaviour. Standalone
     * there is no plugin layer and the global YAML lives in [dataPath] — the former
     * `ServerRabbitMQApi.create` behaviour, including the [StandaloneLifecycleHook] init.
     */
    private fun resolveConfig(platform: RabbitMQInstance?): CommonRabbitMQConfig {
        return if (platform != null) {
            resolveRabbitMQConfig(
                GlobalRabbitMQConfig.getOrLoad(platform.dataPath, "config.yml"),
                PluginRabbitMQConfig.create(dataPath)
            )
        } else {
            StandaloneLifecycleHook.onInit(dataPath)
            resolveRabbitMQConfig(GlobalRabbitMQConfig.getOrLoad(dataPath, configFileName))
        }
    }

    private fun platformInstanceOrNull(): RabbitMQInstance? =
        ServiceLoader.load(RabbitMQInstance::class.java).firstOrNull()
}
