package dev.slne.surf.eventbus.connection

import dev.slne.surf.eventbus.InternalEventBusApi

@InternalEventBusApi
interface EventBusConnectionFactory<T : EventBusTransporter, C : EventBusConnection> {
    fun createConnection(transporter: T): C
}
