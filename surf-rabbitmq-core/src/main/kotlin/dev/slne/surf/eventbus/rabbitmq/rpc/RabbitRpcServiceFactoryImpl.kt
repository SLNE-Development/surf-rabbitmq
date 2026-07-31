package dev.slne.surf.eventbus.rabbitmq.rpc

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.eventbus.rabbitmq.api.rpc.RabbitRpcServiceFactory

@AutoService(RabbitRpcServiceFactory::class)
class RabbitRpcServiceFactoryImpl : RabbitRpcServiceFactory {
    override fun createRpcService(api: SurfRabbitApi): RabbitRpcService {
        return RabbitRpcServiceImpl(api)
    }
}
