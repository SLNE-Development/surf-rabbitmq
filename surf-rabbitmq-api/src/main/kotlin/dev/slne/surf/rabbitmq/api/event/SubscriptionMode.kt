package dev.slne.surf.rabbitmq.api.event

/**
 * How an event is distributed among the instances of one service.
 *
 * This is the single most consequential choice when subscribing, because it decides whether a
 * handler runs once or once per running instance.
 */
enum class SubscriptionMode {
    /**
     * Exactly **one** instance of the service handles each event.
     *
     * All instances consume one durable queue, so the event also survives every instance being
     * offline. Correct for anything with a side effect — database writes, statistics, webhooks.
     *
     * This is the default: handling an event once when you wanted all instances is a delay,
     * while handling it on all instances when you wanted one is duplicated work.
     */
    SHARED,

    /**
     * **Every** instance handles each event.
     *
     * Each process owns an auto-deleting queue, so events sent while a process is down are
     * lost. Correct for refreshing per-process state — cache invalidation, config reload,
     * kicking a player.
     */
    BROADCAST
}
