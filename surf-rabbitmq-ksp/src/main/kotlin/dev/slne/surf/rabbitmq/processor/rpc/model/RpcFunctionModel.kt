package dev.slne.surf.rabbitmq.processor.rpc.model

import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ksp.TypeParameterResolver

data class RpcFunctionModel(
    val declaration: KSFunctionDeclaration,
    val name: String,
    val invokerName: String,
    val invokerFunctionName: String,
    val returnType: KSTypeReference,
    val parameters: List<KSValueParameter>,
    val typeParameterResolver: TypeParameterResolver,
)