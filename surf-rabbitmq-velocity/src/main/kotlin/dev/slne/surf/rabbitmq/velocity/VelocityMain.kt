package dev.slne.surf.rabbitmq.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.proxy.ProxyServer
import dev.slne.surf.rabbitmq.common.RabbitMQInstance
import kotlinx.coroutines.runBlocking

lateinit var plugin: VelocityMain
    private set

class VelocityMain @Inject constructor(val proxy: ProxyServer) {
    init {
        plugin = this

        runBlocking {
            RabbitMQInstance.get().onEnable()
        }
    }

    @Subscribe
    suspend fun onEnable(event: ProxyInitializeEvent) {
        RabbitMQInstance.get().onEnable()
    }

    @Subscribe(priority = -1000)
    suspend fun onDisable(event: ProxyShutdownEvent) {
        RabbitMQInstance.get().onDisable()
    }
}