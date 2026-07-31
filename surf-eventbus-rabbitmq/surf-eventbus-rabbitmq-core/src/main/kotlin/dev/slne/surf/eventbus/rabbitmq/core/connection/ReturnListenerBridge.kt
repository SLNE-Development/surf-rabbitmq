package dev.slne.surf.eventbus.rabbitmq.core.connection

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport
import dev.slne.surf.eventbus.audit.AuditSink
import dev.slne.surf.eventbus.rabbitmq.audit.AuditMessageIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Surfaces messages the broker sent back as unroutable.
 *
 * `mandatory = true` makes the broker return such a message instead of dropping it, but the
 * return arrives asynchronously on the channel and is invisible to the publisher unless
 * something listens. Without this bridge a caller would wait out the full request timeout for
 * a message that was rejected within milliseconds.
 *
 * Ordering guarantee this relies on: the broker sends `basic.return` **before** the confirm
 * ack of the same message, on the same channel. With confirms enabled (the default), a
 * completed `publish()` therefore implies any return has already been processed.
 */
class ReturnListenerBridge(
    private val scope: CoroutineScope,
    private val serviceName: String,
    private val instanceId: String,
    private val auditServiceName: String,
    private val auditSink: AuditSink,
    private val onReturned: (messageId: String, routingKey: String, reason: String) -> Unit
) {
    companion object {
        private val log = logger()
    }

    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val returned = ConcurrentHashMap<String, String>()

    /** Installs the listener on [channel]. Call once per publisher channel. */
    fun install(channel: Channel) {
        channel.addReturnListener { replyCode, replyText, _, routingKey, properties, body ->
            val messageId = properties?.messageId
            val reason = "$replyCode $replyText"

            log.atWarning().log(
                "Message to '%s' was returned as unroutable: %s", routingKey, reason
            )

            // A message addressed at the audit service is never itself audited: reporting it
            // would publish another fire-and-forget call to the same unreachable destination,
            // which would bounce the same way and report again, forever.
            if (routingKey != auditServiceName) {
                scope.launch {
                    auditSink.report(
                        AuditReport(
                            messageUuid = AuditMessageIdentity.of(properties ?: AMQP.BasicProperties.Builder().build()),
                            kind = AuditKind.UNROUTABLE,
                            originService = serviceName,
                            originInstance = null,
                            reportedByService = serviceName,
                            reportedByInstance = instanceId,
                            failedAtEpochMs = System.currentTimeMillis(),
                            routingKey = routingKey,
                            payloadSizeBytes = body.size,
                            payload = body,
                        )
                    )
                }
            }

            if (messageId != null && pending.contains(messageId)) {
                returned[messageId] = reason
                onReturned(messageId, routingKey, reason)
            }
        }
    }

    /** Starts watching for a return of [messageId]. */
    fun register(messageId: String) {
        pending += messageId
    }

    /** Stops watching and clears any recorded return. */
    fun unregister(messageId: String) {
        pending -= messageId
        returned.remove(messageId)
    }

    /** The broker's reason if this message was returned, otherwise `null`. */
    fun returnedReason(messageId: String): String? = returned[messageId]
}
