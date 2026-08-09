package dev.slne.surf.eventbus.credentials

/** An address and a secret, and nothing about how a connection is opened with them. */
data class RabbitCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val vhost: String,
) : Credentials {
    override fun toString(): String =
        "RabbitCredentials(host=$host, port=$port, username=$username, " +
            "password=<redacted>, vhost=$vhost)"
}
