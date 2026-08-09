package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import dev.slne.surf.eventbus.redis.codec.readVarInt
import dev.slne.surf.eventbus.redis.codec.writeVarInt
import io.netty.buffer.ByteBuf

@InternalEventBusApi
object VarIntBinaryCodec : AbstractCodec<Int>() {

    override fun write(buf: ByteBuf, value: Int) {
        buf.writeVarInt(value)
    }

    override fun read(buf: ByteBuf): Int {
        return buf.readVarInt()
    }
}