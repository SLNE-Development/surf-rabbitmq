package dev.slne.surf.rabbitmq.common.connection.consumer

import com.rabbitmq.client.Channel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
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
    private val settled = AtomicBoolean(false)

    suspend fun ack() {
        if (!enabled) return

        if (settled.compareAndSet(false, true)) {
            withContext(channelDispatcher) {
                channel.basicAck(deliveryTag, false)
            }
        }
    }

    suspend fun nack(requeue: Boolean = true) {
        if (!enabled) return

        if (settled.compareAndSet(false, true)) {
            withContext(channelDispatcher) {
                channel.basicNack(deliveryTag, false, requeue)
            }
        }
    }

    suspend fun reject(requeue: Boolean = true) {
        if (!enabled) return

        if (settled.compareAndSet(false, true)) {
            withContext(channelDispatcher) {
                channel.basicReject(deliveryTag, requeue)
            }
        }
    }

    suspend fun nackIfUnsettled(requeue: Boolean) {
        if (!enabled) return

        if (settled.compareAndSet(false, true)) {
            withContext(channelDispatcher) {
                channel.basicNack(deliveryTag, false, requeue)
            }
        }
    }
}