package dev.slne.surf.eventbus.core.dispatch

import dev.slne.surf.eventbus.event.SurfBusEvent
import java.util.concurrent.ConcurrentHashMap

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

    private val cache = ConcurrentHashMap<String, Optional>()

    private class Optional(val value: Class<out SurfBusEvent>?)

    fun resolve(typeName: String, known: Collection<Class<out SurfBusEvent>>, loaders: Collection<ClassLoader>):
            Class<out SurfBusEvent>? {
        cache[typeName]?.let { return it.value }

        val fromRegistry = known.firstOrNull { it.name == typeName }
        if (fromRegistry != null) {
            cache[typeName] = Optional(fromRegistry)
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
                cache[typeName] = Optional(resolved)
                return resolved
            }
        }

        cache[typeName] = Optional(null)
        return null
    }

    /** Whether [typeName] has already produced a negative result. Basis of warning exactly once. */
    fun isKnownUnresolvable(typeName: String): Boolean = cache[typeName]?.value == null && cache.containsKey(typeName)
}
