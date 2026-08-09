package dev.slne.surf.eventbus.connection

import dev.slne.surf.api.core.util.logger
import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.exception.api.SurfEventBusAlreadyDisconnectedException
import dev.slne.surf.eventbus.exception.api.SurfEventBusAlreadyFrozenException
import dev.slne.surf.eventbus.exception.api.SurfEventBusNotFrozenException
import dev.slne.surf.eventbus.platform.StandaloneLifecycleHook
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What every transport entry point does the same way: freeze, connect, disconnect.
 *
 * `SurfRabbitApi` and `SurfRedisApi` each grew their own copy of this — two flags, two
 * check-then-act guards, two coroutine scopes, and two different answers to "what happens if
 * you connect twice". The transport-specific part is [connection]; the ceremony around it is
 * not transport-specific and lives here once.
 */
@InternalEventBusApi
abstract class EventBusTransporter(
    val name: String,
    private val standalone: Boolean = false,
    private val standaloneHook: StandaloneLifecycleHook = StandaloneLifecycleHook.NoOp,
) {
    protected val log = logger()

    // An AtomicBoolean rather than a plain var: freeze() is a check-then-act, and without
    // publication two threads could both pass the check and both believe they froze the api.
    // SurfEventBusImpl, EventSubscriptionRegistry, QueryServiceRegistry and RedisApi all guard
    // their own flag; this was the one that did not.
    private val frozen = AtomicBoolean(false)
    private val disconnected = AtomicBoolean(false)

    /**
     * The broker connection this transporter drives.
     *
     * Abstract rather than a constructor parameter because both implementations build theirs
     * lazily: constructing an api — to read its identity, or to register services before
     * connecting — must not require the `ServiceLoader` registration that only
     * `surf-eventbus-core` satisfies.
     */
    @InternalEventBusApi
    protected abstract val connection: EventBusConnection

    /** `true` once [freeze] has run and no further registrations are accepted. */
    val isFrozen get() = frozen.get()

    /**
     * `true` once [disconnect] has run.
     *
     * Terminal: a disconnected transporter cannot be reconnected, because its scopes are
     * cancelled and its managed structures disposed. Build a new one instead.
     */
    val isDisconnected get() = disconnected.get()

    @InternalEventBusApi
    val scope =
        CoroutineScope(
            Dispatchers.Default +
                CoroutineName("EventBusTransporter-$name") +
                SupervisorJob() +
                CoroutineExceptionHandler { context, throwable ->
                    log
                        .atSevere()
                        .withCause(throwable)
                        .log("Unhandled exception in EventBusTransporter coroutine ${context[CoroutineName]}")
                },
        )

    /**
     * Locks registrations, so [connect] can rely on the set being complete.
     *
     * @throws SurfEventBusAlreadyFrozenException if this transporter is already frozen.
     */
    fun freeze() {
        if (!frozen.compareAndSet(false, true)) throw SurfEventBusAlreadyFrozenException()
    }

    /**
     * Opens the broker connection.
     *
     * @throws SurfEventBusNotFrozenException if [freeze] has not run.
     * @throws SurfEventBusAlreadyDisconnectedException if this transporter was disconnected.
     */
    suspend fun connect() {
        if (!frozen.get()) throw SurfEventBusNotFrozenException()
        if (disconnected.get()) throw SurfEventBusAlreadyDisconnectedException(name)
        if (standalone) standaloneHook.beforeConnect()

        try {
            connection.connect()
        } catch (failure: Throwable) {
            // A half-open connection is not retryable, and whatever the failed connect did get
            // as far as opening stays open unless something closes it. Tearing down through
            // the normal path means the connection disposes exactly what it created, rather
            // than each transport hand-rolling a second, subtly different cleanup branch.
            try {
                disconnect()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
    }

    /** Convenience for the usual pairing of [freeze] and [connect]. */
    suspend fun freezeAndConnect() {
        freeze()
        connect()
    }

    /**
     * Closes the broker connection and cancels [scope].
     *
     * Idempotent, and terminal: the second call is a no-op rather than an error, because
     * shutdown paths routinely run twice.
     */
    suspend fun disconnect() {
        if (!disconnected.compareAndSet(false, true)) return

        try {
            connection.disconnect()
        } finally {
            scope.cancel("EventBusTransporter \"$name\" disconnected")
            if (standalone) standaloneHook.afterDisconnect()
        }
    }
}
