package dev.slne.surf.eventbus.platform.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import dev.slne.surf.eventbus.platform.EventBusPlatformInstance
import org.bukkit.plugin.java.JavaPlugin

class PaperMain : SuspendingJavaPlugin() {
    override suspend fun onLoadAsync() {
        EventBusPlatformInstance.get().onLoad()
    }

    override suspend fun onEnableAsync() {
        EventBusPlatformInstance.get().onEnable()
    }

    override suspend fun onDisableAsync() {
        EventBusPlatformInstance.get().onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)