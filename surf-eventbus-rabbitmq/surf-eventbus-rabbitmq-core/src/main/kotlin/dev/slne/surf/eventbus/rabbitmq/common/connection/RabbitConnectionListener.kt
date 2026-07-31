package dev.slne.surf.eventbus.rabbitmq.common.connection

import com.rabbitmq.client.ShutdownSignalException

interface RabbitConnectionListener {

    fun onConnectionLost(cause: ShutdownSignalException) = Unit

    fun onRecoveryStarted() = Unit

    fun onQueueRecovered(
        oldName: String,
        newName: String
    ) = Unit

    fun onRecoveryCompleted(generation: Long) = Unit
}