package dev.slne.surf.rabbitmq.common.rpc.serialization

import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcType
import dev.slne.surf.rabbitmq.api.rpc.type.RabbitRpcTypeKrpc
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.jvm.jvmErasure

fun SerializersModule.buildContextual(type: RabbitRpcType): KSerializer<Any?> {
    return type.annotations
        .filterIsInstance<Serializable>()
        .lastOrNull()
        ?.let { serializable ->
            @Suppress("UNCHECKED_CAST")
            (type as? RabbitRpcTypeKrpc)
                ?.serializers
                ?.get(serializable.with) as? KSerializer<Any?>
        }
        ?: buildContextual(type.kType)
}

private fun SerializersModule.buildContextual(type: KType): KSerializer<Any?> {
    return buildContextualRecursive(type) ?: serializer(type)
}

@OptIn(ExperimentalSerializationApi::class)
private fun SerializersModule.buildContextualRecursive(type: KType): KSerializer<Any?>? {
    val contextual = getContextual(
        kClass = type.jvmErasure,
        typeArgumentsSerializers = type.arguments.mapIndexed { index, projection ->
            val typeArg = projection.type
                ?: error("Unexpected star projection type at index $index in type arguments list of '$type'")
            buildContextualRecursive(typeArg) ?: serializer(typeArg)
        }
    )

    @Suppress("UNCHECKED_CAST")
    return if (type.isMarkedNullable) {
        contextual?.nullable
    } else {
        contextual
    } as? KSerializer<Any?>?
}