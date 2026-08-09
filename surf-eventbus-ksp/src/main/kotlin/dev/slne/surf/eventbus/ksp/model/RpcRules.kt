package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference

object RpcRules : ContractRules {

    override fun acceptsContract(declaration: KSClassDeclaration, logger: KSPLogger): Boolean = true

    override fun acceptsFunction(
        function: KSFunctionDeclaration,
        returnType: KSTypeReference,
        fireAndForget: Boolean,
        logger: KSPLogger,
    ): Boolean {
        val functionName = function.simpleName.asString()

        if (fireAndForget && returnType.resolve().declaration.qualifiedName?.asString() != "kotlin.Unit") {
            logger.error(
                "@FireAndForget on $functionName requires the return type Unit: nobody " +
                        "sends an answer, so nothing can be returned.",
                function,
            )
            return false
        }

        return true
    }
}
