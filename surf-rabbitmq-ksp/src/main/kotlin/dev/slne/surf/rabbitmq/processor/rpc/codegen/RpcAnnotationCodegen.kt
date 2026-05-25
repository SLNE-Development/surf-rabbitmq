package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import dev.slne.surf.rabbitmq.processor.ClassNames
import dev.slne.surf.rabbitmq.processor.MemberNames
import dev.slne.surf.rabbitmq.processor.Names
import dev.slne.surf.rabbitmq.processor.Types

fun FileSpec.Builder.optInInternalRabbitApi() = apply {
    addAnnotation(
        AnnotationSpec.builder(ClassNames.kotlinOptIn)
            .addMember("%T::class", ClassNames.internalRabbitMqApi)
            .build()
    )
}

fun TypeSpec.Builder.addInternalDeprecation() = apply {
    addAnnotation(
        AnnotationSpec.builder(Deprecated::class)
            .addMember(
                "message = %S, level = %T.HIDDEN",
                "This synthesized declaration should not be used directly",
                DeprecationLevel::class
            )
            .build()
    )
}

fun FileSpec.Builder.suppressInternalDeprecation() = apply {
    addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "DEPRECATION_ERROR").build())
}

fun List<KSAnnotation>.toAnnotationListCode(): CodeBlock {
    if (isEmpty()) {
        return CodeBlock.of("%M()", MemberNames.emptyList)
    }

    return CodeBlock.builder()
        .add("%M(\n", MemberNames.listOf)
        .withIndent {
            for (annotation in this@toAnnotationListCode) {
                add("%L,\n", annotation.toConstructorCode())
            }
        }
        .add(")")
        .build()
}

fun List<KSAnnotation>.toSerializerMapCode(): CodeBlock {
    val entries = mapNotNull { annotation -> annotation.toSerializerMapEntryCode() }
    if (entries.isEmpty()) {
        return CodeBlock.of("%M()", MemberNames.emptyMap)
    }

    return CodeBlock.builder()
        .add("%M(\n", MemberNames.mapOf)
        .withIndent {
            entries.forEach { entry ->
                add("%L,\n", entry)
            }
        }
        .add(")")
        .build()
}


fun KSAnnotation.isSerializableAnnotation(): Boolean {
    val type = annotationType.resolve()
    return type.declaration.qualifiedName?.asString() == Names.KOTLINX_SERIALIZABLE_FQ
}

private fun KSAnnotation.toConstructorCode(): CodeBlock {
    val annotationType = annotationType.resolve().toTypeName()

    return CodeBlock.builder()
        .add("%T(", annotationType)
        .apply {
            arguments.forEachIndexed { index, argument ->
                if (index > 0) add(", ")

                val name = argument.name?.asString()
                if (name != null) {
                    add("%N = ", name)
                }

                add("%L", argument.value.toAnnotationValueCode())
            }
        }
        .add(")")
        .build()
}

private fun KSAnnotation.toSerializerMapEntryCode(): CodeBlock? {
    val serializerType = arguments
        .find { it.name?.asString() == "with" }
        ?.value as? KSType
        ?: return null

    val serializerDeclaration = serializerType.declaration as? KSClassDeclaration
        ?: error(
            "Expected serializer class declaration for @Serializable.with, " +
                    "but got ${serializerType.declaration.qualifiedName?.asString()}"
        )

    val serializerClassName = serializerDeclaration.toClassName()

    return CodeBlock.of(
        "%T::class to %L",
        serializerClassName,
        serializerDeclaration.createSerializerInstanceCode(),
    )
}

private fun KSClassDeclaration.createSerializerInstanceCode(): CodeBlock {
    return when (classKind) {
        ClassKind.OBJECT -> CodeBlock.of("%T", toClassName())
        ClassKind.CLASS -> {
            val constructor = primaryConstructor
                ?: error("Serializer ${qualifiedName?.asString()} must have a primary constructor")

            if (constructor.parameters.isNotEmpty()) {
                error("Serializer ${qualifiedName?.asString()} must have a no-arg primary constructor")
            }

            CodeBlock.of("%T()", toClassName())
        }

        else -> error("Cannot create serializer instance for class kind ${classKind.name}")
    }
}

private fun Any?.toAnnotationValueCode(): CodeBlock {
    return when (this) {
        is String -> CodeBlock.of("%S", this)
        is Char -> CodeBlock.of("%S.single()", toString())
        is Boolean,
        is Byte,
        is Short,
        is Int,
        is Long,
        is Float,
        is Double -> CodeBlock.of("%L", this)

        is KSType -> CodeBlock.of("%T::class", toTypeName())
        is KSAnnotation -> toConstructorCode()

        is KSClassDeclaration -> {
            val parent = parentDeclaration as? KSClassDeclaration
                ?: error("Enum entry has no parent enum: ${qualifiedName?.asString()}")

            CodeBlock.of("%T.%N", parent.toClassName(), simpleName.asString())
        }

        is Array<*> -> toArrayCode()

        null -> error("Annotation values cannot be null")
        else -> error("Unsupported annotation argument value: $this (${this::class})")
    }
}

private fun Array<*>.toArrayCode(): CodeBlock {
    if (isEmpty()) {
        return CodeBlock.of("%M()", MemberNames.emptyArray)
    }

    return CodeBlock.builder()
        .add("%M(", MemberNames.arrayOf)
        .apply {
            this@toArrayCode.forEachIndexed { index, value ->
                if (index > 0) add(", ")
                add("%L", value.toAnnotationValueCode())
            }
        }
        .add(")")
        .build()
}