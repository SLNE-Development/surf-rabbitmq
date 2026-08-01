package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.FireAndForget
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import dev.slne.surf.eventbus.rabbitmq.exception.SurfRabbitServiceUnavailableException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RpcService
interface WorkService {
    @FireAndForget
    suspend fun doWork(id: String)
}

/**
 * Covers the RPC-suite fire-and-forget cases: the caller returns before processing finishes, an
 * `InstanceTarget` reaches exactly one of several instances, an `InstanceTarget` for a dead
 * instance is unroutable, and repeated `rpc<T>(InstanceTarget(x))` calls share one proxy.
 */
@RequiresDocker
class FireAndForgetContractTest {

    private val dataPath = Files.createTempDirectory("fnf-test")

    @Test
    fun `the caller returns before the handler finishes`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf-slow")
        val handlerStarted = CompletableDeferred<Unit>()
        val handlerMayFinish = CompletableDeferred<Unit>()

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<WorkService>(object : WorkService {
            override suspend fun doWork(id: String) {
                handlerStarted.complete(Unit)
                withTimeout(5.seconds) { handlerMayFinish.await() }
            }
        })
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller-slow", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<WorkService>(RabbitTarget.ServiceTarget(service))

            withTimeout(5.seconds) { proxy.doWork("job-1") }
            // The call above returned; the handler must still be mid-flight, held open by
            // handlerMayFinish. If call() had waited for the handler, this deferred would
            // already be complete.
            assertTrue(!handlerMayFinish.isCompleted)

            withTimeout(5.seconds) { handlerStarted.await() }
            handlerMayFinish.complete(Unit)
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `an instance target reaches exactly one of three instances`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("fnf-instances")
        val received = CompletableDeferred<String>()

        val servers = (1..3).map { index ->
            val instanceId = "$service-instance-$index"
            val server = SurfRabbitApi.builder(service, dataPath)
                .config(testConfig())
                .instanceName(instanceId)
                .build()
            server.registerService<WorkService>(object : WorkService {
                override suspend fun doWork(id: String) {
                    check(received.complete("$instanceId:$id")) {
                        "more than one instance handled the same fire-and-forget call"
                    }
                }
            })
            server.freezeAndConnect()
            instanceId to server
        }

        val client = SurfRabbitApi.builder("caller-instances", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val targetInstance = servers[1].first
            val proxy = client.rpc<WorkService>(RabbitTarget.InstanceTarget(targetInstance))
            proxy.doWork("job-2")

            val result = withTimeout(5.seconds) { received.await() }
            assertEquals("$targetInstance:job-2", result)
        } finally {
            client.disconnect()
            servers.forEach { it.second.disconnect() }
        }
    }

    @Test
    fun `an instance target with no matching instance is unroutable`() = runBlocking {
        val client = SurfRabbitApi.builder("caller-dead", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<WorkService>(RabbitTarget.InstanceTarget("no-such-instance"))

            assertFailsWith<SurfRabbitServiceUnavailableException> {
                withTimeout(5.seconds) { proxy.doWork("job-3") }
            }
        } finally {
            client.disconnect()
        }
    }

    @Test
    fun `repeated rpc calls for the same instance target share one proxy`() = runBlocking {
        val client = SurfRabbitApi.builder("caller-cache", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val target = RabbitTarget.InstanceTarget("some-instance")
            val first = client.rpc<WorkService>(target)
            val second = client.rpc<WorkService>(target)

            assertSame(first, second, "the proxy cache must key on (interface, target)")
        } finally {
            client.disconnect()
        }
    }
}
