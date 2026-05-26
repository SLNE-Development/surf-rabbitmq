package dev.slne.surf.rabbitmq.paper

import com.github.shynixn.mccoroutine.folia.SuspendingJavaPlugin
import dev.slne.surf.rabbitmq.common.RabbitMQInstance
import org.bukkit.plugin.java.JavaPlugin

class PaperMain : SuspendingJavaPlugin() {
    override suspend fun onLoadAsync() {
        RabbitMQInstance.get().onLoad()
    }

    override suspend fun onEnableAsync() {
        RabbitMQInstance.get().onEnable()
    }

    override suspend fun onDisableAsync() {
        RabbitMQInstance.get().onDisable()
    }
}

val plugin get() = JavaPlugin.getPlugin(PaperMain::class.java)