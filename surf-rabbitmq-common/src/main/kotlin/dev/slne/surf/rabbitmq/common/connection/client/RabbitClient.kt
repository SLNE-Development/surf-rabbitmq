package dev.slne.surf.rabbitmq.common.connection.client

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.RecoveryDelayHandler
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitConsumer
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherOptions
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherPool
import io.netty.channel.Channel
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueSocketChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.uring.IoUring
import io.netty.channel.uring.IoUringIoHandler
import io.netty.channel.uring.IoUringSocketChannel
import org.jetbrains.annotations.Blocking
import java.lang.AutoCloseable
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*
import kotlin.time.Duration.Companion.seconds

class RabbitClient private constructor(
    private val connectionProvider: RabbitConnectionProvider,
    private val publisherPool: RabbitPublisherPool
) : AutoCloseable {
    private val consumers = ConcurrentLinkedQueue<RabbitConsumer>()

    companion object {
        private data class NettyTransport(
            val ioHandlerFactory: IoHandlerFactory,
            val channelClass: Class<out Channel>,
            val name: String
        )

        private data class ActiveClientInfo(
            val connectionName: String,
            val createdAtMillis: Long,
            val creationThread: String,
            val creationStackTrace: List<String>
        )

        private val log = logger()

        private val transport: NettyTransport = when {
            IoUring.isAvailable() -> NettyTransport(
                ioHandlerFactory = IoUringIoHandler.newFactory(),
                channelClass = IoUringSocketChannel::class.java,
                name = "IoUring"
            )

            Epoll.isAvailable() -> NettyTransport(
                ioHandlerFactory = EpollIoHandler.newFactory(),
                channelClass = EpollSocketChannel::class.java,
                name = "Epoll"
            )

            KQueue.isAvailable() -> NettyTransport(
                ioHandlerFactory = KQueueIoHandler.newFactory(),
                channelClass = KQueueSocketChannel::class.java,
                name = "KQueue"
            )

            else -> NettyTransport(
                ioHandlerFactory = NioIoHandler.newFactory(),
                channelClass = NioSocketChannel::class.java,
                name = "NIO"
            )
        }

        private val sharedEventLoopGroup: MultiThreadIoEventLoopGroup
        private val sharedConsumerExecutor: ExecutorService
        private val activeClients = ConcurrentHashMap<RabbitClient, ActiveClientInfo>()

        init {

            log.atInfo()
                .log("Using ${transport.name} for RabbitMQ client")

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

            sharedEventLoopGroup = MultiThreadIoEventLoopGroup(8, nettyThreadFactory, transport.ioHandlerFactory)
            sharedConsumerExecutor = Executors.newFixedThreadPool(
                16,
                Thread.ofPlatform()
                    .name("rabbitmq-consumer-thread-", 0)
                    .uncaughtExceptionHandler { thread, throwable ->
                        log.atSevere()
                            .withCause(throwable)
                            .log(
                                "Uncaught exception in RabbitMQ consumer thread (%s): %s",
                                thread.name,
                                throwable
                            )
                    }
                    .daemon()
                    .factory()
            )
        }

        fun create(
            config: CommonRabbitMQConfig,
            connectionName: String,
            publisherOptions: RabbitPublisherOptions = RabbitPublisherOptions()
        ): RabbitClient {
            val connectionFactory = ConnectionFactory().apply {
                host = config.getHost()
                port = config.getPort()
                username = config.getUsername()
                password = config.getPassword()
                virtualHost = config.getVhost()

                isAutomaticRecoveryEnabled = true
                isTopologyRecoveryEnabled = true
                recoveryDelayHandler = RecoveryDelayHandler.ExponentialBackoffDelayHandler()

                requestedHeartbeat = 60
                connectionTimeout = config.getTimeout().seconds.inWholeMilliseconds.toInt()

                setSharedExecutor(sharedConsumerExecutor)
                netty().eventLoopGroup(sharedEventLoopGroup)
                netty().bootstrapCustomizer { bootstrap ->
                    bootstrap.channel(transport.channelClass)
                }
            }

            val connectionProvider = RabbitConnectionProvider(
                factory = connectionFactory,
                connectionName = connectionName
            )

            val publisherPool = RabbitPublisherPool(
                connectionProvider = connectionProvider,
                size = config.getPublisherPoolSize(),
                options = publisherOptions
            )

            val client = RabbitClient(
                connectionProvider = connectionProvider,
                publisherPool = publisherPool
            )

            activeClients[client] = ActiveClientInfo(
                connectionName = connectionName,
                createdAtMillis = System.currentTimeMillis(),
                creationThread = Thread.currentThread().name,
                creationStackTrace = Throwable().stackTrace
                    .drop(1)
                    .take(12)
                    .map { it.toString() }
            )

            return client
        }

        @Blocking
        fun closeSharedResources() {
            val stillActive = activeClients.values.toList()

            if (stillActive.isNotEmpty()) {
                log.atWarning()
                    .log(
                        "RabbitMQ shared resources are being shut down while %s RabbitClient(s) are still active. " +
                                "These plugins probably did not call RabbitMQApi.disconnect(): %s",
                        stillActive.size,
                        stillActive.joinToString { it.connectionName }
                    )

                log.atWarning()
                    .log(
                        "Any RabbitMQ connection recovery errors that appear after this message are expected follow-up " +
                                "errors caused by shutting down shared RabbitMQ resources while RabbitMQ clients are " +
                                "still active. Fix the plugins listed above by calling RabbitMQApi.disconnect() during shutdown."
                    )

                Thread.sleep(Duration.ofSeconds(10))

                stillActive.forEach { info ->
                    log.atWarning()
                        .log(
                            """
                                Leaked RabbitClient:
                                connectionName: ${info.connectionName}
                                createdAt: ${Instant.ofEpochMilli(info.createdAtMillis)}
                                creationThread: ${info.creationThread}
                                creationStackTrace:
                                ${info.creationStackTrace.joinToString(separator = "\n") { "    at $it" }}
                                """.trimIndent()
                        )
                }
            }

            sharedConsumerExecutor.shutdown()
            if (!sharedConsumerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                sharedConsumerExecutor.shutdownNow()
            }
            sharedEventLoopGroup.shutdownGracefully().syncUninterruptibly()
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
        try {
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
        } finally {
            activeClients.remove(this)
        }
    }
}
