package dev.slne.surf.eventbus.redis.util

import dev.slne.surf.eventbus.InternalEventBusApi
import reactor.util.function.Tuple2

// The two `Mono<T>.asDeferred()` extensions that used to live here are gone.
//
// Both were unused. One was a hand-written Reactive Streams Subscriber whose `onComplete`
// threw NoSuchElementException from inside the signal - a violation of the Reactive Streams
// spec (§2.13) - and the other was a one-line wrapper around `awaitSingle`, which is to say a
// second copy of what `kotlinx-coroutines-reactor` already provides. Its own KDoc told callers
// to prefer the other one. They also put `reactor.core.publisher.Mono` in the published ABI of
// a library that no longer asks its consumers to know Reactor exists.
//
// Use `kotlinx.coroutines.reactor.awaitSingle` / `awaitSingleOrNull`, or
// `kotlinx.coroutines.reactive.awaitFirstOrNull`, directly.

/**
 * Enables Kotlin destructuring for Reactor's [Tuple2].
 *
 * Example:
 * ```
 * val (a, b) = tuple2
 * ```
 */
@InternalEventBusApi
operator fun <T1 : Any, T2 : Any> Tuple2<T1, T2>.component1(): T1 = this.t1

/**
 * Enables Kotlin destructuring for Reactor's [Tuple2].
 *
 * Example:
 * ```
 * val (a, b) = tuple2
 * ```
 */
@InternalEventBusApi
operator fun <T1 : Any, T2 : Any> Tuple2<T1, T2>.component2(): T2 = this.t2
