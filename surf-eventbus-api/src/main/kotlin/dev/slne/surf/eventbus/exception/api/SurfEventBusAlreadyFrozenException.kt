package dev.slne.surf.eventbus.exception.api

import dev.slne.surf.eventbus.exception.SurfEventBusException

/** Thrown when a registration arrives after the api it targets has been frozen. */
class SurfEventBusAlreadyFrozenException : SurfEventBusException("Cannot register a service after the API has been frozen")
