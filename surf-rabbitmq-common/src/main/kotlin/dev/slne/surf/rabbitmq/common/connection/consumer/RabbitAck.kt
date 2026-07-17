package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    private val settlementMutex = Mutex()
    private var settled = false

    suspend fun ack() {
        if (!enabled) return

        settle {
            channel.basicAck(deliveryTag, false)
        }
    }

    suspend fun nack(requeue: Boolean = true) {
        if (!enabled) return

        settle {
            channel.basicNack(deliveryTag, false, requeue)
        }
    }

    suspend fun reject(requeue: Boolean = true) {
        if (!enabled) return

        settle {
            channel.basicReject(deliveryTag, requeue)
        }
    }

    suspend fun nackIfUnsettled(requeue: Boolean) {
        if (!enabled) return

        settle {
            channel.basicNack(deliveryTag, false, requeue)
        }
    }

    private suspend inline fun settle(crossinline operation: () -> Unit) {
        settlementMutex.withLock {
            if (settled) return

            withContext(channelDispatcher) {
                operation()
            }
            settled = true
        }
    }
}
