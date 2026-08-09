package dev.slne.surf.eventbus.rabbitmq.connection

enum class RabbitConnectionStatus {
    NEW,
    CONNECTING,
    OPEN,
    RECOVERING,
    UNAVAILABLE,
    CLOSED
}