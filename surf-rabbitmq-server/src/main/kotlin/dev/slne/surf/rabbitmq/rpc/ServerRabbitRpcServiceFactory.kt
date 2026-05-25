package dev.slne.surf.rabbitmq.rpc

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.ServerRabbitMQApi
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory

@AutoService(RabbitRpcServiceFactory::class)
class ServerRabbitRpcServiceFactory : RabbitRpcServiceFactory {
    override fun createRpcService(api: RabbitMQApi): RabbitRpcService {
        return ServerRabbitRpcServiceImpl(api as ServerRabbitMQApi)
    }
}