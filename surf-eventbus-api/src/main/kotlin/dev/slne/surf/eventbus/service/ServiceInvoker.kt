package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/** Invokes one generated method handle of a contract implementation. */
@InternalEventBusApi
fun interface ServiceInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
