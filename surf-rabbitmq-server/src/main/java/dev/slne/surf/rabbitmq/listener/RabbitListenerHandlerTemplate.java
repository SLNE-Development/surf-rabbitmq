package dev.slne.surf.rabbitmq.listener;

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

final class RabbitListenerHandlerTemplate implements RabbitListenerHandler {
    private static final Method METHOD;
    private static final MethodHandle METHOD_HANDLE;
    private static final Class<? extends RabbitRequestPacket<?>> REQUEST_CLASS;

    static {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final RabbitListenerHandlerFactory.ClassData classData = RabbitListenerHandlerFactory.classData(lookup);

        METHOD = classData.method();
        METHOD_HANDLE = classData.handle();
        REQUEST_CLASS = classData.requestClass();
    }

    @Override
    public void handle(RabbitRequestPacket<?> message) {
        if (!REQUEST_CLASS.isInstance(message)) return;
        try {
            METHOD_HANDLE.invokeExact(message);
        } catch (Throwable e) {
            sneakyThrow(e);
        }
    }

    @Override
    public String toString() {
        return "RabbitListenerHandleTemplate{" + METHOD + '}';
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }
}
