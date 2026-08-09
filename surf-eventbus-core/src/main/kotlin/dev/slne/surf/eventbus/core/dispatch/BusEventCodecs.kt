package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.event.BusEventCodec
import dev.slne.surf.eventbus.event.SurfBusEvent
import io.netty.buffer.Unpooled
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the `BusEventCodec` an event type declares through its companion object.
 *
 * Absence is the normal case and cached as such: without a codec an event travels as JSON.
 */
object BusEventCodecs {

    private val cache = ConcurrentHashMap<Class<*>, Any>()
    private val none = Any()

    fun codecFor(eventClass: Class<out SurfBusEvent>): BusEventCodec<SurfBusEvent>? {
        val cached = cache.computeIfAbsent(eventClass) { type ->
            val companion = try {
                type.getDeclaredField("Companion").also { it.isAccessible = true }.get(null)
            } catch (_: NoSuchFieldException) {
                null
            }

            if (companion is BusEventCodec<*>) companion else none
        }

        @Suppress("UNCHECKED_CAST")
        return if (cached === none) null else cached as BusEventCodec<SurfBusEvent>
    }

    fun decode(eventClass: Class<out SurfBusEvent>, payload: ByteArray): SurfBusEvent {
        val codec = codecFor(eventClass)
            ?: error("${eventClass.name} arrived on the binary channel but declares no BusEventCodec")

        val buffer = Unpooled.wrappedBuffer(payload)
        try {
            return codec.decode(buffer)
        } finally {
            buffer.release()
        }
    }
}
