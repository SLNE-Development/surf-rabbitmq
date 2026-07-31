package dev.slne.surf.eventbus.query.callable

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

@InternalEventBusApi
class QueryParameterDefault(
    override val name: String,
    override val type: KType
) : QueryParameter
