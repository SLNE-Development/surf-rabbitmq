package dev.slne.surf.eventbus.exception.api

import dev.slne.surf.eventbus.exception.SurfEventBusException

/** Thrown when a connect is attempted while registrations are still open. */
class SurfEventBusNotFrozenException :
    SurfEventBusException(
        "EventBusTransporter must be frozen before connecting — call freeze() or freezeAndConnect() first",
    )
