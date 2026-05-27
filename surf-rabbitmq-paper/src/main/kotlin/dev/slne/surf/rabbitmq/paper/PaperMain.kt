package dev.slne.surf.rabbitmq.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import dev.slne.surf.rabbitmq.common.RabbitMQCommonInstance
import org.bukkit.plugin.java.JavaPlugin

class PaperMain : SuspendingJavaPlugin() {
    override suspend fun onLoadAsync() {
        RabbitMQCommonInstance.get().onLoad()
    }

    override suspend fun onEnableAsync() {
        RabbitMQCommonInstance.get().onEnable()
    }

    override suspend fun onDisableAsync() {
        RabbitMQCommonInstance.get().onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)