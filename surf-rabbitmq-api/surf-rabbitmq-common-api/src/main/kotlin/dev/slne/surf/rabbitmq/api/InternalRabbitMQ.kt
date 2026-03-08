package dev.slne.surf.rabbitmq.api

import dev.slne.surf.surfapi.shared.api.annotation.InternalAPIMarker

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
annotation class InternalRabbitMQ()