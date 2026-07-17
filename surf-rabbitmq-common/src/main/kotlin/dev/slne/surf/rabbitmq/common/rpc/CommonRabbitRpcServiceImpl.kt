package dev.slne.surf.rabbitmq.common.rpc

import dev.slne.surf.rabbitmq.api.RabbitMQApi
import dev.slne.surf.rabbitmq.api.rpc.RabbitRpcService
import dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor
import kotlin.reflect.KClass

abstract class CommonRabbitRpcServiceImpl(private val api: RabbitMQApi) : RabbitRpcService {

    override fun <Service : Any> serviceDescriptorOf(kClass: KClass<Service>): RabbitRpcServiceDescriptor<Service> {
        val descriptor = findServiceDescriptor(kClass) ?: error("Unable to find a service descriptor of the $kClass.")

        if (descriptor !is RabbitRpcServiceDescriptor<*>) {
            error("Located service descriptor is not a RabbitRpcServiceDescriptor: $descriptor but $kClass")
        }

        @Suppress("UNCHECKED_CAST")
        return descriptor as RabbitRpcServiceDescriptor<Service>
    }

    private fun <Service : Any> findServiceDescriptor(kClass: KClass<Service>): Any? {
        return ServiceDescriptorCache.get(kClass.java).takeUnless { it === NO_DESCRIPTOR }
    }

    private object ServiceDescriptorCache : ClassValue<Any>() {
        override fun computeValue(type: Class<*>): Any {
            if (!type.isInterface) return NO_DESCRIPTOR
            val packageName = type.packageName
            val simpleName = type.simpleName
            val descriptorFqName = "$packageName.${simpleName}Descriptor"

            return try {
                val descriptorClass = Class.forName(descriptorFqName, false, type.classLoader)
                val descriptorKClass = descriptorClass.kotlin
                descriptorKClass.objectInstance ?: NO_DESCRIPTOR
            } catch (_: ClassNotFoundException) {
                NO_DESCRIPTOR
            }
        }
    }

    companion object {
        private val NO_DESCRIPTOR = Any()
    }
}
