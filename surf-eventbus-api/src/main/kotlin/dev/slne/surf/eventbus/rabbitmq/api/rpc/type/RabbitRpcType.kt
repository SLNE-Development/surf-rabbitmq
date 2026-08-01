package dev.slne.surf.eventbus.rabbitmq.api.rpc.type

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

@InternalEventBusApi
interface RabbitRpcType {
    val kType: KType

    /**
     * List of annotations with target [AnnotationTarget.TYPE].
     */
    val annotations: List<Annotation>
}