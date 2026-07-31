package dev.slne.surf.eventbus.rabbitmq.test.paper

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.test.RabbitMqTestCommonInstance

@AutoService(RabbitMqTestCommonInstance::class)
class RabbitMqTestPaperInstance : RabbitMqTestCommonInstance() {
    override suspend fun onLoad() {
        super.onLoad()

        api = SurfRabbitApi.builder("surf-rabbitmq-test-paper", plugin.dataPath).build()
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

val rabbitMqApi get() = RabbitMqTestPaperInstance.get().api
