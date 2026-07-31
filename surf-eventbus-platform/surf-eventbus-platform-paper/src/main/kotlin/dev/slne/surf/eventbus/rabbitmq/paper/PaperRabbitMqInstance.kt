package dev.slne.surf.eventbus.rabbitmq.paper

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.common.RabbitMQCommonInstance
import java.nio.file.Path

@AutoService(RabbitMQInstance::class)
class PaperRabbitMqInstance : RabbitMQCommonInstance() {
    override lateinit var dataPath: Path

    companion object {
        fun get(): PaperRabbitMqInstance = RabbitMQInstance.instance as PaperRabbitMqInstance
    }
}