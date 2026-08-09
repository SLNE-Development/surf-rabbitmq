package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ksp.TypeParameterResolver

/** One abstract method of a contract interface. */
data class ServiceFunctionModel(
    val declaration: KSFunctionDeclaration,
    val name: String,
    val invokerName: String,
    val invokerFunctionName: String,
    val returnType: KSTypeReference,
    val parameters: List<KSValueParameter>,
    val typeParameterResolver: TypeParameterResolver,
    /** Always `false` for a query: the rules reject `@FireAndForget` there. */
    val fireAndForget: Boolean,
)
