package dev.slne.surf.eventbus.query.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.query.callable.QueryCallable
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.json.Json

/**
 * Generated once per `@QueryService` interface.
 *
 * [fqName] is also the channel name (`RedisChannels.query(fqName)`) and the value
 * `QueryFrame.contract` carries — the two must agree, which is exactly what generating both
 * from the same descriptor guarantees.
 */
@InternalEventBusApi
interface QueryServiceDescriptor<Service : Any> {
    val fqName: String
    val timeoutMillis: Long
    val callables: Map<String, QueryCallable<Service>>

    fun getCallable(name: String): QueryCallable<Service>?
    fun createInstance(instanceId: String, json: Json, transport: QueryTransport): Service
}
