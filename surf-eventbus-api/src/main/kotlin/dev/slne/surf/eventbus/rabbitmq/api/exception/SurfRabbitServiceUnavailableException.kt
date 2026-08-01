package dev.slne.surf.eventbus.rabbitmq.api.exception

import java.io.Serial

/**
 * The broker could not route a message: no queue is bound for the target.
 *
 * This means the service was never deployed or the name is misspelled — **not** that it is
 * temporarily down. A service whose instances have all stopped still has its durable queue, so
 * its messages wait there instead of being returned.
 */
class SurfRabbitServiceUnavailableException(
    val target: String,
    val replyText: String
) : SurfRabbitRequestException(
    "No service is registered for '$target' ($replyText). " +
            "Check the service name; a service that is merely offline would still have a queue."
) {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}
