package dev.slne.surf.eventbus.redis

import dev.slne.surf.api.core.util.logger
import io.netty.channel.MultiThreadIoEventLoopGroup
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Netty event loop, the Redisson executor and the two schedulers Redis needs.
 *
 * These used to hang off `RedisInstance`, the platform SPI — so implementing "which directory
 * is my data folder in" also meant inheriting a thread pool. A platform describes a platform;
 * runtime objects belong where the behaviour is. One instance per process, built by
 * `RedisComponentProviderImpl`.
 */
class RedisRuntime {

    val eventLoopGroup: MultiThreadIoEventLoopGroup
    val redissonExecutorService: ExecutorService

    init {
        val contextClassLoader = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = this.javaClass.classLoader
            val nettyThreadFactory = Thread.ofPlatform()
                .name("redisson-netty-thread-", 0)
                .uncaughtExceptionHandler { thread, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log(
                            "Uncaught exception in Redisson Netty thread (%s): %s",
                            thread.name,
                            throwable
                        )
                }
                .factory()

            val redissonThreadFactory = Thread.ofVirtual()
                .name("redisson-virtual-thread-executor-", 0)
                .uncaughtExceptionHandler { thread, throwable ->
                    log.atSevere()
                        .withCause(throwable)
                        .log(
                            "Uncaught exception in Redisson virtual thread executor (%s): %s",
                            thread.name,
                            throwable
                        )
                }
                .factory()

            eventLoopGroup =
                MultiThreadIoEventLoopGroup(16, nettyThreadFactory, TransportInfo.instance.ioHandlerFactory)
            redissonExecutorService = Executors.newThreadPerTaskExecutor(redissonThreadFactory)
        } finally {
            Thread.currentThread().contextClassLoader = contextClassLoader
        }
    }

    val streamPollScheduler: Scheduler = Schedulers.newBoundedElastic(
        8,
        Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
        "surf-redis-stream-poll",
        60,
        true
    )

    val ttlRefreshScheduler: Scheduler = Schedulers.newParallel("surf-redis-ttl-refresh", 2)

    fun load() {
        log.atInfo()
            .log(
                "Enabling Redis networking using %s transport with Redisson %s",
                TransportInfo.instance.transportString,
                RedisConstants.REDISSON_VERSION
            )
    }

    fun disable() {
        log.atInfo().log("Disabling Redis networking")

        streamPollScheduler.dispose()
        ttlRefreshScheduler.dispose()
        eventLoopGroup.shutdownGracefully().syncUninterruptibly()

        redissonExecutorService.shutdown()
        if (!redissonExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
            redissonExecutorService.shutdownNow()
        }
    }

    companion object {
        private val log = logger()

        /**
         * One per process, created on first use.
         *
         * Lazily rather than eagerly because a process that only speaks RabbitMQ must not pay
         * for a Netty event loop it never uses — which is what inheriting these from the
         * platform SPI used to cost it.
         */
        val instance: RedisRuntime by lazy { RedisRuntime() }
    }
}
