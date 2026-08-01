package dev.slne.surf.eventbus.platform.paper

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap

@Suppress("UnstableApiUsage", "unused")
class PaperBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        PaperEventBusInstance.get().dataPath = context.dataDirectory
    }
}