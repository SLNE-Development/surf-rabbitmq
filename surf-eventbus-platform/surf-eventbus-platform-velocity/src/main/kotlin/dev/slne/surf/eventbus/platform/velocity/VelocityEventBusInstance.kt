package dev.slne.surf.eventbus.platform.velocity

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.platform.EventBusInstance
import dev.slne.surf.eventbus.platform.EventBusPlatformInstance
import java.nio.file.Path

/**
 * Velocity, serving both transports.
 *
 * Like Paper, this platform shipped a RabbitMQ instance and no Redis one, so `publish` and
 * `query` could not work on a proxy at all.
 */
@AutoService(EventBusInstance::class)
class VelocityEventBusInstance : EventBusPlatformInstance() {
    override val dataPath: Path get() = plugin.path

    /**
     * The plugin that owns [clazz], from the proxy's plugin container registry.
     *
     * Velocity has no `getProvidingPlugin`, so the owner is found by matching class loaders.
     * Falls back to this plugin's own name rather than throwing: attribution is used for
     * per-plugin config and log lines, and neither is worth failing a call over.
     */
    override fun tryExtractPluginName(clazz: Class<*>): String {
        val classLoader = clazz.classLoader ?: return FALLBACK_NAME

        return plugin.proxy.pluginManager.plugins
            .firstOrNull { it.instance.getOrNull()?.javaClass?.classLoader === classLoader }
            ?.description
            ?.id
            ?: FALLBACK_NAME
    }

    private fun <T> java.util.Optional<T>.getOrNull(): T? = orElse(null)

    companion object {
        private const val FALLBACK_NAME = "surf-eventbus-velocity"

        fun get(): VelocityEventBusInstance = EventBusInstance.instance as VelocityEventBusInstance
    }
}
