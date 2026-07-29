package dev.slne.surf.rabbitmq.api.exception

/**
 * Thrown when [dev.slne.surf.rabbitmq.api.SurfRabbitApi.connect] is called before
 * [dev.slne.surf.rabbitmq.api.SurfRabbitApi.freeze].
 *
 * Extends [IllegalStateException] directly, matching [SurfRabbitApiAlreadyFrozenException] —
 * both are frozen-state precondition violations, not wire-protocol errors.
 */
class SurfRabbitApiNotFrozenException :
    IllegalStateException("SurfRabbitApi must be frozen before connecting — call freeze() or freezeAndConnect() first")