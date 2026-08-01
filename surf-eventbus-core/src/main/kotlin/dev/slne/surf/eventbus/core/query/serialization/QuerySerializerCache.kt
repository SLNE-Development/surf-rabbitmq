package dev.slne.surf.eventbus.core.query.serialization

import dev.slne.surf.eventbus.query.callable.QueryCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import java.util.concurrent.ConcurrentHashMap

/** Caches the argument and return-type serializers of a `@QueryService` callable. */
class QuerySerializerCache {
    private val parameterCache = ConcurrentHashMap<QueryCallable<*>, QueryParametersSerializer>()
    private val returnTypeCache = ConcurrentHashMap<QueryCallable<*>, KSerializer<Any?>>()

    fun getParameterSerializer(callable: QueryCallable<*>, module: SerializersModule): QueryParametersSerializer =
        parameterCache.computeIfAbsent(callable) { QueryParametersSerializer(callable, module) }

    fun getReturnTypeSerializer(callable: QueryCallable<*>, module: SerializersModule): KSerializer<Any?> =
        returnTypeCache.computeIfAbsent(callable) { module.serializer(callable.returnType) }
}
