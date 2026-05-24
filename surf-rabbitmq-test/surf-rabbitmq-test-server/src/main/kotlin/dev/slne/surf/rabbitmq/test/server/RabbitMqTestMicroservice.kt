package dev.slne.surf.rabbitmq.test.server

import com.google.auto.service.AutoService
import dev.slne.surf.microservice.api.microservice.Microservice
import dev.slne.surf.rabbitmq.api.ServerRabbitMQApi
import dev.slne.surf.rabbitmq.test.rpc.RabbitMqTestRpcService
import dev.slne.surf.rabbitmq.test.server.handler.TestRabbitMqHandler
import dev.slne.surf.rabbitmq.test.server.rpc.RabbitMqTestRpcServerImpl
import kotlin.io.path.Path

@AutoService(Microservice::class)
class RabbitMqTestMicroservice : Microservice() {
    override val dataPath = Path("config")
    private val rabbitApi = ServerRabbitMQApi.create("surf-rabbitmq-test", dataPath)

    override suspend fun onBootstrap(args: List<String>) {
        rabbitApi.registerRpcService<RabbitMqTestRpcService>(RabbitMqTestRpcServerImpl)

        rabbitApi.registerRequestHandler(TestRabbitMqHandler)

        rabbitApi.freezeAndConnect()
    }

    override suspend fun onDisable() {
        rabbitApi.disconnect()
    }
}