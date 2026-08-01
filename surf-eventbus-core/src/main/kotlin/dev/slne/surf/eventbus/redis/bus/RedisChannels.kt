package dev.slne.surf.eventbus.redis.bus

/**
 * Every Redis channel name the bus uses.
 *
 * Two event families with distinct prefixes rather than one family with an encoding marker: a
 * Redisson `Topic` is bound to exactly one codec, and a pattern subscription must not catch the
 * other family — Redis glob crosses dots, so `surf.eventbus.*` would.
 */
object RedisChannels {

    private const val PREFIX = "surf.eventbus."

    const val JSON_PATTERN = PREFIX + "json.*"
    const val BINARY_PATTERN = PREFIX + "bin.*"

    fun json(topic: String): String = PREFIX + "json." + topic

    fun binary(topic: String): String = PREFIX + "bin." + topic

    fun query(contract: String): String = PREFIX + "query." + contract

    fun reply(instanceId: String): String = PREFIX + "reply." + instanceId

    /** The topic a JSON or binary channel name belongs to, or `null` for another family. */
    fun topicOf(channel: String): String? = when {
        channel.startsWith(PREFIX + "json.") -> channel.removePrefix(PREFIX + "json.")
        channel.startsWith(PREFIX + "bin.") -> channel.removePrefix(PREFIX + "bin.")
        else -> null
    }
}
