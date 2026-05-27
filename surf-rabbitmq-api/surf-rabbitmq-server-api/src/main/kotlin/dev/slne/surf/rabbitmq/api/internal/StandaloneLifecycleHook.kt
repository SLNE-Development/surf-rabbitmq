package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import java.nio.file.Path

@InternalRabbitMQ
interface StandaloneLifecycleHook {
    fun onInit(dataPath: Path)
    suspend fun beforeConnect()
    suspend fun afterDisconnect()

    @InternalRabbitMQ
    companion object : StandaloneLifecycleHook by instance {
        val INSTANCE get() = instance
    }
}

private val instance = requiredService<StandaloneLifecycleHook>()
