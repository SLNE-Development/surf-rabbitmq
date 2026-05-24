package dev.slne.surf.rabbitmq.processor.rpc.model

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import dev.slne.surf.rabbitmq.processor.Names

class RpcServiceModelFactory(private val logger: KSPLogger) {
    fun create(declaration: KSClassDeclaration): RpcServiceModel? {
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error(
                "Only interfaces can be annotated with @${Names.RPC_SERVICE_ANNOTATION}",
                declaration,
            )
            return null
        }

        val simpleName = declaration.simpleName.asString()
        val fqName = declaration.qualifiedName?.asString() ?: run {
            logger.error(
                "Cannot generate descriptor for interface $simpleName: no qualified name",
                declaration,
            )
            return null
        }

        val ksFile = declaration.containingFile ?: run {
            logger.error(
                "Cannot generate descriptor for interface $simpleName: no containing file",
                declaration,
            )
            return null
        }

        val packageName = fqName.substringBeforeLast('.', "")
        val serviceClassName = declaration.toClassName()
        val descriptorClassName = serviceClassName.peerClass("${simpleName}Descriptor")
        val clientImplClassName = serviceClassName.peerClass("${simpleName}ClientImpl")

        val classTypeParameterResolver = declaration.typeParameters.toTypeParameterResolver()
        val seenFunctionNames = mutableSetOf<String>()
        val functions = mutableListOf<RpcFunctionModel>()

        for (function in declaration.getAllFunctions()) {
            if (function.isObjectMethod()) continue

            val functionName = function.simpleName.asString()
            if (!seenFunctionNames.add(functionName)) {
                logger.error(
                    "A function with the name '$functionName' is already defined in $simpleName",
                    function,
                )
                continue
            }

            if (!function.modifiers.contains(Modifier.SUSPEND)) {
                logger.error(
                    "Cannot generate descriptor for function $functionName: must be suspend",
                    function,
                )
                continue
            }

            val returnType = function.returnType
            if (returnType == null) {
                logger.error(
                    "Cannot generate descriptor for function $functionName: no return type",
                    function,
                )
                continue
            }

            functions += RpcFunctionModel(
                declaration = function,
                name = functionName,
                invokerName = "${functionName}Invoker",
                invokerFunctionName = "invoke${functionName.replaceFirstChar { it.uppercaseChar() }}",
                returnType = returnType,
                parameters = function.parameters,
                typeParameterResolver = function.typeParameters.toTypeParameterResolver(classTypeParameterResolver),
            )
        }

        return RpcServiceModel(
            declaration = declaration,
            containingFile = ksFile,
            simpleName = simpleName,
            fqName = fqName,
            packageName = packageName,
            serviceClassName = serviceClassName,
            descriptorClassName = descriptorClassName,
            clientImplClassName = clientImplClassName,
            functions = functions,
        )
    }

    private fun KSFunctionDeclaration.isObjectMethod(): Boolean {
        val name = simpleName.asString()
        return when (name) {
            "toString" if parameters.isEmpty() -> true
            "equals" if parameters.size == 1 -> true
            "hashCode" if parameters.isEmpty() -> true
            else -> false
        }
    }
}