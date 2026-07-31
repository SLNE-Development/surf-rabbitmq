package dev.slne.surf.eventbus.rabbitmq.api.internal

import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import java.nio.file.Path

@InternalRabbitMQ
interface RabbitMQInstance {

    val dataPath: Path

    @InternalRabbitMQ
    companion object {
        val instance = requiredService<RabbitMQInstance>()
    }
}