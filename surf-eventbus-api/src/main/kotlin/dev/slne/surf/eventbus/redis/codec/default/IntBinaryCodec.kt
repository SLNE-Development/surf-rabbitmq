package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import io.netty.buffer.ByteBuf

@InternalEventBusApi
object IntBinaryCodec : AbstractCodec<Int>() {

    override fun write(buf: ByteBuf, value: Int) {
        buf.writeInt(value)
    }

    override fun read(buf: ByteBuf): Int {
        return buf.readInt()
    }
}