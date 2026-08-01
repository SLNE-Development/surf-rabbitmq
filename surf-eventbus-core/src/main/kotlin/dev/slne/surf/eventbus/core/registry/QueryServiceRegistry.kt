package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.query.QueryService
import java.util.concurrent.ConcurrentHashMap

/**
 * Holds the query contracts this process offers.
 *
 * One implementation per contract per process: two would both answer the same question from the
 * same instance, and which of them wins would be a race.
 */
class QueryServiceRegistry {

    private val implementations = ConcurrentHashMap<String, Any>()

    @Volatile
    private var frozen = false

    fun register(contract: Class<*>, implementation: Any) {
        check(!frozen) { "registration is closed: freeze() has already run" }

        require(contract.getAnnotation(QueryService::class.java) != null) {
            "${contract.name} is not annotated with @QueryService"
        }
        require(contract.isInstance(implementation)) {
            "${implementation.javaClass.name} does not implement ${contract.name}"
        }

        val previous = implementations.putIfAbsent(contract.name, implementation)
        check(previous == null) {
            "${contract.name} is already offered by ${previous?.javaClass?.name} in this process"
        }
    }

    fun freeze() {
        frozen = true
    }

    fun contracts(): Set<String> = implementations.keys.toSet()

    fun implementationOf(contract: String): Any? = implementations[contract]

    fun timeoutOf(contract: Class<*>): Long =
        contract.getAnnotation(QueryService::class.java)?.timeoutMillis
            ?: error("${contract.name} is not annotated with @QueryService")
}
