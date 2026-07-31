package dev.slne.surf.eventbus.ksp.processor.query.model

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeParameterResolver
import dev.slne.surf.eventbus.ksp.processor.query.Names

class QueryServiceModelFactory(private val logger: KSPLogger) {
    fun create(declaration: KSClassDeclaration): QueryServiceModel? {
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error(
                "Only interfaces can be annotated with @${Names.QUERY_SERVICE_ANNOTATION}",
                declaration,
            )
            return null
        }

        // A private/file-private contract can't be referenced from the generated (internal)
        // descriptor. Such a contract is only ever used to test the registry directly, never
        // through a generated proxy, so skipping codegen for it is correct, not a limitation.
        val visibility = declaration.getVisibility()
        if (visibility == Visibility.PRIVATE || visibility == Visibility.LOCAL) {
            return null
        }

        if (declaration.typeParameters.isNotEmpty()) {
            logger.error(
                "Type parameters are not allowed on interfaces annotated with @${Names.QUERY_SERVICE_ANNOTATION}",
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

        val timeoutMillis = declaration.annotations
            .firstOrNull { it.shortName.asString() == Names.QUERY_SERVICE_ANNOTATION }
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "timeoutMillis" }
            ?.value as? Long
            ?: Names.DEFAULT_TIMEOUT_MILLIS

        val classTypeParameterResolver = declaration.typeParameters.toTypeParameterResolver()
        val seenFunctionNames = mutableSetOf<String>()
        val functions = mutableListOf<QueryFunctionModel>()

        for (property in declaration.getAllProperties()) {
            logger.error(
                "Cannot generate descriptor for property ${property.simpleName.asString()}: properties are not allowed",
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
                logger.error(
                    "@QueryService method $functionName must be suspend: a broadcast query is " +
                            "always asynchronous.",
                    function,
                )
                continue
            }

            if (function.typeParameters.isNotEmpty()) {
                logger.error(
                    "Cannot generate descriptor for function $functionName: type parameters are not allowed",
                    function,
                )
                continue
            }

            val fireAndForget = function.annotations.any {
                it.shortName.asString() == "FireAndForget"
            }

            if (fireAndForget) {
                logger.error(
                    "@FireAndForget has no meaning on a @QueryService method: a question " +
                            "without an answer is an event. Use bus.publish(...) instead.",
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

            val resolvedReturnType = returnType.resolve()
            val returnQualifiedName = resolvedReturnType.declaration.qualifiedName?.asString()

            if (returnQualifiedName == "kotlin.Unit") {
                logger.error(
                    "@QueryService method $functionName must not return Unit: a question " +
                            "without an answer is an event.",
                    function,
                )
                continue
            }

            if (!resolvedReturnType.isMarkedNullable) {
                logger.error(
                    "@QueryService method $functionName must return a nullable type: null means " +
                            "abstain, so a non-nullable return type cannot express \"not mine\".",
                    function,
                )
                continue
            }

            functions += QueryFunctionModel(
                declaration = function,
                name = functionName,
                invokerName = "${functionName}Invoker",
                invokerFunctionName = "invoke${functionName.replaceFirstChar { it.uppercaseChar() }}",
                returnType = returnType,
                parameters = function.parameters,
                typeParameterResolver = function.typeParameters.toTypeParameterResolver(classTypeParameterResolver),
            )
        }

        return QueryServiceModel(
            declaration = declaration,
            containingFile = ksFile,
            simpleName = simpleName,
            fqName = fqName,
            packageName = packageName,
            serviceClassName = serviceClassName,
            descriptorClassName = descriptorClassName,
            clientImplClassName = clientImplClassName,
            functions = functions,
            timeoutMillis = timeoutMillis,
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
