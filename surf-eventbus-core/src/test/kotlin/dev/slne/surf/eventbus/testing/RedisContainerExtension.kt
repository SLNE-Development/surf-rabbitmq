package dev.slne.surf.eventbus.testing

import org.redisson.misc.RedisURI
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Redis container shared by every integration test in the JVM.
 *
 * Per-test containers cost more than they buy: Redis has no cross-test state a suite cares
 * about beyond keys, and [uniqueKeyspace] keeps those apart for a fraction of the startup cost.
 * The same trade-off `RabbitBrokerExtension` already makes for the broker.
 *
 * Testcontainers has no Redis module; a `GenericContainer` is all this needs.
 */
object RedisContainerExtension {

    private const val REDIS_PORT = 6379

    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT)
            .also { it.start() }
    }

    private val counter = AtomicInteger()

    fun host(): String = container.host

    fun port(): Int = container.getMappedPort(REDIS_PORT)

    fun uri(): String = "redis://${host()}:${port()}"

    fun redisUri(): RedisURI = RedisURI(uri())

    /** A key prefix no other test in this run uses. */
    fun uniqueKeyspace(name: String): String = "$name-${counter.incrementAndGet()}"
}
