package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import dev.slne.surf.eventbus.redis.codec.writeUuid
import io.netty.buffer.ByteBuf
import java.util.*

@InternalEventBusApi
object UUIDBinaryCodec : AbstractCodec<UUID>() {
    override fun write(buf: ByteBuf, value: UUID) {
        buf.writeUuid(value)
    }

    override fun read(buf: ByteBuf): UUID {
        return UUID(buf.readLong(), buf.readLong())
    }
}