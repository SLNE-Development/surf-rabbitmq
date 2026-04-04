package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
interface StandaloneLifecycleHook {
    fun onInit()
    suspend fun beforeConnect()
    suspend fun afterDisconnect()

    companion object : StandaloneLifecycleHook by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<StandaloneLifecycleHook>()
