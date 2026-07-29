package dev.slne.surf.rabbitmq.core.connection

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.common.connection.client.RabbitClient
import dev.slne.surf.rabbitmq.common.topology.RabbitTopology
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
    private val client: RabbitClient,
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

            // The audit copy. surf.rpc has no alternate exchange (it would suppress this
            // very basic.return), so the copy is produced here instead. Off the listener
            // thread: publishing suspends.
            scope.launch {
                runCatching {
                    client.publish(
                        exchange = "",
                        routingKey = RabbitTopology.UNROUTABLE_QUEUE,
                        body = body,
                        properties = properties ?: AMQP.BasicProperties.Builder().build(),
                        mandatory = false
                    )
                }.onFailure {
                    log.atWarning().withCause(it)
                        .log("Could not preserve returned message in %s", RabbitTopology.UNROUTABLE_QUEUE)
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
