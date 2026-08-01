package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueParameter
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.ksp.TypeParameterResolver

/** One contract interface, whichever annotation marked it. */
data class ServiceModel(
    val kind: ContractKind,
    val declaration: KSClassDeclaration,
    val containingFile: KSFile,
    val simpleName: String,
    val fqName: String,
    val packageName: String,
    val serviceClassName: ClassName,
    val descriptorClassName: ClassName,
    val clientImplClassName: ClassName,
    val functions: List<ServiceFunctionModel>,
    /** `@RpcService(service = ...)`, empty for a query. */
    val defaultService: String,
    /** `@QueryService(timeoutMillis = ...)`, 0 for RPC. */
    val timeoutMillis: Long,
)

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
