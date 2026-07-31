package dev.slne.surf.eventbus.rabbitmq.velocity

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.eventbus.rabbitmq.common.RabbitMQCommonInstance
import java.nio.file.Path

@AutoService(RabbitMQInstance::class)
class VelocityRabbitMqInstance : RabbitMQCommonInstance() {
    override val dataPath: Path get() = plugin.path
}