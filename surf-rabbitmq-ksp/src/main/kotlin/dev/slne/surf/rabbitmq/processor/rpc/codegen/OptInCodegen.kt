package dev.slne.surf.rabbitmq.processor.rpc.codegen

import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import dev.slne.surf.rabbitmq.processor.ClassNames

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

