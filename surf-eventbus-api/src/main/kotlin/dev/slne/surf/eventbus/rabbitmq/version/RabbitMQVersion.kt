package dev.slne.surf.eventbus.rabbitmq.version

import dev.slne.surf.eventbus.rabbitmq.version.RabbitMQVersion.Companion.AMQP_HEADER
import dev.slne.surf.eventbus.rabbitmq.version.RabbitMQVersion.Companion.UNKNOWN

/**
 * The semantic version of a surf-rabbitmq library instance.
 *
 * Every published request and response announces the sender's version via the
 * [AMQP_HEADER] message header. Received packets expose the announced version through
 * `RabbitPacket.senderVersion`, allowing features to be gated on the peer's version so
 * that clients and servers can be updated independently of each other.
 *
 * Peers running a version older than `1.6.0` never announce a version and are reported
 * as [UNKNOWN], which is older than every real version.
 */
data class RabbitMQVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0
) : Comparable<RabbitMQVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "Version components must not be negative: $major.$minor.$patch"
        }
    }

    override fun compareTo(other: RabbitMQVersion): Int = compareValuesBy(
        this,
        other,
        RabbitMQVersion::major,
        RabbitMQVersion::minor,
        RabbitMQVersion::patch
    )

    fun isNewerThan(other: RabbitMQVersion): Boolean = this > other
    fun isOlderThan(other: RabbitMQVersion): Boolean = this < other
    fun isAtLeast(other: RabbitMQVersion): Boolean = this >= other
    fun isAtMost(other: RabbitMQVersion): Boolean = this <= other

    fun isAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean =
        isAtLeast(RabbitMQVersion(major, minor, patch))

    /**
     * `true` if the peer did not announce a version (library older than `1.6.0`)
     * or the announced version could not be parsed.
     */
    val isUnknown: Boolean
        get() = this == UNKNOWN

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * AMQP message header carrying the sender's library version as a string,
         * e.g. `1.6.0`.
         *
         * Renamed from `x-surf-rabbitmq-version` in 2.0, when the project stopped being
         * "surf-rabbitmq". This is a **wire break**: a 1.x peer does not send this header and a
         * 2.0 peer does not send the old one, so peers on either side of the change read each
         * other as [UNKNOWN] rather than as their real version. That is the same state 1.x
         * peers older than `1.6.0` already produce, and it is handled - but it does mean any
         * version-gated behaviour is off until the whole fleet is on 2.0. See
         * `docs/rollout-2.0.md`.
         */
        const val AMQP_HEADER = "x-surf-eventbus-version"

        /**
         * Placeholder for peers that did not announce a version.
         * Compares as older than every real version.
         */
        val UNKNOWN = RabbitMQVersion(0, 0, 0)

        /**
         * The version of the surf-rabbitmq library running in this process.
         */
        val CURRENT: RabbitMQVersion = parseOrNull(BuildVersion.VERSION) ?: UNKNOWN

        /**
         * Parses a version string such as `1.6.0`, `1.6` or `1.6.0-SNAPSHOT`.
         * Missing minor/patch components default to `0`.
         *
         * @return the parsed version, or `null` if [value] is not a valid version
         */
        fun parseOrNull(value: String?): RabbitMQVersion? {
            if (value.isNullOrBlank()) return null

            val numeric = value.trim().substringBefore('-').substringBefore('+')
            val parts = numeric.split('.')
            if (parts.size > 3) return null

            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: 0
            val patch = parts.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 0
            if (major < 0 || minor < 0 || patch < 0) return null

            return RabbitMQVersion(major, minor, patch)
        }

        /**
         * Extracts the sender's version from AMQP message [headers].
         *
         * @return the announced version, or [UNKNOWN] if the header is absent or invalid
         */
        fun fromHeaders(headers: Map<String, Any?>?): RabbitMQVersion =
            parseOrNull(headers?.get(AMQP_HEADER)?.toString()) ?: UNKNOWN
    }
}
