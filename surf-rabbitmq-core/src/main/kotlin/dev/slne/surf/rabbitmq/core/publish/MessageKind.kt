package dev.slne.surf.rabbitmq.core.publish

import kotlin.time.Duration

/**
 * What kind of message is being published, which determines its expiry and persistence.
 *
 * Before this existed, the request timeout was applied as `expiration` to every outgoing
 * message. That is right for a request nobody is waiting for any more, and wrong for a
 * fire-and-forget message, which has to survive in the queue until its service comes back.
 */
enum class MessageKind {
    /** A request whose caller is waiting for a reply. */
    RPC_REQUEST,

    /** A reply to an [RPC_REQUEST]. */
    RPC_RESPONSE,

    /** A message to one service instance with no reply expected. */
    FIRE_AND_FORGET,

    /** An event published to the topic exchange. */
    EVENT;

    /**
     * The AMQP `expiration` for this kind, or `null` for no expiry.
     *
     * @param requestTimeout how long a caller waits for a reply
     */
    fun expirationMillis(requestTimeout: Duration): String? = when (this) {
        // Nobody is waiting once the timeout has passed; executing it then would apply a
        // stale decision.
        RPC_REQUEST, RPC_RESPONSE -> requestTimeout.inWholeMilliseconds.toString()

        // No caller is waiting, so there is nothing to go stale. Expiring these would throw
        // away work whenever a service was down longer than a request timeout.
        FIRE_AND_FORGET, EVENT -> null
    }

    /**
     * The AMQP delivery mode: `2` persistent, `1` transient.
     */
    fun deliveryMode(persistRequests: Boolean, persistResponses: Boolean): Int = when (this) {
        RPC_REQUEST, FIRE_AND_FORGET -> if (persistRequests) 2 else 1
        RPC_RESPONSE -> if (persistResponses) 2 else 1

        // Events may target a durable shared queue, where a transient message would be
        // dropped on broker restart without any upside.
        EVENT -> 2
    }
}
