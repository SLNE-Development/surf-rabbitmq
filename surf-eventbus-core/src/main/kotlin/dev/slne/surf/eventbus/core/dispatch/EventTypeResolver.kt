package dev.slne.surf.eventbus.core.dispatch

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import dev.slne.surf.eventbus.event.SurfBusEvent

/**
 * Resolves the wire type name to a class, in two stages.
 *
 * The wire carries the **concrete** type name while a handler may only have registered a base
 * type, so a resolver that knows only literally registered types would drop exactly the
 * documented polymorphic subscription. This is the bug the pre-2.0 Rabbit path had.
 *
 * Stage one is the registry — cheap, exact, the normal case. Stage two is `Class.forName` over
 * the class loaders of the registered listeners, because event types live in the subscribing
 * plugin on Paper and Velocity, not in ours. Positive **and** negative results are cached so a
 * stream of unknown types does not become a stream of failing lookups.
 */
class EventTypeResolver {

    /**
     * Bounded on purpose: the key is `envelope.type`, straight off the wire.
     *
     * Negative results have to be cached - that is the point of stage two - but an unbounded
     * map keyed by remote input means any peer that can publish to the Redis channel grows this
     * process's heap by publishing envelopes with random type names, and there is no
     * authentication on those channels. The cap turns that into a bounded working set; a real
     * deployment has far fewer distinct event types than the limit.
     */
    private val cache: Cache<String, Optional> = Caffeine.newBuilder()
        .maximumSize(MAX_CACHED_TYPES)
        .build()

    private class Optional(val value: Class<out SurfBusEvent>?)

    fun resolve(typeName: String, known: Collection<Class<out SurfBusEvent>>, loaders: Collection<ClassLoader>):
            Class<out SurfBusEvent>? {
        cache.getIfPresent(typeName)?.let { return it.value }

        val fromRegistry = known.firstOrNull { it.name == typeName }
        if (fromRegistry != null) {
            cache.put(typeName, Optional(fromRegistry))
            return fromRegistry
        }

        for (loader in loaders) {
            val candidate = try {
                Class.forName(typeName, false, loader)
            } catch (_: ClassNotFoundException) {
                continue
            } catch (_: LinkageError) {
                continue
            }

            // Loading only what extends SurfBusEvent: a wire type name is attacker-adjacent
            // input, and initialising an arbitrary class because someone published its name is
            // not a thing this bus does.
            if (SurfBusEvent::class.java.isAssignableFrom(candidate)) {
                @Suppress("UNCHECKED_CAST")
                val resolved = candidate as Class<out SurfBusEvent>
                cache.put(typeName, Optional(resolved))
                return resolved
            }
        }

        cache.put(typeName, Optional(null))
        return null
    }

    private companion object {
        const val MAX_CACHED_TYPES = 4_096L
    }
}
