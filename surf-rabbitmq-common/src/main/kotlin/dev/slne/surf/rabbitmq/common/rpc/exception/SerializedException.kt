package dev.slne.surf.rabbitmq.common.rpc.exception

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier

@Serializable
data class StackElement(
    val clazz: String,
    val method: String,
    val fileName: String?,
    val lineNumber: Int
) {
    fun toStackTraceElement(): StackTraceElement =
        StackTraceElement(clazz, method, fileName, lineNumber)
}

/**
 * A serializable snapshot of a [Throwable] including message, stack trace, cause chain
 * and suppressed exceptions, used to transport server-side RPC failures to the client.
 *
 * [deserialize] attempts to reconstruct the original exception class via reflection so
 * callers can `catch` the real type. If the class is unavailable on this side or carries
 * state that cannot be restored, it falls back to a [DeserializedException] that still
 * preserves the original `toString()`, message, stack trace and cause chain.
 */
@Serializable
data class SerializedException(
    val toStringMessage: String,
    val message: String? = null,
    val stacktrace: List<StackElement> = emptyList(),
    val cause: SerializedException? = null,
    val className: String,
    val suppressed: List<SerializedException> = emptyList()
) {
    /**
     * Reconstructs this snapshot as a throwable. Never throws.
     */
    fun deserialize(): Throwable {
        val throwable = runCatching {
            deserializeCancellationOrNull() ?: reconstructOriginalOrNull()
        }.getOrNull() ?: DeserializedException(toStringMessage, message, stacktrace, cause, className)

        for (suppressedException in suppressed) {
            runCatching { throwable.addSuppressed(suppressedException.deserialize()) }
        }

        return throwable
    }

    /**
     * [CancellationException]s are always reconstructed as the base class so cancellation
     * propagates correctly even when the concrete subclass (often internal to
     * kotlinx.coroutines) is not reconstructable.
     */
    private fun deserializeCancellationOrNull(): CancellationException? {
        if (className != CANCELLATION_EXCEPTION_CLASS_NAME) return null

        return CancellationException(message, cause?.deserialize()).also {
            it.stackTrace = deserializeStackTrace()
        }
    }

    private fun reconstructOriginalOrNull(): Throwable? {
        val clazz = Class.forName(className)
        if (!Throwable::class.java.isAssignableFrom(clazz)) return null

        // Only reconstruct exceptions without additional fields beyond Throwable's own;
        // anything else would silently lose state, so fall back to DeserializedException.
        if (clazz.fieldsCountOrDefault(-1) != throwableFields) return null

        val constructors = clazz.constructors.sortedByDescending { it.parameterTypes.size }
        for (constructor in constructors) {
            val throwable = runCatching { tryCreateException(constructor) }.getOrNull()
            if (throwable != null) return throwable
        }

        return null
    }

    private fun tryCreateException(constructor: Constructor<*>): Throwable? {
        fun Class<*>.isThrowableClass(): Boolean = Throwable::class.java.isAssignableFrom(this)
        val parameters = constructor.parameterTypes

        var causeApplied = false
        val result = when (parameters.size) {
            2 -> when {
                parameters[0] == String::class.java && parameters[1].isThrowableClass() -> {
                    causeApplied = true
                    constructor.newInstance(message, cause?.deserialize())
                }

                else -> null
            }

            1 -> when {
                parameters[0].isThrowableClass() -> {
                    causeApplied = true
                    constructor.newInstance(cause?.deserialize())
                }

                parameters[0] == String::class.java -> constructor.newInstance(message)
                else -> null
            }

            0 -> constructor.newInstance()
            else -> null
        }

        val throwable = result as? Throwable ?: return null

        val serializedCause = cause
        if (!causeApplied && serializedCause != null) {
            runCatching { throwable.initCause(serializedCause.deserialize()) }
        }

        throwable.stackTrace = deserializeStackTrace()
        return throwable
    }

    internal fun deserializeStackTrace(): Array<StackTraceElement> =
        Array(stacktrace.size) { stacktrace[it].toStackTraceElement() }

    companion object {
        private val CANCELLATION_EXCEPTION_CLASS_NAME = CancellationException::class.java.typeName

        private val throwableFields = Throwable::class.java.fieldsCountOrDefault(-1)

        private fun Class<*>.fieldsCountOrDefault(defaultValue: Int) =
            runCatching { fieldsCount() }.getOrDefault(defaultValue)

        private tailrec fun Class<*>.fieldsCount(accumulator: Set<String> = emptySet()): Int {
            val fields = declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }

            val totalFields = (accumulator + fields)
            val superClass = superclass ?: run {
                var messageField = false
                return totalFields.count {
                    if (it == "message" || it == "detailMessage") {
                        if (messageField) {
                            return@count false
                        }
                        messageField = true
                    }
                    true
                }
            }

            return superClass.fieldsCount(totalFields)
        }

        fun from(throwable: Throwable): SerializedException {
            val className = if (throwable is CancellationException) {
                CANCELLATION_EXCEPTION_CLASS_NAME
            } else {
                throwable.javaClass.typeName
            }

            return SerializedException(
                toStringMessage = throwable.toString(),
                message = throwable.message,
                stacktrace = throwable.stackTrace.map { element ->
                    StackElement(
                        clazz = element.className,
                        method = element.methodName,
                        fileName = element.fileName,
                        lineNumber = element.lineNumber
                    )
                },
                cause = throwable.cause?.let(Companion::from),
                className = className,
                suppressed = throwable.suppressed.map(Companion::from)
            )
        }
    }
}

/**
 * Fallback for remote exceptions whose original class could not be reconstructed.
 * Preserves the original `toString()`, message, stack trace and cause chain.
 */
class DeserializedException(
    private val toStringMessage: String,
    override val message: String?,
    stacktrace: List<StackElement>,
    cause: SerializedException?,
    val className: String
) : Throwable() {
    override val cause: Throwable? = cause?.deserialize()

    init {
        stackTrace = stacktrace.map { it.toStackTraceElement() }.toTypedArray()
    }

    override fun toString(): String = toStringMessage
}
