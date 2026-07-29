package dev.slne.surf.rabbitmq.core.rpc

import dev.slne.surf.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.rabbitmq.api.rpc.RpcService
import dev.slne.surf.rabbitmq.common.testing.RabbitBrokerExtension
import dev.slne.surf.rabbitmq.common.testing.RequiresDocker
import dev.slne.surf.rabbitmq.common.testing.testConfig
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
            val proxy = client.rpc<EchoRpcService>(service = service)

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
}
