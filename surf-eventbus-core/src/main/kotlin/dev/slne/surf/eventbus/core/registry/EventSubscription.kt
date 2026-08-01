package dev.slne.surf.eventbus.core.registry

import dev.slne.surf.eventbus.event.SurfBusEvent
import java.lang.reflect.Method

data class EventSubscription(
    val eventClass: Class<out SurfBusEvent>,
    val pattern: String,
    val includeSelf: Boolean,
    val listener: Any,
    val method: Method
) {
    /** `Klasse#Methode`, used in log lines and audit rows. */
    val displayName: String get() = "${listener.javaClass.simpleName}#${method.name}"
}
