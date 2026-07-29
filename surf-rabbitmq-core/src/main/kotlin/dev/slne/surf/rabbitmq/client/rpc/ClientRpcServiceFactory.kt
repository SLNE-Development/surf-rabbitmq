package dev.slne.surf.rabbitmq.client.rpc

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.ClientRabbitMQApi
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory

@AutoService(RabbitRpcServiceFactory::class)
class ClientRpcServiceFactory : RabbitRpcServiceFactory {
    override fun createRpcService(api: RabbitMQApi): RabbitRpcService {
        return ClientRpcServiceImpl(api as ClientRabbitMQApi)
    }
}