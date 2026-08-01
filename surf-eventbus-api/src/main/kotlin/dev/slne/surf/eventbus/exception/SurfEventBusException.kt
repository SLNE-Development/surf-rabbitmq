package dev.slne.surf.eventbus.exception

/**
 * The root of everything this project throws on purpose.
 *
 * A consumer that wants to catch "the bus failed" had to name two unrelated hierarchies and
 * knew of neither that it was the complete set.
 */
abstract class SurfEventBusException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
