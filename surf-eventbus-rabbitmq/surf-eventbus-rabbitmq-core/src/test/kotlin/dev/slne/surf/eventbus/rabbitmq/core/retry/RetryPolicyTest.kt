package dev.slne.surf.eventbus.rabbitmq.core.retry

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RetryPolicyTest {

    private fun attemptsHeader(count: Any?): Map<String, Any?> =
        mapOf(RetryPolicy.ATTEMPTS_HEADER to count)

    @Test
    fun `a first delivery has no attempts`() {
        assertEquals(0, RetryPolicy.attemptsFrom(null))
        assertEquals(0, RetryPolicy.attemptsFrom(emptyMap()))
    }

    @Test
    fun `the attempt count comes from the custom header`() {
        assertEquals(1, RetryPolicy.attemptsFrom(attemptsHeader(1)))
        assertEquals(3, RetryPolicy.attemptsFrom(attemptsHeader(3)))
    }

    @Test
    fun `a malformed attempt header is treated as no attempts`() {
        // Better to retry a message once too often than to crash the consumer on a header.
        assertEquals(0, RetryPolicy.attemptsFrom(attemptsHeader("nonsense")))
        assertEquals(0, RetryPolicy.attemptsFrom(attemptsHeader(null)))
    }

    @Test
    fun `the ladder climbs ten seconds, one minute, five minutes`() {
        assertEquals(RetryDecision.Retry(RetryTier.TEN_SECONDS), RetryPolicy.decide(0, true))
        assertEquals(RetryDecision.Retry(RetryTier.ONE_MINUTE), RetryPolicy.decide(1, true))
        assertEquals(RetryDecision.Retry(RetryTier.FIVE_MINUTES), RetryPolicy.decide(2, true))
    }

    @Test
    fun `the fourth failure dead-letters`() {
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(3, true))
    }

    @Test
    fun `attempts beyond the maximum still dead-letter`() {
        // Defensive: a message must never loop, whatever its header claims.
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(99, true))
    }

    @Test
    fun `disabling retry dead-letters on the first failure`() {
        assertEquals(RetryDecision.DeadLetter, RetryPolicy.decide(0, false))
    }

    @Test
    fun `the ladder allows exactly four deliveries`() {
        val retries = (0..RetryPolicy.MAX_RETRIES)
            .map { RetryPolicy.decide(it, true) }
            .count { it is RetryDecision.Retry }

        assertEquals(3, retries, "three retries plus the first delivery is four in total")
    }

    @Test
    fun `tier queue names match the specification`() {
        assertEquals("surf.retry.10s", RetryTier.TEN_SECONDS.queueName)
        assertEquals("surf.retry.60s", RetryTier.ONE_MINUTE.queueName)
        assertEquals("surf.retry.300s", RetryTier.FIVE_MINUTES.queueName)
    }

    @Test
    fun `the default tier ttls match the specification`() {
        // The config interface default is what production runs on; tests override it.
        val config = object : dev.slne.surf.eventbus.rabbitmq.api.internal.config.CommonRabbitMQConfig {
            override fun getHost() = ""
            override fun getPort() = 0
            override fun getUsername() = ""
            override fun getPassword() = ""
            override fun getVhost() = ""
            override fun getTimeout() = 0
            override fun getRequestTimeoutSeconds() = 0
            override fun getPublisherPoolSize() = 0
            override fun getServerPrefetchCount() = 0
            override fun isPersistRequests() = false
            override fun isPersistResponses() = false
            override fun isOutgoingRequestChunkingEnabled() = false
            override fun isOutgoingResponseChunkingEnabled() = false
        }

        assertEquals(listOf(10_000L, 60_000L, 300_000L), config.getRetryTtlMillis())
    }
}
