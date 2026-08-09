package dev.slne.surf.eventbus.platform.paper

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.platform.EventBusInstance
import dev.slne.surf.eventbus.platform.EventBusPlatformInstance
import org.bukkit.plugin.java.JavaPlugin
import java.nio.file.Path

/**
 * Paper, serving both transports.
 *
 * This platform shipped a RabbitMQ instance and no Redis one, so `publish` and `query` — the
 * two verbs that ride Redis — could not work on a Paper server at all.
 */
@AutoService(EventBusInstance::class)
class PaperEventBusInstance : EventBusPlatformInstance() {
    override lateinit var dataPath: Path

    /**
     * The plugin that owns [clazz], from Paper's own class-loader bookkeeping.
     *
     * Falls back to this plugin's own name rather than throwing: attribution is used for
     * per-plugin config and log lines, and neither is worth failing a call over.
     */
    override fun tryExtractPluginName(clazz: Class<*>): String =
        runCatching { JavaPlugin.getProvidingPlugin(clazz).name }
            .getOrDefault(FALLBACK_NAME)

    companion object {
        private const val FALLBACK_NAME = "surf-eventbus-paper"

        fun get(): PaperEventBusInstance = EventBusInstance.INSTANCE as PaperEventBusInstance
    }
}
