package dev.slne.surf.rabbitmq.common.connection.client

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.RecoveryDelayHandler
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.internal.RabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitConsumer
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherOptions
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherPool
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.uring.IoUring
import io.netty.channel.uring.IoUringIoHandler
import java.lang.AutoCloseable
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.seconds

class RabbitClient private constructor(
    private val connectionProvider: RabbitConnectionProvider,
    private val publisherPool: RabbitPublisherPool
) : AutoCloseable {
    private val consumers = ConcurrentLinkedQueue<RabbitConsumer>()

    companion object {
        private val log = logger()
        private val sharedEventLoopGroup: MultiThreadIoEventLoopGroup

        init {
            val (ioHandlerFactory, networkingString) = when {
                IoUring.isAvailable() -> IoUringIoHandler.newFactory() to "IoUring"
                Epoll.isAvailable() -> EpollIoHandler.newFactory() to "Epoll"
                KQueue.isAvailable() -> KQueueIoHandler.newFactory() to "KQueue"
                else -> NioIoHandler.newFactory() to "NIO"
            }

            log.atInfo()
                .log("Using $networkingString for RabbitMQ client")

            val nettyThreadFactory = Thread.ofPlatform()
                .name("rabbitmq-netty-thread-", 0)
                .uncaughtExceptionHandler { thread, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log(
                            "Uncaught exception in RabbitMQ Netty thread (%s): %s",
                            thread.name,
                            throwable
                        )
                }
                .factory()

            sharedEventLoopGroup = MultiThreadIoEventLoopGroup(8, nettyThreadFactory, ioHandlerFactory)
        }

        fun create(
            config: RabbitMQConfig,
            connectionName: String,
            publisherOptions: RabbitPublisherOptions = RabbitPublisherOptions()
        ): RabbitClient {
            val connectionFactory = ConnectionFactory().apply {
                host = config.host
                port = config.port
                username = config.username
                password = config.password
                virtualHost = config.vhost

                isAutomaticRecoveryEnabled = true
                isTopologyRecoveryEnabled = true
                recoveryDelayHandler = RecoveryDelayHandler.ExponentialBackoffDelayHandler()

                requestedHeartbeat = 60
                connectionTimeout = config.timeout.seconds.inWholeMilliseconds.toInt()

                netty().eventLoopGroup(sharedEventLoopGroup)
            }

            val connectionProvider = RabbitConnectionProvider(
                factory = connectionFactory,
                connectionName = connectionName
            )

            val publisherPool = RabbitPublisherPool(
                connectionProvider = connectionProvider,
                size = config.publisherPoolSize(),
                options = publisherOptions
            )

            return RabbitClient(
                connectionProvider = connectionProvider,
                publisherPool = publisherPool
            )
        }

        fun closeEventLoopGroup() {
            sharedEventLoopGroup.shutdownGracefully()
        }
    }

    suspend fun publish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        properties: AMQP.BasicProperties? = null,
        mandatory: Boolean = false
    ) {
        publisherPool.publish(
            exchange = exchange,
            routingKey = routingKey,
            body = body,
            properties = properties,
            mandatory = mandatory
        )
    }

    fun newConsumer(name: String): RabbitConsumer {
        val consumer = RabbitConsumer(
            connectionProvider = connectionProvider,
            name = name
        )
        consumers.add(consumer)

        return consumer
    }

    override fun close() {
        consumers.forEach { consumer ->
            runCatching {
                consumer.close()
            }
        }

        runCatching {
            publisherPool.close()
        }

        runCatching {
            connectionProvider.close()
        }
    }
}