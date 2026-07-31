package dev.slne.surf.eventbus.rabbitmq.api.target

/**
 * Where a message goes.
 *
 * Both variants are published to `surf.rpc`, which is a `direct` exchange: the routing key is
 * matched exactly against the binding of the destination queue.
 */
sealed interface RabbitTarget {

    /** The routing key used when publishing to `surf.rpc`. */
    val routingKey: String

    /**
     * Any one instance of [serviceName].
     *
     * All instances compete for the same queue, so the broker load-balances between them.
     * This is the normal case.
     */
    data class ServiceTarget(val serviceName: String) : RabbitTarget {
        init {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
        }

        override val routingKey: String get() = serviceName
    }

    /**
     * One specific process, addressed by its [RabbitIdentity.instanceId].
     *
     * Use for things that only make sense on one machine, such as moving a player to a
     * particular Paper server. The message is lost if that instance is offline, since its
     * queue is `autoDelete`.
     */
    data class InstanceTarget(val instanceId: String) : RabbitTarget {
        init {
            require(instanceId.isNotBlank()) { "instanceId must not be blank" }
        }

        override val routingKey: String get() = instanceId
    }
}
