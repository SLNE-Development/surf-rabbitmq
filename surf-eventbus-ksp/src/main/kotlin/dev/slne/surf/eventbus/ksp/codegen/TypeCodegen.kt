package dev.slne.surf.eventbus.ksp.codegen

import com.google.devtools.ksp.symbol.KSTypeReference
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver
import com.squareup.kotlinpoet.ksp.toAnnotationSpec
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.withIndent

/**
 * Emits the `ServiceType` a descriptor carries for one type reference.
 *
 * A type that names `@Serializable(with = ...)` needs the serializer instance at runtime, which
 * is the only reason the Krpc variant exists; everything else gets the plain one.
 */
fun KSTypeReference.createServiceTypeConstructor(typeParameterResolver: TypeParameterResolver): CodeBlock {
    val typeUseAnnotations = annotations.toList()
    val serializableAnnotations = typeUseAnnotations.filter { it.isSerializableAnnotation() }
    val serviceTypeClass = if (serializableAnnotations.isNotEmpty()) {
        ClassNames.serviceTypeKrpc
    } else {
        ClassNames.serviceTypeDefault
    }

    return CodeBlock.builder()
        .add("%T(\n", serviceTypeClass)
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
