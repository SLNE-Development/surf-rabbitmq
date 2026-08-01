package dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.rpc.callable.RabbitRpcCallable
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget

@InternalEventBusApi
interface RabbitRpcServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, RabbitRpcCallable<Service>>

    /**
     * The service declared by `@RpcService(service = ...)`, or an empty string if none was
     * given, in which case `rpc(...)` requires an explicit service.
     */
    val defaultService: String

    fun getCallable(name: String): RabbitRpcCallable<Service>?
    fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service
}
