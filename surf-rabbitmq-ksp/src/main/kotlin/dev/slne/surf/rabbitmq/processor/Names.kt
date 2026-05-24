package dev.slne.surf.rabbitmq.processor

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object Names {
    const val RPC_SERVICE_ANNOTATION_FQ = "dev.slne.surf.rabbitmq.common.rpc.RpcService"
    const val RPC_SERVICE_ANNOTATION = "RpcService"

    const val RPC_SERVICE_DESCRIPTOR_FQ = "dev.slne.surf.rabbitmq.api.rpc.descriptor.RabbitRpcServiceDescriptor"

    const val KOTLINX_SERIALIZABLE_FQ = "kotlinx.serialization.Serializable"
}

object ClassNames {
    val rpcServiceDescriptor = ClassName("dev.slne.surf.rabbitmq.api.rpc.descriptor", "RabbitRpcServiceDescriptor")
    val rpcInvoker = ClassName("dev.slne.surf.rabbitmq.api.rpc.invoker", "RabbitRpcInvoker")
    val rpcCallable = ClassName("dev.slne.surf.rabbitmq.api.rpc.callable", "RabbitRpcCallable")
    val rpcCallableDefault = ClassName("dev.slne.surf.rabbitmq.api.rpc.callable", "RabbitRpcCallableDefault")
    val rpcTypeKrpc = ClassName("dev.slne.surf.rabbitmq.api.rpc.type", "RabbitRpcTypeKrpc")
    val rpcTypeDefault = ClassName("dev.slne.surf.rabbitmq.api.rpc.type", "RabbitRpcTypeDefault")
    val rpcParameterDefault = ClassName("dev.slne.surf.rabbitmq.api.rpc.type", "RabbitRpcParameterDefault")
    val rpcRabbitCall = ClassName("dev.slne.surf.rabbitmq.api.rpc", "RabbitRpcCall")

    val rabbitMqApi = ClassName("dev.slne.surf.rabbitmq.api", "RabbitMQApi")
    val internalRabbitMqApi = ClassName("dev.slne.surf.rabbitmq.api", "InternalRabbitMQ")

    val kotlinKClass = ClassName("kotlin.reflect", "KClass")
    val kotlinxKSerializer = ClassName("kotlinx.serialization", "KSerializer")
    val kotlinArray = ClassName("kotlin", "Array")

    val kotlinOptIn = ClassName("kotlin", "OptIn")
}

object MemberNames {
    val kotlinTypeOf = MemberName("kotlin.reflect", "typeOf")

    val listOf = MemberName("kotlin.collections", "listOf")
    val emptyList = MemberName("kotlin.collections", "emptyList")

    val arrayOf = MemberName("kotlin", "arrayOf")
    val emptyArray = MemberName("kotlin", "emptyArray")

    val emptyMap = MemberName("kotlin.collections", "emptyMap")
    val mapOf = MemberName("kotlin.collections", "mapOf")
}

object Types {
    /**
     * KSerializer<Any?>
     */
    val kSerializerAnyNullable = ClassNames.kotlinxKSerializer.parameterizedBy(ANY.copy(nullable = true))

    /**
     * KClass<KSerializer<Any?>>
     */
    val serializerKClassType = ClassNames.kotlinKClass.parameterizedBy(kSerializerAnyNullable)

    /**
     * Array<Any?>
     */
    val anyNullableArray = ClassNames.kotlinArray.parameterizedBy(ANY.copy(nullable = true))
}