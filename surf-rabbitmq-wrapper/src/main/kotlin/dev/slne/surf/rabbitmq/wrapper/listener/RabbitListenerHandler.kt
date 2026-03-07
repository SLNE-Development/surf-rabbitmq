package dev.slne.surf.rabbitmq.wrapper.listener

import dev.slne.surf.rabbitmq.wrapper.packet.RabbitPacket
import dev.slne.surf.surfapi.core.api.util.mutableObject2ObjectMapOf
import dev.slne.surf.surfapi.core.api.util.mutableObjectListOf
import it.unimi.dsi.fastutil.objects.ObjectList
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.javaMethod

object RabbitListenerHandler {
    private val listeners = mutableObject2ObjectMapOf<Class<*>, ObjectList<MethodHandle>>()
    private val lookup = MethodHandles.lookup()

    fun registerListener(instance: Any) {
        val clazz = instance::class

        for (function in clazz.declaredFunctions) {
            function.findAnnotation<RabbitHandler>() ?: continue

            if (function.isSuspend) {
                throw IllegalArgumentException("Listener functions cannot be suspend functions: ${clazz.qualifiedName}::${function.name}")
            }

            if (function.returnType.classifier != Unit::class) {
                throw IllegalArgumentException("Listener functions must return Unit: ${clazz.qualifiedName}::${function.name}")
            }

            if (function.parameters.size != 2) {
                throw IllegalArgumentException("Listener functions must have exactly one parameter: ${clazz.qualifiedName}::${function.name}")
            }

            val packetType = function.parameters[1].type.classifier as? KClass<*>
                ?: throw IllegalArgumentException("Listener function parameter must have a valid type: ${clazz.qualifiedName}::${function.name}")

            if (!RabbitPacket::class.java.isAssignableFrom(packetType.java)) {
                throw IllegalArgumentException("Listener function parameter must be a subclass of RabbitPacket: ${clazz.qualifiedName}::${function.name}")
            }

            val method = function.javaMethod!!
            method.isAccessible = true

            val handle = lookup.unreflect(method).bindTo(instance)

            listeners.computeIfAbsent(packetType.java) { mutableObjectListOf() }
                .add(handle)
        }
    }

    fun notifyListeners(packet: RabbitPacket) {
        listeners[packet.javaClass]?.forEach {
            it.invoke(packet)
        }
    }
}