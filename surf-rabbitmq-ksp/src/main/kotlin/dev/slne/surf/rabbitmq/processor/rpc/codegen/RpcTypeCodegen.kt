package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.withIndent
import dev.slne.surf.rabbitmq.processor.ClassNames
import dev.slne.surf.rabbitmq.processor.MemberNames

fun KSTypeReference.createRabbitRpcTypeConstructor(typeParameterResolver: TypeParameterResolver): CodeBlock {
    val typeUseAnnotations = annotations.toList()
    val serializableAnnotations = typeUseAnnotations.filter { it.isSerializableAnnotation() }
    val rpcTypeClass = if (serializableAnnotations.isNotEmpty()) {
        ClassNames.rpcTypeKrpc
    } else {
        ClassNames.rpcTypeDefault
    }

    return CodeBlock.builder()
        .add("%T(\n", rpcTypeClass)
        .withIndent {
            add("kType = %M<%T>(),\n", MemberNames.kotlinTypeOf, toAnnotatedTypeName(typeParameterResolver))
            add("annotations = %L", typeUseAnnotations.toAnnotationListCode())

            if (serializableAnnotations.isNotEmpty()) {
                add(",\nserializers = %L", serializableAnnotations.toSerializerMapCode())
            }

            add(",\n")
        }
        .add(")")
        .build()
}

private fun KSTypeReference.toAnnotatedTypeName(
    typeParameterResolver: TypeParameterResolver,
): TypeName {
    val baseType = toTypeName(typeParameterResolver)
    val typeUseAnnotations = annotations.map { it.toAnnotationSpec() }.toList()

    if (typeUseAnnotations.isEmpty()) {
        return baseType
    }

    return baseType.copy(
        annotations = baseType.annotations + typeUseAnnotations,
    )
}