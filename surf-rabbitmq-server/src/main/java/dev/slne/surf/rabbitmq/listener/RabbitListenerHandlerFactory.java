package dev.slne.surf.rabbitmq.listener;

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket;
import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.InputStream;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

@NullMarked
public final class RabbitListenerHandlerFactory {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
    private static final byte[] TEMPLATE_CLASS_BYTES;

    static {
        try (final InputStream is = RabbitListenerHandlerTemplate.class.getResourceAsStream(RabbitListenerHandlerTemplate.class.getSimpleName() + ".class")) {
            Objects.requireNonNull(is, "RabbitListenerHandlerTemplate.class not found");
            TEMPLATE_CLASS_BYTES = is.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("Failed to load RabbitListenerHandlerTemplate.class", e);
        }
    }

    public static boolean canAccess(final Object handler, final Method method) {
        try {
            MethodHandles.privateLookupIn(handler.getClass(), LOOKUP).unreflect(method);
            return true;
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    public static RabbitListenerHandler create(final Object handler, final Method method, final Class<? extends RabbitRequestPacket<?>> requestEventClass) {
        try {
            final MethodHandles.Lookup privateLookupIn = MethodHandles.privateLookupIn(handler.getClass(), LOOKUP);
            final List<Object> classData = List.of(handler, requestEventClass, method, privateLookupIn);
            final MethodHandles.Lookup hiddenClassLookup = LOOKUP.defineHiddenClassWithClassData(TEMPLATE_CLASS_BYTES, classData, true);

            return hiddenClassLookup.lookupClass()
                    .asSubclass(RabbitListenerHandler.class)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to create RabbitListenerHandler for " + method, e);
        }
    }

    record ClassData(
            Method method,
            MethodHandle handle,
            Class<? extends RabbitRequestPacket<?>> requestClass
    ) {
    }

    @SuppressWarnings("unchecked")
    static ClassData classData(MethodHandles.Lookup lookup) {
        try {
            final Object target = MethodHandles.classDataAt(lookup, ConstantDescs.DEFAULT_NAME, Object.class, 0);
            final Class<? extends RabbitRequestPacket<?>> requestClass = MethodHandles.classDataAt(lookup, ConstantDescs.DEFAULT_NAME, Class.class, 1);
            final Method method = MethodHandles.classDataAt(lookup, ConstantDescs.DEFAULT_NAME, Method.class, 2);
            final MethodHandles.Lookup privateLookupIn = MethodHandles.classDataAt(lookup, ConstantDescs.DEFAULT_NAME, MethodHandles.Lookup.class, 3);

            final MethodHandle handle = privateLookupIn.unreflect(method).bindTo(target).asType(MethodType.methodType(void.class, RabbitRequestPacket.class));
            return new ClassData(method, handle, requestClass);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Failed to retrieve class data", e);
        }
    }
}
