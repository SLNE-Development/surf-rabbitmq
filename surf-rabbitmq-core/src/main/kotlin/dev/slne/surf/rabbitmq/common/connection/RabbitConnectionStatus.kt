package dev.slne.surf.rabbitmq.common.connection

enum class RabbitConnectionStatus {
    NEW,
    CONNECTING,
    OPEN,
    RECOVERING,
    UNAVAILABLE,
    CLOSED
}