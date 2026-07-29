package dev.slne.surf.rabbitmq.api.rpc

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import kotlin.reflect.KClass

@InternalRabbitMQ
interface ServerRabbitRpcService : RabbitRpcService {
    fun <Service : Any> registerService(serviceKClass: KClass<Service>, serviceInstance: Service)
    fun <Service : Any> unregisterService(serviceKClass: KClass<Service>)
}