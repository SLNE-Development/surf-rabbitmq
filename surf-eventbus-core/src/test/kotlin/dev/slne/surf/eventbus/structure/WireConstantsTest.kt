package dev.slne.surf.eventbus.structure

import dev.slne.surf.eventbus.rabbitmq.version.RabbitMQVersion
import dev.slne.surf.eventbus.redis.bus.RedisChannels
import dev.slne.surf.eventbus.redis.sync.AbstractSyncStructure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Pins the identifiers that are visible on the wire.
 *
 * These are not ordinary constants. Changing one is a protocol break: two processes that
 * disagree about the AMQP version header read each other as an unknown version, and two that
 * disagree about the sync key prefix silently maintain two disjoint copies of every structure.
 * Neither failure announces itself - which is exactly why they are asserted literally here
 * rather than left to a rename refactor to carry along.
 *
 * If a change here is deliberate, update the literal **and** add a step to
 * `docs/rollout-2.0.md`. If it is not, this test just saved a fleet-wide outage.
 */
class WireConstantsTest {

    @Test
    fun `the AMQP version header is unchanged`() {
        assertEquals("x-surf-eventbus-version", RabbitMQVersion.AMQP_HEADER)
    }

    @Test
    fun `the sync structure key prefix is unchanged`() {
        assertEquals("surf.eventbus.sync:", AbstractSyncStructure.NAMESPACE)
    }

    @Test
    fun `the event channel families are unchanged`() {
        assertEquals("surf.eventbus.json.faction.disbanded", RedisChannels.json("faction.disbanded"))
        assertEquals("surf.eventbus.bin.faction.disbanded", RedisChannels.binary("faction.disbanded"))
        assertEquals("surf.eventbus.json.*", RedisChannels.JSON_PATTERN)
        assertEquals("surf.eventbus.bin.*", RedisChannels.BINARY_PATTERN)
    }

    @Test
    fun `the query and reply channels are unchanged`() {
        assertEquals("surf.eventbus.query.dev.example.Locator", RedisChannels.query("dev.example.Locator"))
        assertEquals("surf.eventbus.reply.lobby-1", RedisChannels.reply("lobby-1"))
    }
}
