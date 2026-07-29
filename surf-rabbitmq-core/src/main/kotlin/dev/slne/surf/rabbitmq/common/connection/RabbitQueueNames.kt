package dev.slne.surf.rabbitmq.common.connection

import java.util.*

object RabbitQueueNames {
    private const val CALLBACK_MARKER = ".callback."
    private val invalidCharacter = "[^a-zA-Z0-9._-]".toRegex()

    fun callbackPrefix(connectionName: String): String {
        return sanitize(connectionName) + CALLBACK_MARKER
    }

    fun newCallbackQueueName(connectionName: String): String {
        return callbackPrefix(connectionName) + UUID.randomUUID()
    }

    fun isCallbackQueue(
        connectionName: String,
        queueName: String
    ): Boolean {
        return queueName.startsWith(callbackPrefix(connectionName))
    }

    private fun sanitize(value: String): String {
        return value.replace(invalidCharacter, "_")
    }
}