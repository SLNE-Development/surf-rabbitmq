package dev.slne.surf.eventbus.ksp.processor.query

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object Names {
    const val QUERY_SERVICE_ANNOTATION_FQ = "dev.slne.surf.eventbus.query.QueryService"
    const val QUERY_SERVICE_ANNOTATION = "QueryService"

    const val DEFAULT_TIMEOUT_MILLIS = 5_000L
}

object ClassNames {
    val queryServiceDescriptor = ClassName("dev.slne.surf.eventbus.query.descriptor", "QueryServiceDescriptor")
    val queryInvoker = ClassName("dev.slne.surf.eventbus.service", "ServiceInvoker")
    val queryCallable = ClassName("dev.slne.surf.eventbus.service", "ServiceCallable")
    val queryCallableDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceCallableDefault")
    val queryParameter = ClassName("dev.slne.surf.eventbus.service", "ServiceParameter")
    val queryParameterDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceParameterDefault")
    val queryTypeDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceTypeDefault")

    val queryTransport = ClassName("dev.slne.surf.eventbus.transport", "QueryTransport")
    val queryFrame = ClassName("dev.slne.surf.eventbus.transport", "QueryFrame")

    val querySerializerCache =
        ClassName("dev.slne.surf.eventbus.service.serialization", "ServiceSerializerCache")

    val json = ClassName("kotlinx.serialization.json", "Json")

    val kotlinKClass = ClassName("kotlin.reflect", "KClass")
    val kotlinArray = ClassName("kotlin", "Array")

    val internalEventBusApi = ClassName("dev.slne.surf.eventbus", "InternalEventBusApi")
    val optIn = ClassName("kotlin", "OptIn")
}

object MemberNames {
    val kotlinTypeOf = MemberName("kotlin.reflect", "typeOf")

    val mapOf = MemberName("kotlin.collections", "mapOf")
    val emptyMap = MemberName("kotlin.collections", "emptyMap")

    val arrayOf = MemberName("kotlin", "arrayOf")
    val emptyArray = MemberName("kotlin", "emptyArray")

    val emptyList = MemberName("kotlin.collections", "emptyList")
}

object Types {
    /** Array<Any?> */
    val anyNullableArray = ClassNames.kotlinArray.parameterizedBy(ANY.copy(nullable = true))
}
