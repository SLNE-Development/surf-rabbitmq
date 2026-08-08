package dev.slne.surf.eventbus.core.dispatch

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.service.serialization.ServiceSerializerCache
import dev.slne.surf.eventbus.core.registry.QueryServiceRegistry
import dev.slne.surf.eventbus.service.ServiceInvoker
import dev.slne.surf.eventbus.query.descriptor.QueryServiceDescriptor
import dev.slne.surf.eventbus.transport.QueryFrame
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.json.Json

/**
 * Dispatches an incoming query frame to its registered implementation.
 *
 * Four rules, in [dispatch]: no implementation offered here means silence, an answering handler
 * sends exactly one answer, an abstaining handler (`null`) sends nothing, and a throwing handler
 * is audited but never answers — an error answer would overtake a correct one from another
 * process.
 */
class QueryDispatcher(
    private val registry: QueryServiceRegistry,
    private val instanceId: String,
    private val auditSink: AuditSink,
    private val json: Json,
    private val transport: QueryTransport
) {
    private val serializerCache = ServiceSerializerCache()

    suspend fun dispatch(frame: QueryFrame) {
        val implementation = registry.implementationOf(frame.contract) ?: return

        val answer = try {
            invoke(implementation, frame)
        } catch (throwable: Throwable) {
            // The error stays here: another process may still answer correctly, and an error
            // answer would overtake it. For the asker a broken handler looks exactly like
            // abstention - which is why this audit row is the only trace there is.
            log.atSevere().withCause(throwable)
                .log("Query handler %s#%s failed", frame.contract, frame.callable)
            auditSink.report(failureReport(frame, throwable))
            return
        }

        // null means abstain: not "no", and not an answer. Nothing goes out.
        if (answer == null) return

        transport.answer(frame, answer)
    }

    private suspend fun invoke(implementation: Any, frame: QueryFrame): String? {
        val descriptor = descriptorOf(frame.contract, implementation.javaClass.classLoader)
        val callable = descriptor.getCallable(frame.callable)
            ?: error("${frame.contract} has no callable '${frame.callable}'")

        val argumentsSerializer = serializerCache.getParameterSerializer(callable, json.serializersModule)
        val arguments = json.decodeFromString(argumentsSerializer, frame.payload)

        @Suppress("UNCHECKED_CAST")
        val result = (callable.invoker as ServiceInvoker<Any>)
            .call(implementation, arguments)
            ?: return null

        val returnSerializer = serializerCache.getReturnTypeSerializer(callable, json.serializersModule)
        return json.encodeToString(returnSerializer, result)
    }

    private fun descriptorOf(contract: String, classLoader: ClassLoader?): QueryServiceDescriptor<Any> {
        val descriptor = lookupDescriptor(contract, classLoader)
            ?: error("no generated descriptor found for $contract; is it annotated with @QueryService?")

        @Suppress("UNCHECKED_CAST")
        return descriptor as QueryServiceDescriptor<Any>
    }

    private fun failureReport(frame: QueryFrame, throwable: Throwable) = AuditReport(
        messageUuid = frame.correlationId,
        kind = AuditKind.QUERY_HANDLER_FAILED,
        originService = frame.originInstanceId,
        originInstance = frame.originInstanceId,
        reportedByService = instanceId,
        reportedByInstance = instanceId,
        failedAtEpochMs = System.currentTimeMillis(),
        contract = frame.contract,
        callable = frame.callable,
        correlationId = frame.correlationId,
        exceptionClass = throwable.javaClass.name,
        exceptionMessage = throwable.message,
        stacktrace = throwable.stackTraceToString()
    )

    /**
     * Generated descriptors, per contract and defining class loader.
     *
     * An instance field rather than the `object` it used to be. As a singleton it outlived
     * every bus in the process and held its keys - which include a `ClassLoader` - strongly,
     * so a Paper plugin could never be unloaded once one of its queries had been dispatched.
     * Tied to the dispatcher, the entries die when the bus does.
     *
     * Bounded as well: the size is naturally small (only contracts that passed the registry
     * check reach here), and a cap costs nothing to guarantee that.
     */
    private val descriptorCache: Cache<Pair<String, ClassLoader?>, Optional> = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_DESCRIPTORS)
        .build()

    /** Caffeine cannot store nulls, and "no descriptor" is worth caching. */
    private class Optional(val value: Any?)

    private fun lookupDescriptor(contract: String, classLoader: ClassLoader?): Any? =
        descriptorCache.get(contract to classLoader) { (name, loader) ->
            // Every load is inside the try. The contract lookup used to sit outside it, so a
            // ClassNotFoundException there escaped through computeIfAbsent instead of
            // producing the "no generated descriptor" message the caller is written for.
            try {
                val contractClass = Class.forName(name, false, loader)
                val descriptorFqName =
                    "${contractClass.packageName}.${contractClass.simpleName}Descriptor"

                Optional(Class.forName(descriptorFqName, false, loader).kotlin.objectInstance)
            } catch (_: ClassNotFoundException) {
                Optional(null)
            } catch (_: LinkageError) {
                Optional(null)
            }
        }.value

    companion object {
        private val log = logger()
        private const val MAX_CACHED_DESCRIPTORS = 1_024L
    }
}
