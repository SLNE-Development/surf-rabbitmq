package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.Visibility

object QueryRules : ContractRules {

    override fun acceptsContract(declaration: KSClassDeclaration, logger: KSPLogger): Boolean {
        // A private/file-private contract can't be referenced from the generated (internal)
        // descriptor. Such a contract is only ever used to test the registry directly, never
        // through a generated proxy, so skipping codegen for it is correct, not a limitation.
        val visibility = declaration.getVisibility()
        return visibility != Visibility.PRIVATE && visibility != Visibility.LOCAL
    }

    override fun acceptsFunction(
        function: KSFunctionDeclaration,
        returnType: KSTypeReference,
        fireAndForget: Boolean,
        logger: KSPLogger,
    ): Boolean {
        val functionName = function.simpleName.asString()

        if (fireAndForget) {
            logger.error(
                "@FireAndForget has no meaning on a @QueryService method: a question " +
                        "without an answer is an event. Use bus.publish(...) instead.",
                function,
            )
            return false
        }

        val resolved = returnType.resolve()

        if (resolved.declaration.qualifiedName?.asString() == "kotlin.Unit") {
            logger.error(
                "@QueryService method $functionName must not return Unit: a question " +
                        "without an answer is an event.",
                function,
            )
            return false
        }

        if (!resolved.isMarkedNullable) {
            logger.error(
                "@QueryService method $functionName must return a nullable type: null means " +
                        "abstain, so a non-nullable return type cannot express \"not mine\".",
                function,
            )
            return false
        }

        return true
    }
}
