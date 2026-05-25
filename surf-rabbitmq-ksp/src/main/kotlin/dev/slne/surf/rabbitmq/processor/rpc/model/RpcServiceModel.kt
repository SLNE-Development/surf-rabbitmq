package dev.slne.surf.rabbitmq.processor.rpc.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName

data class RpcServiceModel(
    val declaration: KSClassDeclaration,
    val containingFile: KSFile,
    val simpleName: String,
    val fqName: String,
    val packageName: String,
    val serviceClassName: ClassName,
    val descriptorClassName: ClassName,
    val clientImplClassName: ClassName,
    val functions: List<RpcFunctionModel>,
)