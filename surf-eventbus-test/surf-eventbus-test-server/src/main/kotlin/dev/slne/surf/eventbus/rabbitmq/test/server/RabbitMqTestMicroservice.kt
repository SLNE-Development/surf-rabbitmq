package dev.slne.surf.eventbus.rabbitmq.test.server

import com.google.auto.service.AutoService
import dev.slne.surf.microservice.api.microservice.Microservice
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.test.rpc.RabbitMqTestRpcService
import dev.slne.surf.eventbus.rabbitmq.test.server.rpc.RabbitMqTestRpcServerImpl
import kotlin.io.path.Path

@AutoService(Microservice::class)
class RabbitMqTestMicroservice : Microservice() {
    override val dataPath = Path("config")

    private val rabbitApi = SurfRabbitApi.builder("surf-rabbitmq-test", dataPath).build()

    override suspend fun onBootstrap(args: List<String>) {
        rabbitApi.registerService<RabbitMqTestRpcService>(RabbitMqTestRpcServerImpl)
        rabbitApi.freezeAndConnect()
    }

    override suspend fun onDisable() {
        rabbitApi.disconnect()
    }
}
