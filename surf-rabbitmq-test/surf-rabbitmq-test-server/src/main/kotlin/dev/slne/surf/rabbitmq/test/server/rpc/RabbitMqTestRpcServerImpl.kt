package dev.slne.surf.rabbitmq.test.server.rpc

import dev.slne.surf.api.core.util.random
import dev.slne.surf.rabbitmq.test.rpc.CustomParameterTypeSerializer
import dev.slne.surf.rabbitmq.test.rpc.CustomReturnTypeSerializer
import dev.slne.surf.rabbitmq.test.rpc.RabbitMqTestRpcService
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

object RabbitMqTestRpcServerImpl: RabbitMqTestRpcService {
    override suspend fun getRandomNumber(): Int {
        delay(1.seconds)
        return random.nextInt()
    }

    override suspend fun doNothing() {
        // do nothing
    }

    override suspend fun customParameterWithCustomReturnType(parameter: @Serializable(with = CustomParameterTypeSerializer::class) String): @Serializable(
        with = CustomReturnTypeSerializer::class
    ) String {
        return "Hello, $parameter!"
    }
}