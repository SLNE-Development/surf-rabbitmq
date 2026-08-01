package dev.slne.surf.eventbus.ksp.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toTypeName
import dev.slne.surf.eventbus.ksp.model.ServiceFunctionModel
import dev.slne.surf.eventbus.ksp.model.ServiceModel

/** Generates `<Name>ClientImpl`: the proxy that turns a call into a query frame. */
class QueryClientCodegen {

    private companion object {
        const val INSTANCE_ID_NAME = "__instanceId__"
        const val JSON_NAME = "__json__"
        const val TRANSPORT_NAME = "__transport__"
        const val DESCRIPTOR_NAME = "__descriptor__"
        const val SERIALIZER_CACHE_NAME = "__serializerCache__"
        const val EMPTY_ARRAY_NAME = "__emptyArray__"
    }

    fun createClientType(service: ServiceModel): TypeSpec {
        return TypeSpec.classBuilder(service.clientImplClassName)
            .addOriginatingKSFile(service.containingFile)
            .addModifiers(KModifier.INTERNAL)
            .addSuperinterface(service.serviceClassName)
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter(INSTANCE_ID_NAME, String::class)
                    .addParameter(JSON_NAME, ClassNames.json)
                    .addParameter(TRANSPORT_NAME, ClassNames.queryTransport)
                    .addParameter(DESCRIPTOR_NAME, service.descriptorClassName)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(INSTANCE_ID_NAME, String::class)
                    .initializer(INSTANCE_ID_NAME).addModifiers(KModifier.PRIVATE).build()
            )
            .addProperty(
                PropertySpec.builder(JSON_NAME, ClassNames.json)
                    .initializer(JSON_NAME).addModifiers(KModifier.PRIVATE).build()
            )
            .addProperty(
                PropertySpec.builder(TRANSPORT_NAME, ClassNames.queryTransport)
                    .initializer(TRANSPORT_NAME).addModifiers(KModifier.PRIVATE).build()
            )
            .addProperty(
                PropertySpec.builder(DESCRIPTOR_NAME, service.descriptorClassName)
                    .initializer(DESCRIPTOR_NAME).addModifiers(KModifier.PRIVATE).build()
            )
            .addProperty(
                PropertySpec.builder(SERIALIZER_CACHE_NAME, ClassNames.serviceSerializerCache)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("%T()", ClassNames.serviceSerializerCache)
                    .build()
            )
            .addProperty(
                PropertySpec.builder(EMPTY_ARRAY_NAME, Types.anyNullableArray)
                    .addModifiers(KModifier.PRIVATE)
                    .initializer("%M()", MemberNames.emptyArray)
                    .build()
            )
            .apply {
                service.functions.forEach { function -> addFunction(createQueryFunction(function)) }
            }
            .build()
    }

    private fun createQueryFunction(function: ServiceFunctionModel): FunSpec {
        val returnTypeName = function.returnType.toTypeName(function.typeParameterResolver)

        return FunSpec.builder(function.name)
            .addAnnotation(
                AnnotationSpec.builder(Suppress::class).addMember("%S", "UNCHECKED_CAST").build()
            )
            .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
            .addParameters(
                function.parameters.map { parameter ->
                    ParameterSpec.builder(
                        parameter.name?.asString() ?: error("parameter name is null"),
                        parameter.type.toTypeName(function.typeParameterResolver),
                    ).build()
                }
            )
            .returns(returnTypeName)
            .addCode(
                CodeBlock.builder()
                    .addStatement("val callable = %L.getCallable(%S)!!", DESCRIPTOR_NAME, function.name)
                    .addStatement(
                        "val arguments: %T = %L",
                        Types.anyNullableArray,
                        createArgumentsArray(function),
                    )
                    .addStatement(
                        "val argsSerializer = %L.getParameterSerializer(callable, %L.serializersModule)",
                        SERIALIZER_CACHE_NAME, JSON_NAME
                    )
                    .addStatement(
                        "val payload = %L.encodeToString(argsSerializer, arguments)",
                        JSON_NAME
                    )
                    .addStatement(
                        "val frame = %T(\n" +
                                "    contract = %L.fqName,\n" +
                                "    callable = %S,\n" +
                                "    correlationId = java.util.UUID.randomUUID().toString(),\n" +
                                "    originInstanceId = %L,\n" +
                                "    payload = payload\n" +
                                ")",
                        ClassNames.queryFrame, DESCRIPTOR_NAME, function.name, INSTANCE_ID_NAME
                    )
                    .addStatement(
                        "val resultPayload = %L.ask(frame, %L.timeoutMillis) ?: return null",
                        TRANSPORT_NAME, DESCRIPTOR_NAME
                    )
                    .addStatement(
                        "val returnSerializer = %L.getReturnTypeSerializer(callable, %L.serializersModule)",
                        SERIALIZER_CACHE_NAME, JSON_NAME
                    )
                    .addStatement(
                        "return %L.decodeFromString(returnSerializer, resultPayload) as %T",
                        JSON_NAME, returnTypeName
                    )
                    .build()
            )
            .build()
    }

    private fun createArgumentsArray(function: ServiceFunctionModel): CodeBlock {
        if (function.parameters.isEmpty()) {
            return CodeBlock.of("%L", EMPTY_ARRAY_NAME)
        }

        return CodeBlock.builder()
            .add("%M(", MemberNames.arrayOf)
            .apply {
                function.parameters.forEachIndexed { index, parameter ->
                    if (index > 0) add(", ")
                    add("%N", parameter.name?.asString() ?: error("parameter name is null"))
                }
            }
            .add(")")
            .build()
    }
}
