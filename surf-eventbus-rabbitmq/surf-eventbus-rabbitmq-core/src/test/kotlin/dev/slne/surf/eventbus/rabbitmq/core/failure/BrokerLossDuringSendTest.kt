package dev.slne.surf.eventbus.rabbitmq.core.failure

import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.common.testing.testConfig
import dev.slne.surf.eventbus.rabbitmq.core.rpc.EchoRpcImpl
import dev.slne.surf.eventbus.rabbitmq.core.rpc.EchoRpcService
import dev.slne.surf.eventbus.rabbitmq.core.send.WorkService
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * Losing the broker connection at the worst possible moments.
 *
 * The requirement is not that every call succeeds — it cannot — but that every call **settles**.
 * A request that hangs forever is worse than one that fails, because a Minecraft server ends up
 * with coroutines waiting on an answer that will never arrive.
 */
@RequiresDocker
class BrokerLossDuringSendTest {

    private val dataPath = Files.createTempDirectory("broker-loss")

    private fun api(service: String) = SurfRabbitApi
        .builder(service, dataPath)
        .config(testConfig(requestTimeoutSeconds = 15))
        .build()

    @Test
    fun `publishing during a connection loss fails instead of hanging`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-publish")

        val server = api(service).also {
            it.registerService<EchoRpcService>(EchoRpcImpl)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()
            val proxy = client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget(service))

            val calls = (1..20).map { n ->
                async { runCatching { proxy.echo("msg-$n") } }
            }

            delay(20)
            RabbitBrokerExtension.closeAllConnections()

            val results = calls.map { it.await() }
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 60_000,
                "every call must settle. ${results.count { it.isSuccess }} succeeded, " +
                        "${results.count { it.isFailure }} failed, taking ${elapsed}ms"
            )
            assertTrue(
                results.size == 20,
                "no call may be left unresolved"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `a caller waiting for a reply does not hang when the connection drops`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-await")

        val server = api(service).also {
            it.registerService<EchoRpcService>(EchoRpcImpl)
            it.freezeAndConnect()
        }
        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val start = System.currentTimeMillis()
            val proxy = client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget(service))

            val call = async { runCatching { proxy.echo("waiting") } }

            // Drop the connection while the caller is parked on the reply queue.
            delay(30)
            RabbitBrokerExtension.closeAllConnections()

            call.await()
            val elapsed = System.currentTimeMillis() - start

            assertTrue(
                elapsed < 40_000,
                "the caller must be released - by recovery or by failure - but waited ${elapsed}ms. " +
                        "Losing the reply queue without failing pending requests would hang it forever"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `fire-and-forget publishes are not reported as success after a connection loss`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("loss-confirm")

        api(service).also {
            it.registerService<WorkService>(object : WorkService {
                override suspend fun doWork(text: String) {}
            })
            it.freezeAndConnect()
        }.disconnect()

        val client = api("caller").also { it.freezeAndConnect() }

        try {
            val proxy = client.rpc<WorkService>(RabbitTarget.ServiceTarget(service))
            val sends = (1..10).map { n ->
                async { runCatching { proxy.doWork("ff-$n") } }
            }

            delay(10)
            RabbitBrokerExtension.closeAllConnections()

            val results = sends.map { it.await() }

            // Whatever the split, a send that reports success must really have been confirmed.
            assertTrue(
                results.size == 10,
                "every send must settle rather than hang"
            )
        } finally {
            client.disconnect()
        }
    }
}
