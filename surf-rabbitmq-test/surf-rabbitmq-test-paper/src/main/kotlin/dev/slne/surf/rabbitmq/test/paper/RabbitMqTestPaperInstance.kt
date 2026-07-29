package dev.slne.surf.rabbitmq.test.paper

import com.google.auto.service.AutoService
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.event.RabbitSubscribe
import dev.slne.surf.rabbitmq.api.event.SubscriptionMode
import dev.slne.surf.rabbitmq.test.RabbitMqTestCommonInstance
import dev.slne.surf.rabbitmq.test.event.TestBroadcastEvent

@AutoService(RabbitMqTestCommonInstance::class)
class RabbitMqTestPaperInstance : RabbitMqTestCommonInstance() {
    override suspend fun onLoad() {
        super.onLoad()

        api = SurfRabbitApi.builder("surf-rabbitmq-test-paper", plugin.dataPath).build()

        // Every Paper server invalidates its own state, so BROADCAST is right here.
        api.registerListener(TestBroadcastListener)
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

object TestBroadcastListener {
    @RabbitSubscribe(mode = SubscriptionMode.BROADCAST)
    suspend fun onBroadcast(event: TestBroadcastEvent) {
        logger().atInfo().log("Received broadcast: %s", event.message)
    }
}

val rabbitMqApi get() = RabbitMqTestPaperInstance.get().api
