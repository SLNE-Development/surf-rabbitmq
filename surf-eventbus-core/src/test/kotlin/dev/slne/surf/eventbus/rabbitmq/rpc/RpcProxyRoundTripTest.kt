package dev.slne.surf.eventbus.rabbitmq.rpc

import dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.rpc.RpcService
import dev.slne.surf.eventbus.rabbitmq.target.RabbitTarget
import dev.slne.surf.eventbus.rabbitmq.testing.RabbitBrokerExtension
import dev.slne.surf.eventbus.rabbitmq.testing.RequiresDocker
import dev.slne.surf.eventbus.rabbitmq.testing.testConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

// The service name is unique per run, so the target is passed at rpc(...) call time;
// the defaultService mechanism is asserted separately below.
@RpcService(service = "proxy-default-target")
interface EchoRpcService {
    suspend fun echo(text: String): String
}

object EchoRpcImpl : EchoRpcService {
    override suspend fun echo(text: String): String = "echo:$text"
}

@RequiresDocker
class RpcProxyRoundTripTest {

    private val dataPath = Files.createTempDirectory("proxy-test")

    @Test
    fun `a generated proxy round-trips through a real broker`() = runBlocking {
        val service = RabbitBrokerExtension.uniqueServiceName("proxy")

        val server = SurfRabbitApi.builder(service, dataPath).config(testConfig()).build()
        server.registerService<EchoRpcService>(EchoRpcImpl)
        server.freezeAndConnect()

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val proxy = client.rpc<EchoRpcService>(target = RabbitTarget.ServiceTarget(service))

            assertEquals(
                "echo:hi", proxy.echo("hi"),
                "this is the full public path: annotation, KSP codegen, proxy, broker, " +
                        "service registration - nothing else in the suite covers it end to end"
            )
        } finally {
            client.disconnect()
            server.disconnect()
        }
    }

    @Test
    fun `the annotation's service lands in the generated descriptor`() {
        val client = SurfRabbitApi.builder("caller", dataPath)
            .config(testConfig())
            .build()

        assertEquals(
            "proxy-default-target",
            client.serviceDescriptorOf<EchoRpcService>().defaultService
        )
    }

    @Test
    fun `one client reaches two different services over one connection`() = runBlocking {
        val serviceA = RabbitBrokerExtension.uniqueServiceName("a")
        val serviceB = RabbitBrokerExtension.uniqueServiceName("b")

        val serverA = SurfRabbitApi.builder(serviceA, dataPath).config(testConfig()).build()
            .also { it.registerService<EchoRpcService>(EchoRpcImpl); it.freezeAndConnect() }
        val serverB = SurfRabbitApi.builder(serviceB, dataPath).config(testConfig()).build()
            .also { it.registerService<EchoRpcService>(EchoRpcImpl); it.freezeAndConnect() }

        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val fromA = client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget(serviceA)).echo("a")
            val fromB = client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget(serviceB)).echo("b")

            assertEquals("echo:a", fromA)
            assertEquals("echo:b", fromB)
            // Reaching two services requires only two proxies over one TCP connection.
        } finally {
            client.disconnect()
            serverA.disconnect()
            serverB.disconnect()
        }
    }

    @Test
    fun `a call to an unknown service fails fast instead of timing out`() = runBlocking {
        val client = SurfRabbitApi.builder("caller", dataPath).config(testConfig()).build()
        client.freezeAndConnect()

        try {
            val start = System.currentTimeMillis()

            val thrown = runCatching {
                client.rpc<EchoRpcService>(RabbitTarget.ServiceTarget("service-that-does-not-exist"))
                    .echo("x")
            }.exceptionOrNull()

            val elapsed = System.currentTimeMillis() - start

            assert(thrown != null) { "an unroutable call must fail" }
            assert(elapsed < 10_000) {
                "expected a fast failure via mandatory + return listener, " +
                        "but it took ${elapsed}ms - it is falling through to the request timeout"
            }
        } finally {
            client.disconnect()
        }
    }
}
