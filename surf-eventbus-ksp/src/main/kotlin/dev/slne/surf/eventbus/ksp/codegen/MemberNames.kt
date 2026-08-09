package dev.slne.surf.eventbus.ksp.codegen

import com.squareup.kotlinpoet.MemberName

object MemberNames {
    val kotlinTypeOf = MemberName("kotlin.reflect", "typeOf")

    val listOf = MemberName("kotlin.collections", "listOf")
    val emptyList = MemberName("kotlin.collections", "emptyList")

    val arrayOf = MemberName("kotlin", "arrayOf")
    val emptyArray = MemberName("kotlin", "emptyArray")

    val mapOf = MemberName("kotlin.collections", "mapOf")
    val emptyMap = MemberName("kotlin.collections", "emptyMap")
}
