package dev.slne.surf.rabbitmq.paper

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap

@Suppress("UnstableApiUsage", "unused")
class PaperBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        PaperRabbitMqInstance.get().dataPath = context.dataDirectory
    }
}