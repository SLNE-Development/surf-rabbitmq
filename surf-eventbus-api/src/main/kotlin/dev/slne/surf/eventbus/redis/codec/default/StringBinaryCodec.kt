package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import dev.slne.surf.eventbus.redis.codec.readString
import dev.slne.surf.eventbus.redis.codec.writeString
import io.netty.buffer.ByteBuf

@InternalEventBusApi
object StringBinaryCodec : AbstractCodec<String>() {

    override fun write(buf: ByteBuf, value: String) {
        buf.writeString(value)
    }

    override fun read(buf: ByteBuf): String {
        return buf.readString()
    }
}