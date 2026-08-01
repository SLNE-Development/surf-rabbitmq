package dev.slne.surf.eventbus.rabbitmq.common.testing

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

@RequiresDocker
class RabbitBrokerExtensionTest {

    @Test
    fun `a connection to the test broker can be opened`() {
        RabbitBrokerExtension.newConnection("scaffold").use { connection ->
            assertTrue(connection.isOpen)

            connection.createChannel().use { channel ->
                assertTrue(channel.isOpen)
            }
        }
    }
}
