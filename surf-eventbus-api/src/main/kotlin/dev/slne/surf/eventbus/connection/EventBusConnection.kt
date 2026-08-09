package dev.slne.surf.eventbus.connection

import dev.slne.surf.eventbus.InternalEventBusApi

/**
 * The broker-facing half of an [EventBusTransporter].
 *
 * Only the two lifecycle verbs live here. Everything a transport needs beyond them — how a
 * request is addressed, where message loss is reported — belongs to that transport's own
 * sub-interface, because a member every implementation has to invent a stub for is not a
 * shared contract.
 */
@InternalEventBusApi
interface EventBusConnection {
    suspend fun connect()

    suspend fun disconnect()
}
