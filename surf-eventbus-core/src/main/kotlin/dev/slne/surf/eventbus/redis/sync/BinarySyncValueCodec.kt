package dev.slne.surf.eventbus.redis.sync

import dev.slne.surf.eventbus.redis.codec.RedisCodec
import dev.slne.surf.eventbus.redis.codec.RedisCodecException
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import java.util.*

internal class BinarySyncValueCodec<T : Any>(
    private val codec: RedisCodec<T>,
    private val context: String,
) : SyncValueCodec<T> {
    override val descriptor: String = codecDescriptor(codec)

    override fun encode(value: T): String {
        val raw = Unpooled.buffer(DEFAULT_INITIAL_CAPACITY)
        try {
            codec.encode(raw, value)
            val size = raw.readableBytes()
            if (size > MAX_BINARY_VALUE_SIZE) {
                throw RedisCodecException(
                    "Codec '${codec.codecId}' produced $size bytes for $context; maximum is $MAX_BINARY_VALUE_SIZE",
                )
            }

            val bytes = ByteBufUtil.getBytes(raw, raw.readerIndex(), size, false)
            return BASE64_ENCODER.encodeToString(bytes)
        } catch (e: RedisCodecException) {
            throw e
        } catch (e: Exception) {
            throw RedisCodecException(
                "Codec '${codec.codecId}' failed to encode a value for $context",
                e,
            )
        } finally {
            raw.release()
        }
    }

    override fun decode(value: String): T {
        if (value.length > MAX_BASE64_VALUE_SIZE) {
            throw RedisCodecException(
                "Encoded value for $context is ${value.length} characters; maximum is $MAX_BASE64_VALUE_SIZE",
            )
        }

        val bytes =
            try {
                BASE64_DECODER.decode(value)
            } catch (e: IllegalArgumentException) {
                throw RedisCodecException(
                    "Invalid binary value for $context using codec '${codec.codecId}'",
                    e,
                )
            }
        val decoded = Unpooled.wrappedBuffer(bytes)
        try {
            val result = codec.decode(decoded)
            if (decoded.isReadable) {
                throw RedisCodecException(
                    "Codec '${codec.codecId}' left ${decoded.readableBytes()} unread bytes while decoding $context",
                )
            }
            return result
        } catch (e: RedisCodecException) {
            throw e
        } catch (e: Exception) {
            throw RedisCodecException(
                "Codec '${codec.codecId}' failed to decode a value for $context",
                e,
            )
        } finally {
            decoded.release()
        }
    }

    private companion object {
        private const val DEFAULT_INITIAL_CAPACITY = 64
        private const val MAX_BINARY_VALUE_SIZE = 16 * 1024 * 1024
        private const val MAX_BASE64_VALUE_SIZE = (MAX_BINARY_VALUE_SIZE * 4 / 3) + 4
        private val BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val BASE64_DECODER = Base64.getUrlDecoder()
    }
}
