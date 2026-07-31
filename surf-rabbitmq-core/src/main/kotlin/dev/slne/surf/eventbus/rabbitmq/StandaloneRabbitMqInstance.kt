package dev.slne.surf.eventbus.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.common.RabbitMQCommonInstance
import java.nio.file.Path

@AutoService(RabbitMQInstance::class)
class StandaloneRabbitMqInstance : RabbitMQCommonInstance() {
    override lateinit var dataPath: Path

    companion object {
        fun get(): StandaloneRabbitMqInstance = RabbitMQInstance.instance as StandaloneRabbitMqInstance
    }
}