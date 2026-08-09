package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import dev.slne.surf.eventbus.redis.codec.readVarLong
import dev.slne.surf.eventbus.redis.codec.writeVarLong
import io.netty.buffer.ByteBuf

@InternalEventBusApi
object VarLongBinaryCodec : AbstractCodec<Long>() {

    override fun write(buf: ByteBuf, value: Long) {
        buf.writeVarLong(value)
    }

    override fun read(buf: ByteBuf): Long {
        return buf.readVarLong()
    }
}