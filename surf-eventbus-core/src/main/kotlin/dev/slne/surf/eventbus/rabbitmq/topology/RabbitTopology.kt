package dev.slne.surf.eventbus.rabbitmq.topology

/**
 * Every exchange, queue and routing-key name used by the library.
 *
 * Centralised so that a mismatch between the declaring and the publishing side becomes a
 * compile-time impossibility rather than a message that vanishes at runtime.
 */
object RabbitTopology {

    /** Routes RPC and fire-and-forget by target name. Type `direct`. */
    const val RPC_EXCHANGE = "surf.rpc"

    private const val MAX_NAME_LENGTH = 255
    private val illegalCharacter = "[^a-zA-Z0-9._-]".toRegex()

    /** Shared, durable queue. Every instance of a service competes for its messages. */
    fun serviceQueue(serviceName: String): String = build("surf.service.", serviceName)

    /** Per-process queue for messages addressed at one specific instance. */
    fun instanceQueue(instanceId: String): String = build("surf.instance.", instanceId)

    /** Per-process RPC reply queue. */
    fun replyQueue(instanceId: String): String = build("surf.reply.", instanceId)

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
