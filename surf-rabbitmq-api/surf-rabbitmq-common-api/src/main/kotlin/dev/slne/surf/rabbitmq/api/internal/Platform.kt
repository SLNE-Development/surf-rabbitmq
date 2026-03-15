package dev.slne.surf.rabbitmq.api.internal

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
enum class Platform() {
    SERVER,
    PAPER,
    VELOCITY
}