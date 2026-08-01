package dev.slne.surf.eventbus.rabbitmq.api.rpc

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import kotlin.reflect.KClass

/**
 * Dispatches RPC calls and hosts RPC service implementations.
 *
 * Capability follows from which methods a process calls: [createService] makes it a client of
 * [Service], [registerService] makes it a host. A single process can be both for the same or
 * different services over one connection.
 */
@InternalRabbitMQ
interface RabbitRpcService {
    suspend fun <T> call(call: RabbitRpcCall): T

    fun <Service : Any> serviceDescriptorOf(kClass: KClass<Service>): RabbitRpcServiceDescriptor<Service>

    /**
     * Creates a client proxy for [serviceKClass], addressed at [target].
     *
     * [target] overrides the target named in `@RpcService(service = ...)`; omit it to use that
     * default. Proxies are cached per `(serviceKClass, target)` pair, so calling this in a loop
     * body with an [RabbitTarget.InstanceTarget] does not construct a new proxy each time.
     */
    fun <Service : Any> createService(serviceKClass: KClass<Service>, target: RabbitTarget?): Service

    fun <Service : Any> registerService(serviceKClass: KClass<Service>, serviceInstance: Service)
    fun <Service : Any> unregisterService(serviceKClass: KClass<Service>)

    /**
     * Whether any `@RpcService` implementation is currently registered.
     *
     * The connection uses this at connect time to decide whether this process must consume
     * its service queue at all: [registerService] never touches the connection directly, so
     * without this the connection would have no way to know an RPC host exists.
     */
    fun hasRegisteredServices(): Boolean
}
