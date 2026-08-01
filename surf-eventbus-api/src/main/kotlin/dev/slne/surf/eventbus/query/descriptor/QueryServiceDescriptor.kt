package dev.slne.surf.eventbus.query.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.service.ServiceDescriptor
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
interface QueryServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    val timeoutMillis: Long

    fun createInstance(instanceId: String, json: Json, transport: QueryTransport): Service
}
