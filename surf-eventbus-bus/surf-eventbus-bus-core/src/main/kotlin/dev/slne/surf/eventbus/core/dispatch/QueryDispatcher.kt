package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.core.registry.QueryServiceRegistry
import dev.slne.surf.eventbus.transport.QueryFrame

/**
 * Dispatches an incoming query frame to its registered implementation.
 *
 * Stubbed until the Plan 3 KSP descriptor generator exists: without a generated descriptor there
 * is no way to know how to decode [QueryFrame.payload] or invoke the right method on the
 * implementation — only whether one is registered for the contract at all.
 */
class QueryDispatcher(private val registry: QueryServiceRegistry) {

    suspend fun dispatch(frame: QueryFrame) {
        if (registry.implementationOf(frame.contract) == null) return

        throw NotImplementedError(
            "query dispatch for ${frame.contract}#${frame.callable} requires the Plan 3 KSP " +
                    "descriptor generator, which is not yet available"
        )
    }
}
