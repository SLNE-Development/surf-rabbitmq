package dev.slne.surf.rabbitmq.common.rpc.serialization

import dev.slne.surf.rabbitmq.api.rpc.callable.RabbitRpcCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap

class RpcSerializerCache {
    private val parameterCache = ConcurrentHashMap<RabbitRpcCallable<*>, CallableParametersSerializer>()
    private val returnTypeCache = ConcurrentHashMap<RabbitRpcCallable<*>, KSerializer<Any?>>()

    fun getParameterSerializer(
        callable: RabbitRpcCallable<*>,
        module: SerializersModule
    ): CallableParametersSerializer {
        return parameterCache.computeIfAbsent(callable) {
            CallableParametersSerializer(callable, module)
        }
    }

    fun getReturnTypeSerializer(callable: RabbitRpcCallable<*>, module: SerializersModule): KSerializer<Any?> {
        return returnTypeCache.computeIfAbsent(callable) {
            module.buildContextual(callable.returnType)
        }
    }
}