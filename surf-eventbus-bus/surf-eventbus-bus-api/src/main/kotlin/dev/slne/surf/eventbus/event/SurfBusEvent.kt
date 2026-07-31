package dev.slne.surf.eventbus.event

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.Serializable

/**
 * Base type of every event on the bus.
 *
 * Events are broadcast notifications without persistence: an instance that is offline misses
 * them, and there is no redelivery. Anything that must not be lost is a `@FireAndForget` RPC
 * call, not an event.
 */
@Serializable
abstract class SurfBusEvent {

    /**
     * The instance that published this event, or `null` on an event that has not been published
     * yet.
     *
     * Basis of self-delivery filtering: `@SurfSubscribe(includeSelf = false)` — the default —
     * drops events whose origin is this process.
     */
    var originInstanceId: String? = null
        @InternalEventBusApi set

    /**
     * When the event was published, in epoch milliseconds, or `0` before publishing.
     *
     * Stamped at publish rather than at construction: an event built now and published a second
     * later would otherwise carry a time that never happened on the wire.
     */
    var publishedAtEpochMs: Long = 0L
        @InternalEventBusApi set
}
