package dev.slne.surf.rabbitmq.velocity

import com.google.inject.Inject
import com.velocitypowered.api.proxy.ProxyServer

lateinit var plugin: VelocityMain
    private set

class VelocityMain @Inject constructor(val proxy: ProxyServer) {
    init {
        plugin = this
    }
}