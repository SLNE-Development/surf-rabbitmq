package dev.slne.surf.eventbus.platform

import java.io.InputStream
import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * The platform this process runs in: Paper, Velocity or a standalone JVM.
 *
 * There were two of these — `RabbitMQInstance`, an interface in the api module, and
 * `RedisInstance`, an abstract class in core that also built a Netty event loop. A platform had
 * to implement both, and Paper and Velocity implemented only one, so Redis was unreachable
 * there. One SPI, and the runtime objects move to where behaviour belongs.
 */
interface EventBusInstance {
    val dataPath: Path

    fun getResourceAsStream(name: String): InputStream? = javaClass.getResourceAsStream(name)

    /** The plugin name to attribute a caller to, for per-plugin configuration and logging. */
    fun tryExtractPluginName(clazz: Class<*>): String

    /**
     * No `requiredService` here, for the same reason [StandaloneLifecycleHook] dropped it:
     * resolving one eagerly in a companion means the class cannot initialise at all without a
     * registration, and [orNull] exists precisely because absence is a legitimate state. A
     * standalone process that builds a `SurfRabbitApi` directly registers no platform, and
     * asking for one must not be an error there.
     */
    companion object {
        /**
         * The registered platform.
         *
         * @throws ServiceConfigurationError if this process registered none — use [orNull] where
         *   absence is expected.
         */
        val INSTANCE: EventBusInstance
            get() =
                instance ?: throw ServiceConfigurationError(
                    "Service ${EventBusInstance::class.java.name} not available",
                )

        /** The registered platform, or `null` when this process registered none. */
        fun orNull(): EventBusInstance? = instance
    }
}

private val instance: EventBusInstance? by lazy {
    ServiceLoader
        .load(EventBusInstance::class.java, EventBusInstance::class.java.classLoader)
        .firstOrNull()
}
