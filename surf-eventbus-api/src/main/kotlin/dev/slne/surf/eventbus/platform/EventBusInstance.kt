package dev.slne.surf.eventbus.platform

import dev.slne.surf.api.core.util.requiredService
import java.io.InputStream
import java.nio.file.Path

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

    companion object {
        val instance: EventBusInstance by lazy { requiredService<EventBusInstance>() }

        /**
         * The platform, or `null` when none is registered.
         *
         * Standalone processes that build a [dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi]
         * directly have no platform, and asking for one must not be an error there.
         */
        fun orNull(): EventBusInstance? =
            java.util.ServiceLoader.load(EventBusInstance::class.java).firstOrNull()
    }
}
