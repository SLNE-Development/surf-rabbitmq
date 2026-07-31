package dev.slne.surf.eventbus.rabbitmq.api.exception

/**
 * Thrown when a frozen-state precondition is violated: freezing twice, or registering a
 * service after [dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi.freeze] was called.
 *
 * Extends [IllegalStateException] directly rather than [SurfRabbitException] — this is a
 * state precondition violation, not a wire-protocol error.
 */
class SurfRabbitApiAlreadyFrozenException :
    IllegalStateException("Cannot register a service after the API has been frozen")