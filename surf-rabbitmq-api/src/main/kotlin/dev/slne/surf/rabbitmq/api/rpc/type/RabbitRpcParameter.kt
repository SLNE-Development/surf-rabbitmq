package dev.slne.surf.rabbitmq.api.rpc.type

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
interface RabbitRpcParameter {
    val name: String
    val type: RabbitRpcType
    val isOptional: Boolean

    /**
     * List of annotations with target [AnnotationTarget.VALUE_PARAMETER].
     */
    val annotations: List<Annotation>
}