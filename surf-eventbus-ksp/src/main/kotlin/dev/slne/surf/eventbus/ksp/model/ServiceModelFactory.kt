package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver

/**
 * Reads one contract interface into a [ServiceModel].
 *
 * Reading is identical for both kinds — that is the whole reason there is one factory. What
 * differs is which shapes are allowed, and that lives in [ContractRules].
 */
class ServiceModelFactory(private val logger: KSPLogger) {

    fun create(declaration: KSClassDeclaration, kind: ContractKind): ServiceModel? {
        val rules = when (kind) {
            ContractKind.QUERY -> QueryRules
            ContractKind.RPC -> RpcRules
        }

        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error(
                "Only interfaces can be annotated with @${kind.annotationSimpleName}",
                declaration,
            )
            return null
        }

        if (!rules.acceptsContract(declaration, logger)) return null

        if (declaration.typeParameters.isNotEmpty()) {
            logger.error(
                "Type parameters are not allowed on interfaces annotated with " +
                        "@${kind.annotationSimpleName}",
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

        val serviceClassName = declaration.toClassName()
        val classTypeParameterResolver = declaration.typeParameters.toTypeParameterResolver()
        val seenFunctionNames = mutableSetOf<String>()
        val functions = mutableListOf<ServiceFunctionModel>()

        for (property in declaration.getAllProperties()) {
            logger.error(
                "Cannot generate descriptor for property ${property.simpleName.asString()}: " +
                        "properties are not allowed",
                property,
            )
        }

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
                logger.error(suspendMessage(kind, functionName), function)
                continue
            }

            if (function.typeParameters.isNotEmpty()) {
                logger.error(
                    "Cannot generate descriptor for function $functionName: type parameters are not allowed",
                    function,
                )
                continue
            }

            val returnType = function.returnType ?: run {
                logger.error(
                    "Cannot generate descriptor for function $functionName: no return type",
                    function,
                )
                null
            } ?: continue

            val fireAndForget = function.annotations.any {
                it.shortName.asString() == "FireAndForget"
            }

            if (!rules.acceptsFunction(function, returnType, fireAndForget, logger)) continue

            functions += ServiceFunctionModel(
                declaration = function,
                name = functionName,
                invokerName = "${functionName}Invoker",
                invokerFunctionName = "invoke${functionName.replaceFirstChar { it.uppercaseChar() }}",
                returnType = returnType,
                parameters = function.parameters,
                typeParameterResolver = function.typeParameters
                    .toTypeParameterResolver(classTypeParameterResolver),
                fireAndForget = fireAndForget,
            )
        }

        return ServiceModel(
            kind = kind,
            declaration = declaration,
            containingFile = ksFile,
            simpleName = simpleName,
            fqName = fqName,
            packageName = fqName.substringBeforeLast('.', ""),
            serviceClassName = serviceClassName,
            descriptorClassName = serviceClassName.peerClass("${simpleName}Descriptor"),
            clientImplClassName = serviceClassName.peerClass("${simpleName}ClientImpl"),
            functions = functions,
            defaultService = if (kind == ContractKind.RPC) readServiceAttribute(declaration) else "",
            timeoutMillis = if (kind == ContractKind.QUERY) readTimeoutAttribute(declaration) else 0L,
        )
    }

    private fun suspendMessage(kind: ContractKind, functionName: String): String = when (kind) {
        ContractKind.QUERY ->
            "@QueryService method $functionName must be suspend: a broadcast query is " +
                    "always asynchronous."

        ContractKind.RPC ->
            "Cannot generate descriptor for function $functionName: must be suspend"
    }

    private fun readServiceAttribute(declaration: KSClassDeclaration): String =
        declaration.annotations
            .firstOrNull { it.shortName.asString() == ContractKind.RPC.annotationSimpleName }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "service" }
            ?.value as? String
            ?: ""

    private fun readTimeoutAttribute(declaration: KSClassDeclaration): Long =
        declaration.annotations
            .firstOrNull { it.shortName.asString() == ContractKind.QUERY.annotationSimpleName }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "timeoutMillis" }
            ?.value as? Long
            ?: DEFAULT_TIMEOUT_MILLIS

    private fun KSFunctionDeclaration.isObjectMethod(): Boolean {
        val name = simpleName.asString()
        return when (name) {
            "toString" if parameters.isEmpty() -> true
            "equals" if parameters.size == 1 -> true
            "hashCode" if parameters.isEmpty() -> true
            else -> false
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L
    }
}
