package dev.slne.surf.eventbus.exception.api

import dev.slne.surf.eventbus.exception.SurfEventBusException

/**
 * Thrown when a disconnected transporter is asked to connect again.
 *
 * Disconnect is terminal: the scopes are cancelled and the managed structures disposed, so a
 * reconnect would hand back an instance whose registrations silently do nothing.
 */
class SurfEventBusAlreadyDisconnectedException(
    name: String,
) : SurfEventBusException(
        "EventBusTransporter \"$name\" cannot reconnect after disconnect — build a new instance instead",
    )
