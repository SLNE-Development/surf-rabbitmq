package dev.slne.surf.eventbus.platform.standalone

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.platform.EventBusInstance
import dev.slne.surf.eventbus.redis.RedisRuntime
import java.nio.file.Path

/**
 * A plain JVM process, serving both transports.
 *
 * Replaces `StandaloneRedisInstance` and `StandaloneRabbitMqInstance` — the latter of which
 * lived in `surf-eventbus-core`, where a standalone class never belonged. Its `@AutoService`
 * registration there meant core's own unit tests found it via `ServiceLoader` and read its
 * never-initialised `dataPath`.
 */
@AutoService(EventBusInstance::class)
class StandaloneEventBusInstance(override val dataPath: Path) : EventBusInstance {

    /** No-arg constructor for `ServiceLoader`; [dataPath] then comes from [configure]. */
    constructor() : this(configuredPath ?: Path.of("."))

    /**
     * A standalone process has no plugins, so every caller is attributed to the process itself.
     */
    override fun tryExtractPluginName(clazz: Class<*>): String = serviceName

    fun start() {
        RedisRuntime.instance.load()
    }

    fun shutdown() {
        RedisRuntime.instance.disable()
    }

    companion object {
        private const val DEFAULT_SERVICE_NAME = "surf-eventbus-standalone"

        @Volatile
        private var configuredPath: Path? = null

        @Volatile
        var serviceName: String = DEFAULT_SERVICE_NAME
            private set

        /** Called before the bus is built, so the `ServiceLoader` instance sees the real path. */
        fun configure(name: String, dataPath: Path) {
            serviceName = name
            configuredPath = dataPath
        }

        fun get(): StandaloneEventBusInstance =
            EventBusInstance.instance as StandaloneEventBusInstance
    }
}
