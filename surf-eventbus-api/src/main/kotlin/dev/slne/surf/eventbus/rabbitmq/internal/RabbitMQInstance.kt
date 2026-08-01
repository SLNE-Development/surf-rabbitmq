package dev.slne.surf.eventbus.rabbitmq.internal

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.InternalEventBusApi
import java.nio.file.Path

@InternalEventBusApi
interface RabbitMQInstance {

    val dataPath: Path

    @InternalEventBusApi
    companion object {
        val instance = requiredService<RabbitMQInstance>()
    }
}