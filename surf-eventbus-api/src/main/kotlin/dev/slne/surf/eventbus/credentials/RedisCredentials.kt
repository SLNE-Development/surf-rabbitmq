package dev.slne.surf.eventbus.credentials

/** An address and a secret, and nothing about how a connection is opened with them. */
data class RedisCredentials(
    val host: String,
    val port: Int,
    val password: String?,
) : Credentials {
    override fun toString(): String = "RedisCredentials(host=$host, port=$port, password=<redacted>)"
}
