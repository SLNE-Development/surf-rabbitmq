package dev.slne.surf.eventbus.ksp.processor.query.model

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName

data class QueryServiceModel(
    val declaration: KSClassDeclaration,
    val containingFile: KSFile,
    val simpleName: String,
    val fqName: String,
    val packageName: String,
    val serviceClassName: ClassName,
    val descriptorClassName: ClassName,
    val clientImplClassName: ClassName,
    val functions: List<QueryFunctionModel>,
    val timeoutMillis: Long,
)
