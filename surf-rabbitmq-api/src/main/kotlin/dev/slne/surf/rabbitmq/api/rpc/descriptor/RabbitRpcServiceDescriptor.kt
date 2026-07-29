package dev.slne.surf.rabbitmq.api.rpc.descriptor

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import dev.slne.surf.rabbitmq.api.target.RabbitTarget

@InternalRabbitMQ
interface RabbitRpcServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, RabbitRpcCallable<Service>>

    fun getCallable(name: String): RabbitRpcCallable<Service>?
    fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service
}
