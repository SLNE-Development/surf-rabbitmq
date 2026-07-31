package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import java.util.UUID

/**
 * The identity that ties every report about one message together.
 *
 * The retry ladder republishes the same message up to four times, possibly from different
 * processes. Without a shared identity the database would show four unrelated incidents instead of
 * one message that failed four times. The header survives the republish because
 * `RetryPublisher.withNextAttempt` copies the header map.
 */
object AuditMessageIdentity {

    const val HEADER = "x-surf-audit-message-id"

    fun of(properties: AMQP.BasicProperties): String =
        properties.headers?.get(HEADER)?.toString() ?: UUID.randomUUID().toString()

    fun stamp(properties: AMQP.BasicProperties, id: String): AMQP.BasicProperties {
        val headers = HashMap<String, Any?>(properties.headers ?: emptyMap())
        headers[HEADER] = id

        return properties.builder().headers(headers).build()
    }
}
