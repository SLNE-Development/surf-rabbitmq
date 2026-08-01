package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/**
 * Generated once per contract interface, whatever transport carries it.
 *
 * What the two transports do *not* share is how a client instance comes to be — a query needs a
 * channel and a timeout, an RPC call needs a target service. That difference lives in the two
 * sub-interfaces, and nowhere else.
 */
@InternalEventBusApi
interface ServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, ServiceCallable<Service>>

    fun getCallable(name: String): ServiceCallable<Service>?
}
