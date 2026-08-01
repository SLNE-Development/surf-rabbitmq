package dev.slne.surf.eventbus.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.RabbitMQCommonInstance
import java.nio.file.Path

@AutoService(RabbitMQInstance::class)
class StandaloneRabbitMqInstance : RabbitMQCommonInstance() {
    override lateinit var dataPath: Path

    companion object {
        fun get(): StandaloneRabbitMqInstance = RabbitMQInstance.instance as StandaloneRabbitMqInstance
    }
}