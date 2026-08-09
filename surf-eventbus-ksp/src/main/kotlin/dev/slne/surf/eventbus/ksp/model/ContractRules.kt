package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSTypeReference

/**
 * What one contract kind demands beyond what both demand.
 *
 * Split in two because rejection happens at two different granularities: a rejected contract
 * generates nothing, a rejected *function* is skipped while its siblings are still generated.
 * A single whole-list check could not express the second, and the second is what both factories
 * did before they were merged.
 */
sealed interface ContractRules {

    /** Whether this contract should be modelled at all. Reports its own errors. */
    fun acceptsContract(declaration: KSClassDeclaration, logger: KSPLogger): Boolean

    /** Whether this one function should be modelled. Reports its own errors. */
    fun acceptsFunction(
        function: KSFunctionDeclaration,
        returnType: KSTypeReference,
        fireAndForget: Boolean,
        logger: KSPLogger,
    ): Boolean
}
