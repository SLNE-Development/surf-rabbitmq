package dev.slne.surf.eventbus.rabbitmq.rpc

import com.google.auto.service.AutoService
import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RabbitRpcService
import dev.slne.surf.eventbus.rabbitmq.rpc.RabbitRpcServiceFactory

@AutoService(RabbitRpcServiceFactory::class)
class RabbitRpcServiceFactoryImpl : RabbitRpcServiceFactory {
    override fun createRpcService(api: SurfRabbitApi): RabbitRpcService {
        return RabbitRpcServiceImpl(api)
    }
}
