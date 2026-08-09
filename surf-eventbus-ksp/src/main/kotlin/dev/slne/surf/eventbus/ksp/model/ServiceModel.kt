package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName

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
