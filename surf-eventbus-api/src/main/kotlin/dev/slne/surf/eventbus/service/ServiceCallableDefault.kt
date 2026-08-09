package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

@InternalEventBusApi
class ServiceCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: ServiceType,
    override val invoker: ServiceInvoker<Service>,
    override val parameters: Array<out ServiceParameter>,
    override val fireAndForget: Boolean = false,
) : ServiceCallable<Service>
