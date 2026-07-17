package dev.slne.surf.rabbitmq.common.rpc.exception

import dev.slne.surf.rabbitmq.common.util.rethrowIfFatal
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.lang.reflect.Constructor
import java.lang.reflect.Modifier
import java.util.*

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
     * Reconstructs this snapshot as a throwable, falling back to [DeserializedException]
     * when the original type cannot be recreated. Fatal JVM errors are not suppressed.
     */
    fun deserialize(): Throwable {
        val seen = Collections.newSetFromMap(IdentityHashMap<SerializedException, Boolean>())
        return deserialize(depth = 0, seen = seen)
    }

    private fun deserialize(depth: Int, seen: MutableSet<SerializedException>): Throwable {
        if (depth >= MAX_CAUSE_DEPTH || !seen.add(this)) {
            return DeserializedException.create(
                toStringMessage = toStringMessage.truncate(),
                message = message?.truncate(),
                stacktrace = stacktrace.take(MAX_STACK_ELEMENTS),
                cause = null,
                className = className
            )
        }

        try {
            val deserializedCause = cause?.deserialize(depth + 1, seen)
            val throwable = try {
                deserializeCancellationOrNull(deserializedCause)
                    ?: reconstructOriginalOrNull(deserializedCause)
                    ?: DeserializedException.create(
                        toStringMessage = toStringMessage.truncate(),
                        message = message?.truncate(),
                        stacktrace = stacktrace.take(MAX_STACK_ELEMENTS),
                        cause = deserializedCause,
                        className = className
                    )
            } catch (failure: Throwable) {
                failure.rethrowIfFatal()
                DeserializedException.create(
                    toStringMessage = toStringMessage.truncate(),
                    message = message?.truncate(),
                    stacktrace = stacktrace.take(MAX_STACK_ELEMENTS),
                    cause = deserializedCause,
                    className = className
                )
            }

            for (suppressedException in suppressed.take(MAX_SUPPRESSED_EXCEPTIONS)) {
                try {
                    throwable.addSuppressed(suppressedException.deserialize(depth + 1, seen))
                } catch (failure: Throwable) {
                    failure.rethrowIfFatal()
                }
            }

            return throwable
        } finally {
            seen.remove(this)
        }
    }

    /**
     * [CancellationException]s are always reconstructed as the base class so cancellation
     * propagates correctly even when the concrete subclass (often internal to
     * kotlinx.coroutines) is not reconstructable.
     */
    private fun deserializeCancellationOrNull(deserializedCause: Throwable?): CancellationException? {
        if (className != CANCELLATION_EXCEPTION_CLASS_NAME) return null

        return CancellationException(message?.truncate(), deserializedCause).also {
            it.stackTrace = deserializeStackTrace()
        }
    }

    private fun reconstructOriginalOrNull(deserializedCause: Throwable?): Throwable? {
        val classLoader = Thread.currentThread().contextClassLoader
            ?: SerializedException::class.java.classLoader
        val clazz = Class.forName(className, false, classLoader)
        if (!Throwable::class.java.isAssignableFrom(clazz)) return null

        // Only reconstruct exceptions without additional fields beyond Throwable's own;
        // anything else would silently lose state, so fall back to DeserializedException.
        if (clazz.fieldsCountOrDefault(-1) != throwableFields) return null

        val constructors = clazz.constructors.sortedByDescending { it.parameterTypes.size }
        for (constructor in constructors) {
            val throwable = try {
                tryCreateException(constructor, deserializedCause)
            } catch (failure: Throwable) {
                failure.rethrowIfFatal()
                null
            }
            if (throwable != null) return throwable
        }

        return null
    }

    private fun tryCreateException(constructor: Constructor<*>, deserializedCause: Throwable?): Throwable? {
        fun Class<*>.isThrowableClass(): Boolean = Throwable::class.java.isAssignableFrom(this)
        val parameters = constructor.parameterTypes

        var causeApplied = false
        val result = when (parameters.size) {
            2 -> when {
                parameters[0] == String::class.java && parameters[1].isThrowableClass() -> {
                    causeApplied = true
                    constructor.newInstance(message?.truncate(), deserializedCause)
                }

                else -> null
            }

            1 -> when {
                parameters[0].isThrowableClass() -> {
                    causeApplied = true
                    constructor.newInstance(deserializedCause)
                }

                parameters[0] == String::class.java -> constructor.newInstance(message?.truncate())
                else -> null
            }

            0 -> constructor.newInstance()
            else -> null
        }

        val throwable = result as? Throwable ?: return null

        if (!causeApplied && deserializedCause != null) {
            try {
                throwable.initCause(deserializedCause)
            } catch (failure: Throwable) {
                failure.rethrowIfFatal()
            }
        }

        throwable.stackTrace = deserializeStackTrace()
        return throwable
    }

    internal fun deserializeStackTrace(): Array<StackTraceElement> =
        Array(minOf(stacktrace.size, MAX_STACK_ELEMENTS)) { stacktrace[it].toStackTraceElement() }

    companion object {
        private val CANCELLATION_EXCEPTION_CLASS_NAME = CancellationException::class.java.typeName
        private const val MAX_CAUSE_DEPTH = 32
        private const val MAX_STACK_ELEMENTS = 512
        private const val MAX_SUPPRESSED_EXCEPTIONS = 32
        private const val MAX_TEXT_LENGTH = 16_384

        private val throwableFields = Throwable::class.java.fieldsCountOrDefault(-1)

        private fun Class<*>.fieldsCountOrDefault(defaultValue: Int): Int = try {
            fieldsCount()
        } catch (failure: Throwable) {
            failure.rethrowIfFatal()
            defaultValue
        }

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
            val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            return from(throwable, depth = 0, seen = seen)
        }

        private fun from(
            throwable: Throwable,
            depth: Int,
            seen: MutableSet<Throwable>
        ): SerializedException {
            val className = if (throwable is CancellationException) {
                CANCELLATION_EXCEPTION_CLASS_NAME
            } else {
                throwable.javaClass.typeName
            }

            if (depth >= MAX_CAUSE_DEPTH || !seen.add(throwable)) {
                return SerializedException(
                    toStringMessage = "$className: [exception graph truncated]",
                    message = "Exception graph truncated",
                    className = className
                )
            }

            try {
                val safeToString = try {
                    throwable.toString().truncate()
                } catch (failure: Throwable) {
                    failure.rethrowIfFatal()
                    className
                }

                return SerializedException(
                    toStringMessage = safeToString,
                    message = throwable.safeMessage(),
                    stacktrace = throwable.safeStackTrace().take(MAX_STACK_ELEMENTS).map { element ->
                        StackElement(
                            clazz = element.className,
                            method = element.methodName,
                            fileName = element.fileName,
                            lineNumber = element.lineNumber
                        )
                    },
                    cause = throwable.safeCause()?.let { from(it, depth + 1, seen) },
                    className = className,
                    suppressed = throwable.safeSuppressed()
                        .take(MAX_SUPPRESSED_EXCEPTIONS)
                        .map { from(it, depth + 1, seen) }
                )
            } finally {
                seen.remove(throwable)
            }
        }

        private fun String.truncate(): String =
            if (length <= MAX_TEXT_LENGTH) this else take(MAX_TEXT_LENGTH) + "…"

        private fun Throwable.safeMessage(): String? = try {
            message?.truncate()
        } catch (failure: Throwable) {
            failure.rethrowIfFatal()
            null
        }

        private fun Throwable.safeStackTrace(): Array<StackTraceElement> = try {
            stackTrace
        } catch (failure: Throwable) {
            failure.rethrowIfFatal()
            emptyArray()
        }

        private fun Throwable.safeCause(): Throwable? = try {
            cause
        } catch (failure: Throwable) {
            failure.rethrowIfFatal()
            null
        }

        private fun Throwable.safeSuppressed(): Array<Throwable> = try {
            suppressed
        } catch (failure: Throwable) {
            failure.rethrowIfFatal()
            emptyArray()
        }
    }
}

/**
 * Fallback for remote exceptions whose original class could not be reconstructed.
 * Preserves the original `toString()`, message, stack trace and cause chain.
 */
class DeserializedException private constructor(
    private val toStringMessage: String,
    override val message: String?,
    stacktrace: List<StackElement>,
    override val cause: Throwable?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
    val className: String
) : Throwable() {

    constructor(
        toStringMessage: String,
        message: String?,
        stacktrace: List<StackElement>,
        cause: SerializedException?,
        className: String
    ) : this(toStringMessage, message, stacktrace, cause?.deserialize(), Unit, className)

    init {
        stackTrace = stacktrace.map { it.toStackTraceElement() }.toTypedArray()
    }

    override fun toString(): String = toStringMessage

    companion object {
        internal fun create(
            toStringMessage: String,
            message: String?,
            stacktrace: List<StackElement>,
            cause: Throwable?,
            className: String
        ): DeserializedException =
            DeserializedException(toStringMessage, message, stacktrace, cause, Unit, className)
    }
}
