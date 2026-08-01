package dev.slne.surf.eventbus.platform.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.ProxyServer
import dev.slne.surf.eventbus.platform.EventBusPlatformInstance
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

lateinit var plugin: VelocityMain
    private set

class VelocityMain @Inject constructor(val proxy: ProxyServer, @param:DataDirectory val path: Path) {
    init {
        plugin = this

        runBlocking {
            EventBusPlatformInstance.get().onLoad()
        }
    }

    @Subscribe
    suspend fun onEnable(event: ProxyInitializeEvent) {
        EventBusPlatformInstance.get().onEnable()
    }

    @Subscribe(priority = -1000)
    suspend fun onDisable(event: ProxyShutdownEvent) {
        EventBusPlatformInstance.get().onDisable()
    }
}