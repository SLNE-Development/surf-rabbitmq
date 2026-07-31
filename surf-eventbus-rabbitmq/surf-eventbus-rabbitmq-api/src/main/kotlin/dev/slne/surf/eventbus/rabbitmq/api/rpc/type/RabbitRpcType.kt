package dev.slne.surf.eventbus.rabbitmq.api.rpc.type

import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ
import kotlin.reflect.KType

@InternalRabbitMQ
interface RabbitRpcType {
    val kType: KType

    /**
     * List of annotations with target [AnnotationTarget.TYPE].
     */
    val annotations: List<Annotation>
}