package dev.slne.surf.eventbus.credentials.provider

import dev.slne.surf.eventbus.credentials.Credentials
import dev.slne.surf.eventbus.credentials.CredentialsConfigurable

/**
 * The seam an operator hooks a secret store into.
 *
 * It existed for Redis and not for RabbitMQ, which meant one transport could read its password
 * from a vault and the other could only read it from a file. Neither the transports nor the
 * operators asked for that difference.
 */
interface CredentialsProvider<C : Credentials, CF : CredentialsConfigurable> {
    fun credentials(config: CF): C
}
