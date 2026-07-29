package dev.slne.surf.rabbitmq.api.rpc

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import kotlin.reflect.KClass

@InternalRabbitMQ
interface ClientRabbitRpcService : RabbitRpcService {

    fun <Service : Any> createService(serviceKClass: KClass<Service>): Service
}