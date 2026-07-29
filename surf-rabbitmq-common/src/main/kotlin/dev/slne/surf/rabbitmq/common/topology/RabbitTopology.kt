package dev.slne.surf.rabbitmq.common.topology

/**
 * Every exchange, queue and routing-key name used by the library.
 *
 * Centralised so that a mismatch between the declaring and the publishing side becomes a
 * compile-time impossibility rather than a message that vanishes at runtime.
 */
object RabbitTopology {

    /** Routes RPC and fire-and-forget by target name. Type `direct`. */
    const val RPC_EXCHANGE = "surf.rpc"

    /** Routes events by topic pattern. Type `topic`. */
    const val EVENTS_EXCHANGE = "surf.events"

    /** Dead-letter destination for rejected messages. Type `direct`. */
    const val DLX_EXCHANGE = "surf.dlx"

    /**
     * Audit queue for messages the broker returned as unroutable.
     *
     * Bound to nothing. The publish-side return listener republishes returned messages
     * here (Plan 4). Deliberately not an alternate exchange: an AE would swallow the
     * `basic.return` that the fail-fast path depends on.
     */
    const val UNROUTABLE_QUEUE = "surf.unroutable"

    private const val MAX_NAME_LENGTH = 255
    private val illegalCharacter = "[^a-zA-Z0-9._-]".toRegex()

    /** Shared, durable queue. Every instance of a service competes for its messages. */
    fun serviceQueue(serviceName: String): String = build("surf.service.", serviceName)

    /** Per-process queue for messages addressed at one specific instance. */
    fun instanceQueue(instanceId: String): String = build("surf.instance.", instanceId)

    /** Per-process RPC reply queue. */
    fun replyQueue(instanceId: String): String = build("surf.reply.", instanceId)

    /** Durable queue shared by all instances of a service. Exactly one instance handles each event. */
    fun sharedEventQueue(serviceName: String): String = build("surf.events.shared.", serviceName)

    /** Ephemeral queue owned by one instance. Every instance receives its own copy. */
    fun instanceEventQueue(instanceId: String): String = build("surf.events.instance.", instanceId)

    /** Durable queue holding messages a service could not process. */
    fun deadLetterQueue(serviceName: String): String = build("surf.dlq.", serviceName)

    /**
     * Replaces characters that are not valid in AMQP names.
     *
     * Applying this twice yields the same result, so a sanitised name can be passed through
     * again without changing.
     */
    fun sanitize(value: String): String = value.replace(illegalCharacter, "_")

    private fun build(prefix: String, rawName: String): String {
        require(rawName.isNotBlank()) {
            "Name must not be blank: a blank name would collide with every other blank name"
        }

        val name = prefix + sanitize(rawName)

        require(name.length <= MAX_NAME_LENGTH) {
            "AMQP names are limited to $MAX_NAME_LENGTH characters, " +
                    "but '$name' is ${name.length}"
        }

        return name
    }
}
