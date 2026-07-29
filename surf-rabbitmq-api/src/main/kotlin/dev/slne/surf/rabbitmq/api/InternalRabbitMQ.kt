package dev.slne.surf.rabbitmq.api

import dev.slne.surf.api.shared.api.annotation.InternalAPIMarker

@RequiresOptIn
@InternalAPIMarker
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalRabbitMQ