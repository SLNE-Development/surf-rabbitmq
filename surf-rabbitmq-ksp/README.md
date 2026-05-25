# surf-rabbitmq-ksp

This module generates the RPC glue code for interfaces annotated with `@RpcService`.

The generated code is internal infrastructure. Users should only write the service interface and use the public client/server RPC API.

## Input

Given a service interface like this:

```kotlin
@RpcService
interface TestService {
    suspend fun test(
        param: Int,
        param2: List<List<List<Map<String, Int>>>>,
    ): String

    suspend fun unitTest(): Unit

    suspend fun paramWithAnnotation(
        param: @Contextual Int,
    ): @MyFancyTypAnnotation(
        "test",
        MyFancyAnnotation("test2", 2),
    ) @Serializable(with = TestStringSerializer::class) String
}
```

the KSP processor generates two declarations in the same package:

```text
TestServiceDescriptor
TestServiceClientImpl
```

## Generated descriptor

For every RPC service, the processor generates a descriptor object:

```kotlin
internal object TestServiceDescriptor : RabbitRpcServiceDescriptor<TestService> {
    override val simpleName: String = "TestService"

    override val fqName: String = "dev.slne.surf.rabbitmq.common.rpc.TestService"

    override val callables: Map<String, RabbitRpcCallable<TestService>> =
        createCallables()

    override fun getCallable(name: String): RabbitRpcCallable<TestService>? {
        return callables[name]
    }

    override fun createInstance(
        serviceId: Long,
        api: RabbitMQApi,
    ): TestService {
        return TestServiceClientImpl(serviceId, api, this)
    }
}
```

The descriptor contains all metadata needed by the RPC runtime:

* the simple service name
* the fully-qualified service name
* all callable methods
* parameter metadata
* return type metadata
* server-side invokers
* a factory for the generated client implementation

## Generated callables

Each service method becomes a `RabbitRpcCallable` entry.

Example:

```kotlin
private fun createCallables(): Map<String, RabbitRpcCallable<TestService>> {
    return mapOf(
        "test" to RabbitRpcCallableDefault<TestService>(
            name = "test",
            returnType = RabbitRpcTypeDefault(
                kType = typeOf<String>(),
                annotations = emptyList(),
            ),
            invoker = testInvoker,
            parameters = arrayOf(
                RabbitRpcParameterDefault(
                    name = "param",
                    type = RabbitRpcTypeDefault(
                        kType = typeOf<Int>(),
                        annotations = emptyList(),
                    ),
                    isOptional = false,
                    annotations = emptyList(),
                ),
                RabbitRpcParameterDefault(
                    name = "param2",
                    type = RabbitRpcTypeDefault(
                        kType = typeOf<List<List<List<Map<String, Int>>>>>(),
                        annotations = emptyList(),
                    ),
                    isOptional = false,
                    annotations = emptyList(),
                ),
            ),
        ),
    )
}
```

A callable describes one RPC method:

```kotlin
RabbitRpcCallableDefault<Service>(
    name = "methodName",
    returnType = ...,
    invoker = ...,
    parameters = ...,
)
```

## Generated parameters

Every method parameter becomes a `RabbitRpcParameterDefault`.

Example:

```kotlin
RabbitRpcParameterDefault(
    name = "param",
    type = RabbitRpcTypeDefault(
        kType = typeOf<@Contextual Int>(),
        annotations = listOf(
            Contextual(),
        ),
    ),
    isOptional = false,
    annotations = emptyList(),
)
```

There are two annotation locations:

```kotlin
fun foo(@A param: @B String)
```

Generated metadata:

```kotlin
RabbitRpcParameterDefault(
    annotations = listOf(A()),
    type = RabbitRpcTypeDefault(
        kType = typeOf<@B String>(),
        annotations = listOf(B()),
    ),
    ...
)
```

So:

* value-parameter annotations go into `RabbitRpcParameter.annotations`
* type-use annotations go into `RabbitRpcType.annotations`

## Generated return types

A normal return type generates `RabbitRpcTypeDefault`:

```kotlin
RabbitRpcTypeDefault(
    kType = typeOf<String>(),
    annotations = emptyList(),
)
```

An annotated return type keeps both the annotated `KType` and runtime annotation instances:

```kotlin
RabbitRpcTypeDefault(
    kType = typeOf<@MyFancyTypAnnotation("test", MyFancyAnnotation("test2", 2)) String>(),
    annotations = listOf(
        MyFancyTypAnnotation(
            value = "test",
            subAnnotation = MyFancyAnnotation(
                value = "test2",
                value2 = 2,
            ),
        ),
    ),
)
```

## Generated `@Serializable(with = ...)` support

If a type has `@Serializable(with = SomeSerializer::class)`, the processor generates `RabbitRpcTypeKrpc` instead of `RabbitRpcTypeDefault`.

Example input:

```kotlin
@Serializable(with = TestStringSerializer::class) String
```

Generated output:

```kotlin
RabbitRpcTypeKrpc(
    kType = typeOf<@Serializable(with = TestStringSerializer::class) String>(),
    annotations = listOf(
        Serializable(with = TestStringSerializer::class),
    ),
    serializers = mapOf(
        TestStringSerializer::class as KClass<KSerializer<Any?>> to
            TestStringSerializer as KSerializer<Any?>,
    ),
)
```

The runtime uses this serializer map when resolving serializers for RPC parameters and return values.

## Generated invokers

For every method, the descriptor contains a server-side invoker.

Example:

```kotlin
private val testInvoker: RabbitRpcInvoker<TestService> =
    RabbitRpcInvoker(::invokeTest)

@Suppress("UNCHECKED_CAST")
private suspend fun invokeTest(
    service: TestService,
    args: Array<Any?>,
): Any? {
    return service.test(
        args[0] as Int,
        args[1] as List<List<List<Map<String, Int>>>>,
    )
}
```

The invoker receives decoded arguments as `Array<Any?>` and calls the real service implementation.

## Generated client implementation

The generated client implementation implements the original service interface.

Example:

```kotlin
internal class TestServiceClientImpl(
    private val __serviceId__: Long,
    private val __api__: RabbitMQApi,
    private val __descriptor__: TestServiceDescriptor,
) : TestService {
    private val __emptyArray__: Array<Any?> = emptyArray()

    override suspend fun test(
        param: Int,
        param2: List<List<List<Map<String, Int>>>>,
    ): String {
        return __api__.rpcService.call(
            RabbitRpcCall(
                descriptor = __descriptor__,
                callableName = "test",
                serviceId = __serviceId__,
                arguments = arrayOf(
                    param,
                    param2,
                ),
            )
        )
    }

    override suspend fun unitTest(): Unit {
        return __api__.rpcService.call(
            RabbitRpcCall(
                descriptor = __descriptor__,
                callableName = "unitTest",
                serviceId = __serviceId__,
                arguments = __emptyArray__,
            )
        )
    }
}
```

The client implementation does not execute the service locally. It creates a `RabbitRpcCall` and passes it to `api.rpcService.call(...)`.

## Generated class visibility

Generated classes are internal and hidden from direct user usage.

The generated files use:

```kotlin
@OptIn(InternalRabbitMQ::class)
@Suppress("DEPRECATION_ERROR")
```

Generated implementation declarations are also marked as hidden deprecated declarations:

```kotlin
@Deprecated(
    message = "This synthesized declaration should not be used directly",
    level = DeprecationLevel.HIDDEN,
)
```

## Default parameters

Default parameters are detected and stored as metadata:

```kotlin
RabbitRpcParameterDefault(
    isOptional = true,
    ...
)
```

This does not mean the server applies Kotlin default arguments automatically.

The current generated invoker always reads from the argument array:

```kotlin
service.someMethod(args[0] as String)
```

So the client is expected to send a complete argument array.

## Current restrictions

* `@RpcService` can only be used on interfaces.
* RPC methods must be `suspend` functions.
* Method overloading is not supported because callables are keyed by method name.
* Server-side default argument application is not supported.
* Generated invokers use unchecked casts from decoded `Array<Any?>` values.
* Custom serializers from `@Serializable(with = ...)` must be instantiable as an object or a no-arg class.
