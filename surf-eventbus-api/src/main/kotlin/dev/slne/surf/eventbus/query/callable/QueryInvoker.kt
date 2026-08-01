package dev.slne.surf.eventbus.query.callable

import dev.slne.surf.eventbus.InternalEventBusApi

/** Invokes a generated method handle for one `@QueryService` callable. */
@InternalEventBusApi
fun interface QueryInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
