package dev.slne.surf.eventbus.rabbitmq.api.rpc

/**
 * Marks an RPC method that does not wait for an answer.
 *
 * The caller returns after the publish; the message waits in the durable service queue if
 * nobody is running. Handler failures still take the retry ladder and end in the audit, but the
 * caller learns nothing about them.
 *
 * Must return `Unit` — the KSP processor rejects anything else. The marker is at the method
 * rather than at the call site because both sides need it: the server must not wait for a
 * `respond()` that never comes, and the client must not wait for an answer that is never sent.
 * The return type alone cannot express it, since a *waiting* method returning `Unit` is a
 * legitimate and different thing: "do this, and tell me if it fails".
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class FireAndForget
