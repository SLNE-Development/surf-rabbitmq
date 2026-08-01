package dev.slne.surf.eventbus.rabbitmq.exception

/**
 * Thrown when [dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi.connect] is called before
 * [dev.slne.surf.eventbus.rabbitmq.SurfRabbitApi.freeze].
 *
 * Extends [IllegalStateException] directly, matching [SurfRabbitApiAlreadyFrozenException] —
 * both are frozen-state precondition violations, not wire-protocol errors.
 */
class SurfRabbitApiNotFrozenException :
    IllegalStateException("SurfRabbitApi must be frozen before connecting — call freeze() or freezeAndConnect() first")