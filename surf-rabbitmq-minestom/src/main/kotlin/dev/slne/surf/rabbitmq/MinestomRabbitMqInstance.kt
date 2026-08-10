package dev.slne.surf.rabbitmq

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.internal.RabbitMQInstance
import dev.slne.surf.rabbitmq.common.RabbitMQCommonInstance
import java.nio.file.Path

@AutoService(RabbitMQInstance::class)
class MinestomRabbitMqInstance: RabbitMQCommonInstance() {
    override val dataPath: Path get() = RabbitMqMinestomEntrypoint.dataPath
}