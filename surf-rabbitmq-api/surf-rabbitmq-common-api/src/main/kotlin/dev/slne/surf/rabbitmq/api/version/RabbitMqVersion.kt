package dev.slne.surf.rabbitmq.api.version

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
data class RabbitMqVersion(
    val major: Int,
    val minor: Int,
    val patch: Int = 0
) : Comparable<RabbitMqVersion> {

    init {
        require(major >= 0 && minor >= 0 && patch >= 0) {
            "Version components must not be negative: $major.$minor.$patch"
        }
    }

    override fun compareTo(other: RabbitMqVersion): Int {
        var result = major.compareTo(other.major)
        if (result != 0) return result
        result = minor.compareTo(other.minor)
        if (result != 0) return result
        return patch.compareTo(other.patch)
    }

    fun isNewerThan(other: RabbitMqVersion): Boolean = this > other
    fun isOlderThan(other: RabbitMqVersion): Boolean = this < other
    fun isAtLeast(other: RabbitMqVersion): Boolean = this >= other
    fun isAtMost(other: RabbitMqVersion): Boolean = this <= other

    fun isAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean =
        isAtLeast(RabbitMqVersion(major, minor, patch))

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
         */
        const val AMQP_HEADER = "x-surf-rabbitmq-version"

        /**
         * Placeholder for peers that did not announce a version.
         * Compares as older than every real version.
         */
        val UNKNOWN = RabbitMqVersion(0, 0, 0)

        /**
         * The version of the surf-rabbitmq library running in this process.
         */
        val CURRENT: RabbitMqVersion = parseOrNull(BuildVersion.VERSION) ?: UNKNOWN

        /**
         * Parses a version string such as `1.6.0`, `1.6` or `1.6.0-SNAPSHOT`.
         * Missing minor/patch components default to `0`.
         *
         * @return the parsed version, or `null` if [value] is not a valid version
         */
        fun parseOrNull(value: String?): RabbitMqVersion? {
            if (value.isNullOrBlank()) return null

            val numeric = value.trim().substringBefore('-').substringBefore('+')
            val parts = numeric.split('.')
            if (parts.size > 3) return null

            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts.getOrNull(1)?.let { it.toIntOrNull() ?: return null } ?: 0
            val patch = parts.getOrNull(2)?.let { it.toIntOrNull() ?: return null } ?: 0
            if (major < 0 || minor < 0 || patch < 0) return null

            return RabbitMqVersion(major, minor, patch)
        }

        /**
         * Extracts the sender's version from AMQP message [headers].
         *
         * @return the announced version, or [UNKNOWN] if the header is absent or invalid
         */
        fun fromHeaders(headers: Map<String, Any?>?): RabbitMqVersion =
            parseOrNull(headers?.get(AMQP_HEADER)?.toString()) ?: UNKNOWN
    }
}
