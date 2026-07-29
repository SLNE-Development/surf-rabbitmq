package dev.slne.surf.rabbitmq.rpc

import com.google.auto.service.AutoService
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcServiceFactory

@AutoService(RabbitRpcServiceFactory::class)
class RabbitRpcServiceFactoryImpl : RabbitRpcServiceFactory {
    override fun createRpcService(api: SurfRabbitApi): RabbitRpcService {
        return RabbitRpcServiceImpl(api)
    }
}
