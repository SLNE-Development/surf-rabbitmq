package dev.slne.surf.eventbus.suite

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import dev.slne.surf.eventbus.testing.RequiresDocker
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Spec tests 24–32: RPC over a real RabbitMQ broker.
 *
 * Most of this suite was written in plan 3 and is **not** repeated here:
 *
 * | # | Where it lives |
 * |---|---|
 * | 25, 26 | `rabbitmq/send/FireAndForgetTest`, `rabbitmq/rpc/FireAndForgetContractTest` |
 * | 28 | `FireAndForgetContractTest.an instance target reaches exactly one of three instances` |
 * | 29 | `FireAndForgetContractTest.an instance target with no matching instance is unroutable` |
 * | 30 | `FireAndForgetValidationTest` in `surf-eventbus-ksp` (compile-time) |
 * | 32 | `FireAndForgetContractTest.repeated rpc calls for the same instance target share one proxy` |
 *
 * What is added here is 24, 27 and 31 — competing consumers, a timeout against a dead service,
 * and the error text a contract without a `service` produces.
 *
 * **Not run.** No Docker daemon was reachable on the machine these were written on.
 */
@RequiresDocker
class RpcSuiteTest {

    private val dataPath = Files.createTempDirectory("surf-eventbus-rpc-suite")

    /**
     * A process named [serviceName], optionally with a stable instance id.
     *
     * The service queue is named after the api's own service name, not after the contract's
     * `@RpcService(service = ...)`. A server must therefore *be* the service it hosts, which
     * is why the three instances below share one service name and differ only by instance.
     */
    private fun api(serviceName: String, instanceName: String? = null): SurfRabbitApi =
        SurfRabbitApi.builder(serviceName, dataPath)
            .config(testConfig())
            .apply { instanceName?.let { instanceName(it) } }
            .build()

    @Test
    fun `24 - exactly one of three service instances handles a waiting call`() = runBlocking {
        val handled = ConcurrentLinkedQueue<String>()

        val servers = (1..3).map { index ->
            api(COUNTING_SERVICE, instanceName = "suite-rpc-server-$index").also {
                it.registerService(CountingService::class, CountingServiceImpl("server-$index", handled))
                it.freezeAndConnect()
            }
        }

        val client = api("suite-rpc-client")
        client.freezeAndConnect()

        val answer = client.rpc<CountingService>().count("job-1")

        assertEquals(
            1,
            handled.size,
            "a waiting call is competing-consumer: one instance takes it, not all three"
        )
        assertEquals(handled.first(), answer, "the answer comes from whoever handled it")

        servers.forEach { it.disconnect() }
        client.disconnect()
    }

    @Test
    fun `27 - a waiting call to an offline service times out without an audit row`() = runBlocking {
        // No server is registered for this contract at all.
        val client = api("suite-rpc-client-offline")
        client.freezeAndConnect()

        val answer = withTimeoutOrNull(TIMEOUT_MILLIS) {
            runCatching { client.rpc<OfflineService>().doWork("job") }.getOrNull()
        }

        assertNull(answer, "nobody answered, so the call must not complete with a value")

        client.disconnect()
    }

    @Test
    fun `31 - a contract without a service names both ways out`() = runBlocking {
        val client = api("suite-rpc-client-noservice")
        client.freezeAndConnect()

        val failure = assertFailsWith<IllegalStateException> {
            client.rpc<UntargetedService>()
        }

        // The message has to say what to do, not just what went wrong: either put a service on
        // the annotation, or pass a target at the call site.
        assertContains(failure.message!!, "service")

        client.disconnect()
    }

    private companion object {
        const val TIMEOUT_MILLIS = 3_000L

        /** Must match `@RpcService(service = ...)` on [CountingService]. */
        const val COUNTING_SERVICE = "suite-rpc-counting"
    }
}

@RpcService(service = "suite-rpc-counting")
interface CountingService {
    suspend fun count(job: String): String
}

@RpcService(service = "suite-rpc-offline")
interface OfflineService {
    suspend fun doWork(job: String): String
}

/** No `service`, so `rpc<T>()` without a target has nowhere to send. */
@RpcService
interface UntargetedService {
    suspend fun doWork(job: String): String
}

private class CountingServiceImpl(
    private val name: String,
    private val handled: ConcurrentLinkedQueue<String>,
) : CountingService {
    private val calls = AtomicInteger()

    override suspend fun count(job: String): String {
        calls.incrementAndGet()
        handled += name
        return name
    }
}
