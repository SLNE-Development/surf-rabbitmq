package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.common.serialization.KotlinSerializerCache
import dev.slne.surf.eventbus.core.envelope.EventEnvelope
import dev.slne.surf.eventbus.core.registry.EventSubscriptionRegistry
import dev.slne.surf.eventbus.event.SurfBusEvent
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Delivers one received envelope to every matching handler.
 *
 * Four rules, each of them a decision:
 * - every match runs, exact and wildcard alike;
 * - a failing handler does not stop its neighbours, and there is nothing to decide afterwards
 *   because Pub/Sub has no ack to withhold;
 * - `includeSelf = false` drops an event this process published;
 * - an unresolvable type warns and audits exactly once per type and process lifetime.
 */
class EventDispatcher(
    private val registry: EventSubscriptionRegistry,
    private val instanceId: String,
    private val auditSink: AuditSink,
    private val json: Json,
    private val typeResolver: EventTypeResolver,
    private val serviceName: String = "unknown"
) {

    private val warnedTypes = ConcurrentHashMap.newKeySet<String>()
    private val serializerCache = KotlinSerializerCache<SurfBusEvent>(json.serializersModule)

    suspend fun dispatch(envelope: EventEnvelope, binaryPayload: ByteArray?) {
        // Nobody subscribes to anything: resolving the wire type would only fail for lack of a
        // classloader to search, and that failure would be a false unknown-type audit, not a
        // real one.
        if (registry.isEmpty()) return

        val subscriptions = registry.subscriptions()
        val eventClass = typeResolver.resolve(
            typeName = envelope.type,
            known = subscriptions.map { it.eventClass },
            loaders = subscriptions.mapTo(mutableSetOf()) { it.listener.javaClass.classLoader }
        )

        if (eventClass == null) {
            if (warnedTypes.add(envelope.type)) {
                log.atWarning().log(
                    "No class for event type %s on topic %s; discarding. A stale publisher or a " +
                            "deleted event type looks exactly like this.",
                    envelope.type, envelope.topic
                )
                auditSink.report(unknownTypeReport(envelope))
            }
            return
        }

        val matches = registry.subscriptionsFor(eventClass, envelope.topic)
        if (matches.isEmpty()) return

        val event = decode(envelope, eventClass, binaryPayload) ?: return

        for (subscription in matches) {
            if (!subscription.includeSelf && envelope.originInstanceId == instanceId) continue

            try {
                invoke(subscription.listener, subscription.method, event)
            } catch (throwable: Throwable) {
                log.atSevere().withCause(throwable)
                    .log("Event handler %s failed for %s", subscription.displayName, envelope.topic)
                auditSink.report(handlerFailureReport(envelope, subscription.displayName, throwable))
            }
        }
    }

    private fun decode(
        envelope: EventEnvelope,
        eventClass: Class<out SurfBusEvent>,
        binaryPayload: ByteArray?
    ): SurfBusEvent? {
        val event = try {
            if (binaryPayload != null) {
                BusEventCodecs.decode(eventClass, binaryPayload)
            } else {
                val payload = envelope.payload
                    ?: error("a JSON event without payload: ${envelope.type} on ${envelope.topic}")
                val serializer = serializerCache.get(eventClass)
                    ?: error("no kotlinx.serialization serializer for ${eventClass.name}")
                json.decodeFromString(serializer, payload)
            }
        } catch (throwable: Throwable) {
            log.atSevere().withCause(throwable)
                .log("Cannot decode %s on %s; discarding", envelope.type, envelope.topic)
            return null
        }

        @OptIn(InternalEventBusApi::class)
        event.originInstanceId = envelope.originInstanceId
        @OptIn(InternalEventBusApi::class)
        event.publishedAtEpochMs = envelope.publishedAtEpochMs

        return event
    }

    private fun invoke(listener: Any, method: java.lang.reflect.Method, event: SurfBusEvent) {
        method.invoke(listener, event)
    }

    private fun unknownTypeReport(envelope: EventEnvelope) = AuditReport(
        messageUuid = UUID.randomUUID().toString(),
        kind = AuditKind.UNKNOWN_EVENT_TYPE,
        originService = serviceName,
        originInstance = envelope.originInstanceId,
        reportedByService = serviceName,
        reportedByInstance = instanceId,
        failedAtEpochMs = envelope.publishedAtEpochMs,
        routingKey = envelope.topic,
        messageType = envelope.type,
        payloadEncoding = "JSON",
        payload = envelope.payload?.toByteArray(),
        payloadSizeBytes = envelope.payload?.length ?: 0
    )

    private fun handlerFailureReport(envelope: EventEnvelope, handler: String, throwable: Throwable) =
        AuditReport(
            messageUuid = UUID.randomUUID().toString(),
            kind = AuditKind.EVENT_HANDLER_FAILED,
            originService = serviceName,
            originInstance = envelope.originInstanceId,
            reportedByService = serviceName,
            reportedByInstance = instanceId,
            failedAtEpochMs = envelope.publishedAtEpochMs,
            routingKey = envelope.topic,
            messageType = envelope.type,
            handler = handler,
            attempt = 0,
            terminal = true,
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            stacktrace = throwable.stackTraceToString(),
            payloadEncoding = "JSON",
            payload = envelope.payload?.toByteArray(),
            payloadSizeBytes = envelope.payload?.length ?: 0
        )

    companion object {
        private val log = logger()
    }
}
