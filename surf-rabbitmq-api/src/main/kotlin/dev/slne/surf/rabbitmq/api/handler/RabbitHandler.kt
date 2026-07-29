package dev.slne.surf.rabbitmq.api.handler

/**
 * Marks a method as a request handler.
 *
 * @property retry whether a failed invocation is retried on the 10s/60s/300s ladder before
 *   being dead-lettered. Set `false` for handlers that are not idempotent — a retried handler
 *   may run more than once for the same message.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RabbitHandler(val retry: Boolean = true)
