package dev.slne.surf.eventbus.query.callable

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

/** One parameter of a `@QueryService` method, as seen by the generated descriptor. */
@InternalEventBusApi
interface QueryParameter {
    val name: String
    val type: KType
}
