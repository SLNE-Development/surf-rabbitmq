package dev.slne.surf.rabbitmq.common.util

import dev.slne.surf.rabbitmq.api.InternalRabbitMQ

@InternalRabbitMQ
@Suppress("DEPRECATION", "removal") // ThreadDeath is deprecated
fun Throwable.rethrowIfFatal() {
    when (this) {
        is VirtualMachineError,
        is ThreadDeath,
        is LinkageError -> throw this
    }
}
