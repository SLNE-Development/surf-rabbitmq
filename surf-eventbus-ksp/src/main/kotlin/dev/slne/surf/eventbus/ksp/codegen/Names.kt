package dev.slne.surf.eventbus.ksp.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object Names {
    const val KOTLINX_SERIALIZABLE_FQ = "kotlinx.serialization.Serializable"
}

object ClassNames {
    // The one contract model, shared by both backends.
    val serviceInvoker = ClassName("dev.slne.surf.eventbus.service", "ServiceInvoker")
    val serviceCallable = ClassName("dev.slne.surf.eventbus.service", "ServiceCallable")
    val serviceCallableDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceCallableDefault")
    val serviceParameterDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceParameterDefault")
    val serviceTypeDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceTypeDefault")
    val serviceTypeKrpc = ClassName("dev.slne.surf.eventbus.service", "ServiceTypeKrpc")
    val serviceSerializerCache =
        ClassName("dev.slne.surf.eventbus.service.serialization", "ServiceSerializerCache")

    val internalEventBusApi = ClassName("dev.slne.surf.eventbus", "InternalEventBusApi")

    // Query-only.
    val queryServiceDescriptor =
        ClassName("dev.slne.surf.eventbus.query.descriptor", "QueryServiceDescriptor")
    val queryTransport = ClassName("dev.slne.surf.eventbus.transport", "QueryTransport")
    val queryFrame = ClassName("dev.slne.surf.eventbus.transport", "QueryFrame")
    val json = ClassName("kotlinx.serialization.json", "Json")

    // RPC-only.
    val rpcServiceDescriptor =
        ClassName("dev.slne.surf.eventbus.rabbitmq.rpc.descriptor", "RpcServiceDescriptor")
    val rabbitMqApi = ClassName("dev.slne.surf.eventbus.rabbitmq", "SurfRabbitApi")
    val rabbitTarget = ClassName("dev.slne.surf.eventbus.rabbitmq.target", "RabbitTarget")
    val rpcRabbitCall = ClassName("dev.slne.surf.eventbus.rabbitmq.rpc", "RabbitRpcCall")

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

    val mapOf = MemberName("kotlin.collections", "mapOf")
    val emptyMap = MemberName("kotlin.collections", "emptyMap")
}

object Types {
    /** `Array<Any?>` */
    val anyNullableArray = ClassNames.kotlinArray.parameterizedBy(ANY.copy(nullable = true))
}
