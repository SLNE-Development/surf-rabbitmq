package dev.slne.surf.eventbus.redis.credentials

import dev.slne.surf.eventbus.credentials.RedisCredentials
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A password belongs after the colon, not before the at-sign.
 *
 * `redis://secret@host` names a *user* called "secret" with no password. Redisson parses it
 * that way, the broker rejects the auth, and the failure reads like a wrong password rather
 * than a malformed URI.
 *
 * Asserted on the parsed fields rather than on `toString()`, which redacts the credentials to
 * `***` — the redaction is why this bug could sit unnoticed in a log line.
 */
class RedisUriTest {

    @Test
    fun `a password lands in the password slot, not the username slot`() {
        val uri = redisUriOf(RedisCredentials(host = "redis-1", port = 6380, password = "s3cret"))

        assertEquals("s3cret", uri.password, "the password must be readable as the password")
        assertNull(uri.username, "a bare password must not become a username")
        assertEquals("redis-1", uri.host)
        assertEquals(6380, uri.port)
    }

    @Test
    fun `no password means no credentials section`() {
        val uri = redisUriOf(RedisCredentials(host = "redis-1", port = 6380, password = null))

        assertEquals("redis://redis-1:6380", uri.toString())
        assertNull(uri.password)
        assertNull(uri.username)
    }

    @Test
    fun `a password with reserved characters survives the round trip`() {
        val uri = redisUriOf(RedisCredentials(host = "h", port = 1, password = "a@b/c"))

        // Percent-encoded on the way in, decoded on the way out: an unencoded '@' would end
        // the credentials section early and make the rest of the password part of the host.
        assertEquals("a@b/c", uri.password)
        assertEquals("h", uri.host)
        assertEquals(1, uri.port)
    }
}
