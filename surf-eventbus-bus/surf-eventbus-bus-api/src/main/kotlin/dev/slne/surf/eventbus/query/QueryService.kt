package dev.slne.surf.eventbus.query

/**
 * Marks an interface as a broadcast query contract.
 *
 * A query asks everyone and is answered by whoever is responsible; the first answer wins. It is
 * deliberately **not** RPC: over Pub/Sub, "exactly one instance does this" would be a lie,
 * because every listening process runs the handler unless it abstains.
 *
 * Rules the KSP processor enforces (see plan 3):
 * - every method is `suspend` and returns a **nullable** type. `null` means abstain — no message
 *   is sent at all — so a non-nullable return type could not express "not mine".
 * - `@FireAndForget` is rejected: a question without an answer is an event.
 *
 * There is no `service` attribute: a query addresses nobody. Its channel follows the contract's
 * fully qualified name.
 *
 * @property timeoutMillis how long the caller waits before the call returns `null`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryService(val timeoutMillis: Long = 5_000)
