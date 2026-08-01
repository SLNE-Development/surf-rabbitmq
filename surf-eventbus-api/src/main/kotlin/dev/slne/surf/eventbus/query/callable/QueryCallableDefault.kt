package dev.slne.surf.eventbus.query.callable

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

@InternalEventBusApi
class QueryCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: KType,
    override val invoker: QueryInvoker<Service>,
    override val parameters: Array<out QueryParameter>
) : QueryCallable<Service>
