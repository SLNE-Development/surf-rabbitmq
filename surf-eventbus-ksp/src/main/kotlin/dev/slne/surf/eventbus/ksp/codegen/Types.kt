package dev.slne.surf.eventbus.ksp.codegen

import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy

object Types {
    /** `Array<Any?>` */
    val anyNullableArray = ClassNames.kotlinArray.parameterizedBy(ANY.copy(nullable = true))
}
