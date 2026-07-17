package dev.slne.surf.rabbitmq.common.connection.client

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.RecoveryDelayHandler
import dev.slne.surf.api.core.util.logger
import dev.slne.surf.rabbitmq.api.exception.SurfRabbitConnectionClosedException
import dev.slne.surf.rabbitmq.api.internal.config.CommonRabbitMQConfig
import dev.slne.surf.rabbitmq.common.connection.RabbitConnectionProvider
import dev.slne.surf.rabbitmq.common.connection.consumer.RabbitConsumer
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherOptions
import dev.slne.surf.rabbitmq.common.connection.publisher.RabbitPublisherPool
import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
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
import java.time.Instant
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
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
        private val sharedLifecycleLock = Any()
        private val sharedResourcesClosed = AtomicBoolean()

        init {

            log.atInfo()
                .log("Using ${transport.name} for RabbitMQ client")

            val nettyThreadFactory = Thread.ofPlatform()
                .name("rabbitmq-netty-thread-", 0)
                .daemon()
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
            sharedConsumerExecutor = ThreadPoolExecutor(
                16,
                16,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(4_096),
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
                    .factory(),
                ThreadPoolExecutor.CallerRunsPolicy()
            )
        }

        fun create(
            config: CommonRabbitMQConfig,
            connectionName: String,
            publisherOptions: RabbitPublisherOptions = RabbitPublisherOptions()
        ): RabbitClient {
            val timeoutSeconds = config.getTimeout()
            require(timeoutSeconds > 0) { "RabbitMQ connection timeout must be greater than 0" }

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
                connectionTimeout = timeoutSeconds.seconds.inWholeMilliseconds
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()

                setSharedExecutor(sharedConsumerExecutor)
                netty().eventLoopGroup(sharedEventLoopGroup)
                netty().bootstrapCustomizer { bootstrap ->
                    bootstrap.channel(transport.channelClass)
                }
            }

            synchronized(sharedLifecycleLock) {
                if (sharedResourcesClosed.get()) {
                    throw SurfRabbitConnectionClosedException("create RabbitMQ client after shared-resource shutdown")
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
        }

        @Blocking
        fun closeSharedResources() {
            val stillActive = synchronized(sharedLifecycleLock) {
                if (!sharedResourcesClosed.compareAndSet(false, true)) return
                activeClients.entries.map { it.key to it.value }
            }

            if (stillActive.isNotEmpty()) {
                log.atWarning()
                    .log(
                        "RabbitMQ shared resources are being shut down while %s RabbitClient(s) are still active. " +
                                "These plugins probably did not call RabbitMQApi.disconnect(): %s",
                        stillActive.size,
                        stillActive.joinToString { it.second.connectionName }
                    )

                stillActive.forEach { (client, info) ->
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

                    try {
                        client.close()
                    } catch (throwable: Throwable) {
                        throwable.rethrowIfFatal()
                        log.atSevere()
                            .withCause(throwable)
                            .log("Failed to close leaked RabbitClient '${info.connectionName}'")
                    }
                }
            }

            sharedConsumerExecutor.shutdown()
            var interrupted = false
            val terminatedGracefully = try {
                sharedConsumerExecutor.awaitTermination(10, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!terminatedGracefully) {
                sharedConsumerExecutor.shutdownNow()
                val terminatedAfterInterrupt = try {
                    sharedConsumerExecutor.awaitTermination(10, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
                if (!terminatedAfterInterrupt) {
                    log.atWarning().log("RabbitMQ consumer executor did not terminate after forced shutdown")
                }
            }

            val shutdownFuture = sharedEventLoopGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS)
            if (!shutdownFuture.awaitUninterruptibly(15, TimeUnit.SECONDS)) {
                log.atWarning().log("RabbitMQ Netty event loop did not terminate within 15 seconds")
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    suspend fun publish(
        exchange: String,
        routingKey: String,
        body: ByteArray,
        properties: AMQP.BasicProperties? = null,
        mandatory: Boolean = false
    ) {
        if (closed.get()) throw SurfRabbitConnectionClosedException("publish")
        publisherPool.publish(
            exchange = exchange,
            routingKey = routingKey,
            body = body,
            properties = properties,
            mandatory = mandatory
        )
    }

    fun newConsumer(name: String): RabbitConsumer {
        if (closed.get()) throw SurfRabbitConnectionClosedException("create consumer")
        val consumer = RabbitConsumer(
            connectionProvider = connectionProvider,
            name = name
        )
        consumers.add(consumer)

        if (closed.get()) {
            consumers.remove(consumer)
            consumer.close()
            throw SurfRabbitConnectionClosedException("create consumer")
        }

        return consumer
    }

    internal fun discardConsumer(consumer: RabbitConsumer) {
        consumers.remove(consumer)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        fun closeResource(block: () -> Unit) {
            try {
                block()
            } catch (throwable: Throwable) {
                throwable.rethrowIfFatal()
                val previous = failure
                if (previous == null) {
                    failure = throwable
                } else {
                    previous.addSuppressed(throwable)
                }
            }
        }

        try {
            consumers.forEach { consumer ->
                closeResource(consumer::close)
            }
            consumers.clear()

            closeResource(publisherPool::close)
            closeResource(connectionProvider::close)
        } finally {
            activeClients.remove(this)
        }

        failure?.let { throw it }
    }

    private val closed = AtomicBoolean()
}
