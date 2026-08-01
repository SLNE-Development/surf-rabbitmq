package dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.service.ServiceDescriptor

@InternalEventBusApi
interface RpcServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    /**
     * The service declared by `@RpcService(service = ...)`, or an empty string if none was
     * given, in which case `rpc(...)` requires an explicit service.
     */
    val defaultService: String

    fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service
}
