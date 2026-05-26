package dev.slne.surf.rabbitmq.test.paper

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import dev.slne.surf.rabbitmq.test.RabbitMqTestCommonInstance

@AutoService(RabbitMqTestCommonInstance::class)
class RabbitMqTestPaperInstance : RabbitMqTestCommonInstance() {
    override suspend fun onLoad() {
        super.onLoad()

        api = ClientRabbitMQApi.create("surf-rabbitmq-test", plugin.dataPath)
        api.freezeAndConnect()
    }

    override suspend fun onDisable() {
        super.onDisable()
        api.disconnect()
    }

    companion object {
        fun get() = RabbitMqTestCommonInstance.instance as RabbitMqTestPaperInstance
    }
}

val rabbitMqApi get() = RabbitMqTestPaperInstance.get().api as ClientRabbitMQApi