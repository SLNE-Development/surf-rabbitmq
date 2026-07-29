package dev.slne.surf.rabbitmq.api.event

/**
 * Declares the topic an event is published under.
 *
 * The topic lives on the type rather than at the call site, so a publisher cannot accidentally
 * send the same event under two different keys.
 *
 * Topics are dot-separated, e.g. `faction.disbanded` or `player.punish.ban`. Wildcards are not
 * allowed here — they belong in [RabbitSubscribe] patterns.
 *
 * @property topic the routing key used when publishing
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitEvent(val topic: String)
