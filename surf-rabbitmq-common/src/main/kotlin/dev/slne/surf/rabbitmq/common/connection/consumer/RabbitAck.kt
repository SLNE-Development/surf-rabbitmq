package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.Channel
import com.rabbitmq.client.ShutdownSignalException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * @see Channel.basicAck
 * @see Channel.basicNack
 * @see Channel.basicReject
 */
class RabbitAck(
    private val channelDispatcher: CoroutineDispatcher,
    private val channel: Channel,
    private val deliveryTag: Long,
    private val enabled: Boolean
) {
    private val settlementClaimed = AtomicBoolean(false)

    val isSettled: Boolean
        get() = !enabled || settlementClaimed.get()

    suspend fun ack(): Boolean {
        return settle {
            channel.basicAck(deliveryTag, false)
        }
    }

    suspend fun nack(requeue: Boolean = true): Boolean {
        return settle {
            channel.basicNack(deliveryTag, false, requeue)
        }
    }

    suspend fun reject(requeue: Boolean = true): Boolean {
        return settle {
            channel.basicReject(deliveryTag, requeue)
        }
    }

    suspend fun nackIfUnsettled(requeue: Boolean): Boolean {
        return nack(requeue)
    }

    private suspend fun settle(operation: () -> Unit): Boolean {
        if (!enabled) {
            return true
        }

        if (!settlementClaimed.compareAndSet(false, true)) {
            return false
        }

        return try {
            withContext(NonCancellable + channelDispatcher) {
                operation()
            }

            true
        } catch (cause: Throwable) {
            if (
                cause is IOException ||
                cause is ShutdownSignalException ||
                !channel.isOpen
            ) {
                false
            } else {
                throw cause
            }
        }
    }
}