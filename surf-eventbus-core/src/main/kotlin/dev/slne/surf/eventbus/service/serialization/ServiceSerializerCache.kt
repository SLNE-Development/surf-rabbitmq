package dev.slne.surf.eventbus.service.serialization

import dev.slne.surf.eventbus.service.ServiceCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap

/** Caches the argument and return-type serializers of one contract callable. */
class ServiceSerializerCache {
    private val parameterCache = ConcurrentHashMap<ServiceCallable<*>, ParametersSerializer>()
    private val returnTypeCache = ConcurrentHashMap<ServiceCallable<*>, KSerializer<Any?>>()

    fun getParameterSerializer(
        callable: ServiceCallable<*>,
        module: SerializersModule
    ): ParametersSerializer =
        parameterCache.computeIfAbsent(callable) { ParametersSerializer(callable, module) }

    fun getReturnTypeSerializer(
        callable: ServiceCallable<*>,
        module: SerializersModule
    ): KSerializer<Any?> =
        returnTypeCache.computeIfAbsent(callable) { module.buildContextual(callable.returnType) }
}
