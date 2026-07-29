package dev.slne.surf.rabbitmq.api.rpc.descriptor

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable

@InternalRabbitMQ
interface RabbitRpcServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, RabbitRpcCallable<Service>>

    fun getCallable(name: String): RabbitRpcCallable<Service>?
    fun createInstance(serviceId: Long, api: RabbitMQApi): Service
}