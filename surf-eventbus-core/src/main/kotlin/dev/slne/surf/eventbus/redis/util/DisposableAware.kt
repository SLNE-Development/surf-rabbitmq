package dev.slne.surf.eventbus.redis.util

import dev.slne.surf.eventbus.redis.util.RedisDisposable
import reactor.core.Disposable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks Reactor [Disposable]s and releases them once.
 *
 * Implements both [RedisDisposable] - the interface the published Redis structures expose - and
 * Reactor's own, so Reactor stays the mechanism here while staying out of the ABI. The two have
 * the same shape, so one pair of methods satisfies both.
 */
abstract class DisposableAware : RedisDisposable, Disposable {
    private val disposables = ConcurrentHashMap.newKeySet<Disposable>()
    private val disposed = AtomicBoolean(false)

    final override fun isDisposed() = disposed.get()

    final override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        dispose0()
        disposables.forEach(Disposable::dispose)
        disposables.clear()
    }

    protected abstract fun dispose0()

    protected fun trackDisposable(disposable: Disposable) {
        if (disposed.get()) {
            disposable.dispose()
            return
        }

        disposables.add(disposable)
        if (disposed.get() && disposables.remove(disposable)) {
            disposable.dispose()
        }
    }
}