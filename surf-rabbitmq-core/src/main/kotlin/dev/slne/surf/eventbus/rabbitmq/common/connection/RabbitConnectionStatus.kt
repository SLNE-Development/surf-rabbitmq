package dev.slne.surf.eventbus.rabbitmq.common.connection

enum class RabbitConnectionStatus {
    NEW,
    CONNECTING,
    OPEN,
    RECOVERING,
    UNAVAILABLE,
    CLOSED
}