package dev.slne.surf.eventbus.redis.codec.default

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.redis.codec.AbstractCodec
import io.netty.buffer.ByteBuf

@InternalEventBusApi
object DoubleBinaryCodec : AbstractCodec<Double>() {

    override fun write(buf: ByteBuf, value: Double) {
        buf.writeDouble(value)
    }

    override fun read(buf: ByteBuf): Double {
        return buf.readDouble()
    }
}