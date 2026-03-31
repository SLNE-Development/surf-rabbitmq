package dev.slne.surf.rabbitmq.listener;

import dev.slne.surf.rabbitmq.api.packet.RabbitRequestPacket;
import dev.slne.surf.surfapi.core.api.invoker.HiddenInvokerUtil;
import dev.slne.surf.surfapi.core.api.invoker.InvokerClassData;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;

@SuppressWarnings("UnstableApiUsage")
final class RabbitListenerHandlerTemplate implements RabbitListenerHandler {
    private static final Method METHOD;
    private static final MethodHandle METHOD_HANDLE;
    private static final Class<?> REQUEST_CLASS;
    private static final boolean IS_SUSPEND;

    static {
        try {
            final MethodHandles.Lookup lookup = MethodHandles.lookup();
            final InvokerClassData classData = HiddenInvokerUtil.loadClassData(lookup, MethodType.methodType(void.class, RabbitRequestPacket.class));

            METHOD = classData.method();
            METHOD_HANDLE = classData.methodHandle();
            REQUEST_CLASS = classData.payloadClass();
            IS_SUSPEND = classData.isSuspend();
        } catch (Throwable e) {
            throw new AssertionError("Failed to load RabbitListenerHandlerTemplate", e);
        }
    }

    @Override
    public @Nullable Object handle(@NotNull RabbitRequestPacket<?> message, @NotNull Continuation<? super @NotNull Unit> $completion) {
        if (!REQUEST_CLASS.isInstance(message)) return Unit.INSTANCE;

        if (IS_SUSPEND) {
            try {
                return METHOD_HANDLE.invoke(message, $completion);
            } catch (Throwable e) {
                HiddenInvokerUtil.sneakyThrow(e);
            }
        } else {
            try {
                METHOD_HANDLE.invokeExact(message);
                return Unit.INSTANCE;
            } catch (Throwable e) {
                HiddenInvokerUtil.sneakyThrow(e);
            }
        }

        return null;
    }

    @Override
    public String toString() {
        return "RabbitListenerHandleTemplate{" + METHOD + '}';
    }
}
