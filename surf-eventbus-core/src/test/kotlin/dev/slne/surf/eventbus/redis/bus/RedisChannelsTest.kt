package dev.slne.surf.eventbus.redis.bus

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedisChannelsTest {

    @Test
    fun `channel names carry their family in the prefix`() {
        assertEquals("surf.eventbus.json.faction.disbanded", RedisChannels.json("faction.disbanded"))
        assertEquals("surf.eventbus.bin.faction.disbanded", RedisChannels.binary("faction.disbanded"))
        assertEquals("surf.eventbus.query.dev.example.Locator", RedisChannels.query("dev.example.Locator"))
        assertEquals("surf.eventbus.reply.lobby-3", RedisChannels.reply("lobby-3"))
    }

    @Test
    fun `the json pattern does not catch the binary family`() {
        // Redis glob: * crosses dots. Distinct prefixes are what keep the families apart.
        assertTrue(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.json("a.b")))
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.binary("a.b")))
        assertTrue(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.binary("a.b")))
        assertFalse(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.json("a.b")))
    }

    @Test
    fun `neither pattern catches the query or reply families`() {
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.query("dev.example.Locator")))
        assertFalse(globMatches(RedisChannels.JSON_PATTERN, RedisChannels.reply("lobby-3")))
        assertFalse(globMatches(RedisChannels.BINARY_PATTERN, RedisChannels.query("dev.example.Locator")))
    }

    private fun globMatches(pattern: String, channel: String): Boolean =
        Regex(pattern.replace(".", "\\.").replace("*", ".*")).matches(channel)
}
