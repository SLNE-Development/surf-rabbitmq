package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.surfapi.core.api.util.requiredService

@InternalRabbitMQ
interface StandaloneLifecycleHook {
    fun onInit()
    suspend fun beforeConnect()
    suspend fun afterDisconnect()

    companion object {
        val instance = requiredService<StandaloneLifecycleHook>()
    }
}