package dev.slne.surf.rabbitmq.api.rpc

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass

@InternalRabbitMQ
interface RabbitRpcService {
    suspend fun <T> call(call: RabbitRpcCall): T

    fun <Service : Any> serviceDescriptorOf(kClass: KClass<Service>): RabbitRpcServiceDescriptor<Service>
}