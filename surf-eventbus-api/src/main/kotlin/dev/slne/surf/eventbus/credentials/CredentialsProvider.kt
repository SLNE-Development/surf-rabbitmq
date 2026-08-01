package dev.slne.surf.eventbus.credentials

/**
 * The seam an operator hooks a secret store into.
 *
 * It existed for Redis and not for RabbitMQ, which meant one transport could read its password
 * from a vault and the other could only read it from a file. Neither the transports nor the
 * operators asked for that difference.
 */
interface CredentialsProvider
