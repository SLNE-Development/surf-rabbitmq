package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import dev.slne.surf.rabbitmq.processor.ClassNames
import dev.slne.surf.rabbitmq.processor.MemberNames
import dev.slne.surf.rabbitmq.processor.Names

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
    addAnnotation(
        AnnotationSpec.builder(Suppress::class).addMember("%S", "DEPRECATION_ERROR").build()
    )
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
    val resolvedAnnotationType = annotationType.resolve()
    val annotationType = resolvedAnnotationType.toTypeName()
    val parameterTypes = (resolvedAnnotationType.declaration as? KSClassDeclaration)
        ?.primaryConstructor
        ?.parameters
        ?.associate { parameter -> parameter.name?.asString() to parameter.type.resolve() }
        .orEmpty()

    return CodeBlock.builder()
        .add("%T(", annotationType)
        .apply {
            arguments.forEachIndexed { index, argument ->
                if (index > 0) add(", ")

                val name = argument.name?.asString()
                if (name != null) {
                    add("%N = ", name)
                }

                add("%L", argument.value.toAnnotationValueCode(parameterTypes[name]))
            }
        }
        .add(")")
        .build()
}

fun KSAnnotation.toTypeUseAnnotationSpec(): AnnotationSpec {
    val resolvedAnnotationType = annotationType.resolve()
    val annotationDeclaration = resolvedAnnotationType.declaration as? KSClassDeclaration
        ?: error("Annotation type is not a class declaration: $resolvedAnnotationType")
    val parameterTypes = annotationDeclaration
        .primaryConstructor
        ?.parameters
        ?.associate { parameter -> parameter.name?.asString() to parameter.type.resolve() }
        .orEmpty()

    return AnnotationSpec.builder(annotationDeclaration.toClassName())
        .apply {
            arguments.forEach { argument ->
                val name = argument.name?.asString()
                val value = argument.value.toAnnotationValueCode(parameterTypes[name])
                if (name == null) {
                    addMember("%L", value)
                } else {
                    addMember("%N = %L", name, value)
                }
            }
        }
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

private fun Any?.toAnnotationValueCode(expectedType: KSType? = null): CodeBlock {
    return when (this) {
        is String -> CodeBlock.of("%S", this)
        is Char -> toCharacterLiteralCode()
        is Boolean,
        is Byte,
        is Short,
        is Int -> CodeBlock.of("%L", this)

        is Long -> CodeBlock.of("%LL", this)
        is Float -> toFloatLiteralCode()
        is Double -> toDoubleLiteralCode()

        is KSType -> CodeBlock.of("%T::class", toTypeName())
        is KSAnnotation -> toConstructorCode()

        is KSClassDeclaration -> {
            val parent = parentDeclaration as? KSClassDeclaration
                ?: error("Enum entry has no parent enum: ${qualifiedName?.asString()}")

            CodeBlock.of("%T.%N", parent.toClassName(), simpleName.asString())
        }

        is Array<*> -> toArrayCode(expectedType)
        is List<*> -> toArrayCode(expectedType)
        is KSName -> toQualifiedNameCode()

        null -> error("Annotation values cannot be null")
        else -> error("Unsupported annotation argument value: $this (${this::class})")
    }
}

private fun Char.toCharacterLiteralCode(): CodeBlock {
    val literal = when (this) {
        '\b' -> "'\\b'"
        '\t' -> "'\\t'"
        '\n' -> "'\\n'"
        '\u000C' -> "'\\f'"
        '\r' -> "'\\r'"
        '\'' -> "'\\\''"
        '\\' -> "'\\\\'"
        else -> if (isISOControl() || isSurrogate()) {
            "'\\u${code.toString(16).padStart(4, '0')}'"
        } else {
            "'$this'"
        }
    }
    return CodeBlock.of("%L", literal)
}

private fun Float.toFloatLiteralCode(): CodeBlock = when {
    isNaN() -> CodeBlock.of("%T.NaN", Float::class)
    this == Float.POSITIVE_INFINITY -> CodeBlock.of("%T.POSITIVE_INFINITY", Float::class)
    this == Float.NEGATIVE_INFINITY -> CodeBlock.of("%T.NEGATIVE_INFINITY", Float::class)
    else -> CodeBlock.of("%Lf", this)
}

private fun Double.toDoubleLiteralCode(): CodeBlock = when {
    isNaN() -> CodeBlock.of("%T.NaN", Double::class)
    this == Double.POSITIVE_INFINITY -> CodeBlock.of("%T.POSITIVE_INFINITY", Double::class)
    this == Double.NEGATIVE_INFINITY -> CodeBlock.of("%T.NEGATIVE_INFINITY", Double::class)
    else -> CodeBlock.of("%L", this)
}

private fun KSName.toQualifiedNameCode(): CodeBlock {
    val parts = asString().split('.')
    return CodeBlock.builder().apply {
        parts.forEachIndexed { index, part ->
            if (index > 0) add(".")
            add("%N", part)
        }
    }.build()
}

private fun Array<*>.toArrayCode(expectedType: KSType?): CodeBlock {
    return asList().toArrayCode(expectedType)
}

private fun List<*>.toArrayCode(expectedType: KSType?): CodeBlock {
    val factoryName = when (expectedType?.declaration?.qualifiedName?.asString()) {
        "kotlin.BooleanArray" -> "booleanArrayOf"
        "kotlin.ByteArray" -> "byteArrayOf"
        "kotlin.CharArray" -> "charArrayOf"
        "kotlin.DoubleArray" -> "doubleArrayOf"
        "kotlin.FloatArray" -> "floatArrayOf"
        "kotlin.IntArray" -> "intArrayOf"
        "kotlin.LongArray" -> "longArrayOf"
        "kotlin.ShortArray" -> "shortArrayOf"
        else -> "arrayOf"
    }
    val factory = MemberName("kotlin", factoryName)

    return CodeBlock.builder()
        .add("%M(", factory)
        .apply {
            this@toArrayCode.forEachIndexed { index, value ->
                if (index > 0) add(", ")
                add("%L", value.toAnnotationValueCode())
            }
        }
        .add(")")
        .build()
}
