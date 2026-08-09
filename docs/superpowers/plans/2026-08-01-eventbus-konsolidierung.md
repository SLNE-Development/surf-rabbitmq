# surf-eventbus Konsolidierung Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do **not** use
> `superpowers:subagent-driven-development` — this repository's owner has forbidden subagents.

**Goal:** Aus zwei zusammengeschobenen Projekten eine API machen: ein Vertragsmodell statt zwei, ein
KSP-Prozessor statt zwei, ein Opt-in-Marker statt drei, Pakete, die nach ihrer Sache heißen — und
die offenen Tasks 4, 5 und 6 aus Plan 4 abschließen.

**Architecture:** `@QueryService` und `@RpcService` teilen sich ein transportneutrales
Vertragsmodell in `dev.slne.surf.eventbus.service`; jeder Transport erbt es und ergänzt nur seine
eigene Art, eine Client-Instanz zu bauen. Ein KSP-Prozessor liest beide Annotationen mit einer
Modellfabrik und schreibt über zwei Backends. Was ein Transport hat und der andere nicht —
Credentials, Konfigurationsschichten, Plattform-Instanz, Ausnahmewurzel — wird für beide gebaut.

**Tech Stack:** Kotlin 2.x, KSP2, KotlinPoet 2.3.0, kotlinx.serialization, Gradle mit
`dev.slne.surf.api.gradle.core`, JUnit 5, kotlin-compile-testing (`dev.zacsweers.kctfork:ksp`),
Testcontainers (RabbitMQ, Redis), Kotlin ABI Validation (`updateLegacyAbi`/`checkLegacyAbi`).

## Global Constraints

- **Voraussetzung:** Der Stand von Commit `7640a75` ist die Basis. `./gradlew compileKotlin
  compileTestKotlin` ist dort grün.
- **Docker ist auf dieser Maschine nicht erreichbar.** `@RequiresDocker`-Tests werden geschrieben
  und **nicht** ausgeführt. Keine Behauptung in dieser Arbeit darf so tun, als seien sie gelaufen;
  der Status ist „nicht verifiziert".
- **Kein fremdes Repository wird verändert** — insbesondere nicht surf-microservice, dessen
  `RabbitModule`-Enumeration veraltet ist, und nicht surf-database-r2dbc.
- **Version 2.0 ist nicht wire- und nicht ABI-kompatibel zu 1.6.x.** Der Paketumbau kostet deshalb
  nichts, was nicht schon bezahlt ist — aber jeder ABI-Dump wird nach dem Umbau neu erzeugt und
  gelesen, nicht blind überschrieben.
- **Ein Opt-in-Marker:** `dev.slne.surf.eventbus.InternalEventBusApi`. `InternalRabbitMQ` und
  `InternalRedisAPI` verschwinden restlos.
- **Umgebungspräfixe bleiben** `SURF_EVENTBUS_RABBITMQ_*` und `SURF_EVENTBUS_REDIS_*`. Eine gesetzte
  alte Variable ist ein Startfehler und bleibt es.
- **Nach jeder Task muss `./gradlew build -PskipIntegration` grün sein**, bevor committet wird.
- **`surf-eventbus-test` existiert nicht mehr.** Alles Testbare liegt in
  `surf-eventbus-api/src/test`, `surf-eventbus-core/src/test` und `surf-eventbus-ksp/src/test`.

---

## File Structure

**Neu:**

| Datei                                                            | Verantwortung                                                                                |
|------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `…-api/…/service/ServiceDescriptor.kt`                           | gemeinsamer Descriptor-Obertyp                                                               |
| `…-api/…/service/ServiceCallable.kt`                             | eine Methode eines Vertrags                                                                  |
| `…-api/…/service/ServiceParameter.kt`                            | ein Parameter, mit `isOptional` und Annotationen                                             |
| `…-api/…/service/ServiceType.kt`                                 | `KType` plus Typ-Annotationen                                                                |
| `…-api/…/service/ServiceInvoker.kt`                              | der generierte Aufrufhandle                                                                  |
| `…-api/…/service/ServiceDefaults.kt`                             | `ServiceCallableDefault`, `ServiceParameterDefault`, `ServiceTypeDefault`, `ServiceTypeKrpc` |
| `…-api/…/platform/EventBusInstance.kt`                           | die eine Plattform-SPI                                                                       |
| `…-api/…/platform/StandaloneLifecycleHook.kt`                    | Bus-weit statt Rabbit-only                                                                   |
| `…-api/…/exception/SurfEventBusException.kt`                     | Ausnahmewurzel                                                                               |
| `…-api/…/credentials/CredentialsProvider.kt`                     | gemeinsamer Obertyp der zwei Nahtstellen                                                     |
| `…-api/…/credentials/RabbitCredentialsProvider.kt`               | Rabbit-Zugangsdaten als Naht                                                                 |
| `…-core/…/redis/RedisRuntime.kt`                                 | Eventloop, Scheduler, Executor — aus `RedisInstance` gelöst                                  |
| `…-core/…/rabbitmq/audit/AuditReports.kt`                        | die fünf Meldepfade an einem Ort                                                             |
| `…-core/…/rabbitmq/credentials/RabbitCredentialsProviderImpl.kt` | Default aus der Konfiguration                                                                |
| `…-ksp/…/ksp/ServiceProcessor.kt`                                | ein Lauf über beide Annotationen                                                             |
| `…-ksp/…/ksp/model/ServiceModelFactory.kt`                       | gemeinsames Einlesen, zwei Regelwerke                                                        |
| `…-ksp/…/ksp/codegen/*Codegen.kt`                                | geteilte Emitter plus zwei Backends                                                          |
| `…-api/src/test/…/structure/PackageLayoutTest.kt`                | verbietet `common`, `rabbitmq.api`, kebab-case                                               |
| `…-api/src/test/…/structure/InternalNotInAbiTest.kt`             | ersetzt `AggregateCompletenessTest`                                                          |
| `…-core/src/test/…/testing/RequiresDocker.kt`                    | einmal statt zweimal                                                                         |
| `…-platform-*/…/{Paper,Velocity,Standalone}EventBusInstance.kt`  | eine Instanz je Plattform, beide Transporte                                                  |
| `docs/rollout-2.0.md`                                            | Rollout-Notiz                                                                                |

**Gelöscht:**

| Typ                                                                                           | Grund                                                |
|-----------------------------------------------------------------------------------------------|------------------------------------------------------|
| `query/callable/**`, `query/descriptor/**`                                                    | in `service/` aufgegangen                            |
| `rabbitmq/api/rpc/{callable,invoker,type}/**`, `rpc/descriptor/RabbitRpcServiceDescriptor.kt` | dito                                                 |
| `core/query/serialization/{QueryParametersSerializer,QuerySerializerCache}.kt`                | dito                                                 |
| `rabbitmq/api/InternalRabbitMQ.kt`, `redis/util/InternalRedisAPI.kt`                          | ein Marker                                           |
| `ksp/processor/query/**`, `rabbitmq/processor/**`                                             | ein Prozessor                                        |
| `PluginRabbitMQConfig.kt`                                                                     | in `RabbitMQConfig` aufgegangen                      |
| `rabbitmq/api/internal/RabbitMQInstance.kt`, `redis/RedisInstance.kt`                         | `EventBusInstance`                                   |
| `redis/testing/FakeRedisInstance.kt`                                                          | tot, referenziert nichts und wird nicht referenziert |
| `AggregateCompletenessTest.kt`                                                                | kann nicht fehlschlagen                              |
| `redis/testing/RequiresDocker.kt`                                                             | Dublette                                             |
| `rabbitmq/StandaloneRabbitMqInstance.kt` (in core)                                            | zieht ins Standalone-Plattform-Modul                 |

---

## Task 1: Ein Opt-in-Marker

Zuerst, weil dieser Schritt jede Datei berührt, die einen der drei Marker nennt — nach den
Umbenennungen wäre dieselbe Datei zweimal dran.

**Files:**

- Modify: `…-api/…/InternalEventBusApi.kt`
- Delete: `…-api/…/rabbitmq/api/InternalRabbitMQ.kt`, `…-api/…/redis/util/InternalRedisAPI.kt`
- Modify: `build.gradle.kts:28-34`, `surf-eventbus-api/build.gradle.kts:15-24`
- Modify: jede Datei, die `InternalRabbitMQ` oder `InternalRedisAPI` importiert oder nennt

**Interfaces:**

- Produces: `@InternalEventBusApi` mit `@InternalAPIMarker`, `RequiresOptIn.Level.ERROR` und den
  sechs Zielen, die `InternalRabbitMQ` heute hat.

- [ ] **Step 1: Den einen Marker schreiben**

`surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/InternalEventBusApi.kt`:

```kotlin
package dev.slne.surf.eventbus

import dev.slne.surf.api.shared.api.annotation.InternalAPIMarker

/**
 * Marks a declaration as internal to surf-eventbus.
 *
 * One marker for the whole project. Three of them — one per merged sub-project — meant a
 * consumer had to opt in three times to reach one coherent internal surface, and the three
 * disagreed on level, targets and even on what the project is called.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Internal to surf-eventbus. It can change in any release."
)
@InternalAPIMarker
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CONSTRUCTOR
)
annotation class InternalEventBusApi
```

- [ ] **Step 2: Die beiden anderen ersetzen**

```bash
git rm surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/InternalRabbitMQ.kt
git rm surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/redis/util/InternalRedisAPI.kt
```

Dann in jeder Kotlin-Datei unter `surf-eventbus-api/src`, `surf-eventbus-core/src`,
`surf-eventbus-ksp/src` und `surf-eventbus-platform/*/src`:

- `import dev.slne.surf.eventbus.rabbitmq.api.InternalRabbitMQ` →
  `import dev.slne.surf.eventbus.InternalEventBusApi`
- `import dev.slne.surf.eventbus.redis.util.InternalRedisAPI` → dito
- `@InternalRabbitMQ` / `@InternalRedisAPI` → `@InternalEventBusApi`
- `@OptIn(InternalRabbitMQ::class)` / `@OptIn(InternalRedisAPI::class)` →
  `@OptIn(InternalEventBusApi::class)`

Doppelte Importe und doppelte `@OptIn`-Argumente in derselben Datei danach entfernen.

Die Zeichenkette, die den Prozessor betrifft, nicht vergessen:
`surf-eventbus-ksp/…/rabbitmq/processor/Names.kt` nennt
`ClassNames.internalRabbitMqApi = ClassName("dev.slne.surf.eventbus.rabbitmq.api", "InternalRabbitMQ")`.
Sie zeigt danach auf `ClassName("dev.slne.surf.eventbus", "InternalEventBusApi")`.

- [ ] **Step 3: Die beiden Build-Dateien nachziehen**

`build.gradle.kts`, im `afterEvaluate`-Block:

```kotlin
        extensions.findByType<KotlinJvmExtension>()?.apply {
            compilerOptions {
                optIn.add("dev.slne.surf.eventbus.InternalEventBusApi")
            }
        }
```

`surf-eventbus-api/build.gradle.kts`:

```kotlin
kotlin {
    abiValidation {
        filters {
            exclude {
                annotatedWith.add("dev.slne.surf.eventbus.InternalEventBusApi")
            }
        }
    }
}
```

- [ ] **Step 4: Prüfen, dass keiner der alten Namen überlebt**

Run:
`grep -rn "InternalRabbitMQ\|InternalRedisAPI" --include=*.kt --include=*.kts . | grep -v /build/`
Expected: keine Ausgabe.

Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS.

- [ ] **Step 5: ABI neu erzeugen und lesen**

Run: `./gradlew updateLegacyAbi`

Dann `git diff surf-eventbus-api/api/surf-eventbus-api.api` lesen. Erwartet werden **nur**
Zeilen, die vorher durch `InternalRabbitMQ`/`InternalRedisAPI` gefiltert waren und jetzt durch
`InternalEventBusApi` gefiltert sind — also im Saldo nichts. Taucht eine bisher interne Klasse neu
im Dump auf, fehlt ihr die Annotation; nachtragen statt den Dump hinnehmen.

Run: `./gradlew checkLegacyAbi`
Expected: SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor!: one opt-in marker instead of three

InternalRabbitMQ, InternalRedisAPI and InternalEventBusApi disagreed on
level, on targets and on what this project is called. A consumer had to opt
in three times to reach one internal surface."
```

---

## Task 2: Ein Vertragsmodell für Query und RPC

**Files:**

- Create:
  `…-api/…/service/{ServiceDescriptor,ServiceCallable,ServiceParameter,ServiceType,ServiceInvoker,ServiceDefaults}.kt`
- Create: `…-api/src/test/…/service/ServiceDefaultsTest.kt`
- Modify: `…-api/…/query/descriptor/QueryServiceDescriptor.kt`,
  `…-api/…/rabbitmq/api/rpc/descriptor/RabbitRpcServiceDescriptor.kt`
- Delete:
  `…-api/…/query/callable/{QueryCallable,QueryCallableDefault,QueryInvoker,QueryParameter,QueryParameterDefault}.kt`
- Delete: `…-api/…/rabbitmq/api/rpc/callable/{RabbitRpcCallable,RabbitRpcCallableDefault}.kt`,
  `…/rpc/invoker/RabbitRpcInvoker.kt`,
  `…/rpc/type/{RabbitRpcParameter,RabbitRpcParameterDefault,RabbitRpcType,RabbitRpcTypeDefault,RabbitRpcTypeKrpc}.kt`
- Create: `…-core/…/service/serialization/{ParametersSerializer,ServiceSerializerCache}.kt`
- Delete: `…-core/…/core/query/serialization/{QueryParametersSerializer,QuerySerializerCache}.kt`,
  `…-core/…/rabbitmq/common/rpc/serialization/{CallableParametersSerializer,RpcSerializerCache}.kt`
- Modify: `…-core/…/core/dispatch/QueryDispatcher.kt`, `…-core/…/core/SurfEventBusImpl.kt`,
  `…-core/…/rabbitmq/rpc/RabbitRpcServiceImpl.kt`,
  `…-core/…/rabbitmq/rpc/service/RpcServiceExecutor.kt`,
  `…-core/…/rabbitmq/common/rpc/serialization/RpcSerializationUtils.kt`,
  `…-api/…/rabbitmq/api/SurfRabbitApi.kt`, `…-api/…/rabbitmq/api/rpc/RabbitRpcCall.kt`,
  `…-api/…/rabbitmq/api/rpc/RabbitRpcService.kt`
- Modify: `…-ksp/…/ksp/processor/query/Names.kt`, `…-ksp/…/rabbitmq/processor/Names.kt`,
  `…-ksp/…/rabbitmq/processor/rpc/codegen/{RpcCallableCodegen,RpcTypeCodegen}.kt`,
  `…-ksp/…/ksp/processor/query/codegen/QueryDescriptorCodegen.kt`

**Interfaces:**

- Consumes: `@InternalEventBusApi` aus Task 1.
- Produces:
    -
    `interface ServiceDescriptor<Service : Any> { val simpleName: String; val fqName: String; val callables: Map<String, ServiceCallable<Service>>; fun getCallable(name: String): ServiceCallable<Service>? }`
    -
    `interface ServiceCallable<Service : Any> { val name: String; val returnType: ServiceType; val invoker: ServiceInvoker<Service>; val parameters: Array<out ServiceParameter>; val fireAndForget: Boolean }`
    -
    `interface ServiceParameter { val name: String; val type: ServiceType; val isOptional: Boolean; val annotations: List<Annotation> }`
    - `interface ServiceType { val kType: KType; val annotations: List<Annotation> }`
    -
    `fun interface ServiceInvoker<Service : Any> { suspend fun call(service: Service, arguments: Array<Any?>): Any? }`
    -
    `class ServiceCallableDefault<Service : Any>(name, returnType, invoker, parameters, fireAndForget = false)`
    - `class ServiceParameterDefault(name, type, isOptional, annotations)`
    - `class ServiceTypeDefault(kType, annotations)`
    -
    `class ServiceTypeKrpc(kType, annotations, serializers: Map<KClass<out KSerializer<*>>, KSerializer<*>>)`
    -
    `interface QueryServiceDescriptor<Service : Any> : ServiceDescriptor<Service> { val timeoutMillis: Long; fun createInstance(instanceId: String, json: Json, transport: QueryTransport): Service }`
    -
    `interface RpcServiceDescriptor<Service : Any> : ServiceDescriptor<Service> { val defaultService: String; fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service }`
    -
    `class ParametersSerializer(callable: ServiceCallable<*>, module: SerializersModule) : KSerializer<Array<Any?>>`
    -
    `class ServiceSerializerCache { fun getParameterSerializer(callable, module): ParametersSerializer; fun getReturnTypeSerializer(callable, module): KSerializer<Any?> }`

- [ ] **Step 1: Test für die Standardimplementierungen schreiben**

`surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/service/ServiceDefaultsTest.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one contract model both `@QueryService` and `@RpcService` descriptors are built from.
 *
 * Query used a bare `KType` and RPC used its own type wrapper, so a query parameter could not
 * carry `@Contextual` or `@Serializable(with = ...)`. Both now carry annotations, and a query
 * callable is simply one whose `fireAndForget` is false.
 */
class ServiceDefaultsTest {

    private object Marker

    @Test
    fun `a callable defaults to expecting an answer`() {
        val callable = ServiceCallableDefault<Marker>(
            name = "findName",
            returnType = ServiceTypeDefault(typeOf<String?>(), emptyList()),
            invoker = ServiceInvoker { _, _ -> null },
            parameters = emptyArray()
        )

        assertFalse(callable.fireAndForget, "only @FireAndForget opts out of a reply")
        assertEquals("findName", callable.name)
    }

    @Test
    fun `a parameter keeps its type annotations`() {
        val annotation = Deprecated("x")
        val parameter = ServiceParameterDefault(
            name = "id",
            type = ServiceTypeDefault(typeOf<String>(), listOf(annotation)),
            isOptional = true,
            annotations = emptyList()
        )

        assertEquals(listOf(annotation), parameter.type.annotations)
        assertTrue(parameter.isOptional)
    }

    @Test
    fun `an invoker forwards its arguments`() = kotlinx.coroutines.runBlocking {
        val invoker = ServiceInvoker<Marker> { _, arguments -> arguments[0] }

        assertEquals("a", invoker.call(Marker, arrayOf<Any?>("a")))
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-api:test --tests '*ServiceDefaultsTest*'`
Expected: FAIL, „Unresolved reference: ServiceCallableDefault".

- [ ] **Step 3: Das Modell schreiben**

`…/service/ServiceType.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlin.reflect.KType

/**
 * A type as the generated descriptor sees it: the Kotlin type plus the annotations written on
 * it at the use site.
 *
 * The annotations are what make `@Contextual` and `@Serializable(with = ...)` reachable at
 * runtime. The query path used a bare `KType` and silently dropped them.
 */
@InternalEventBusApi
interface ServiceType {
    val kType: KType

    /** Annotations with target [AnnotationTarget.TYPE]. */
    val annotations: List<Annotation>
}
```

`…/service/ServiceParameter.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/** One parameter of a contract method, as seen by the generated descriptor. */
@InternalEventBusApi
interface ServiceParameter {
    val name: String
    val type: ServiceType

    /** Whether the method declares a default value, so the wire may omit it. */
    val isOptional: Boolean

    /** Annotations with target [AnnotationTarget.VALUE_PARAMETER]. */
    val annotations: List<Annotation>
}
```

`…/service/ServiceInvoker.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/** Invokes one generated method handle of a contract implementation. */
@InternalEventBusApi
fun interface ServiceInvoker<Service : Any> {
    suspend fun call(service: Service, arguments: Array<Any?>): Any?
}
```

`…/service/ServiceCallable.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/** One method of a `@QueryService` or `@RpcService` contract, generated by the KSP processor. */
@InternalEventBusApi
interface ServiceCallable<Service : Any> {
    val name: String
    val returnType: ServiceType
    val invoker: ServiceInvoker<Service>
    val parameters: Array<out ServiceParameter>

    /**
     * Whether this call is `@FireAndForget`: no reply is sent or awaited.
     *
     * Always `false` for a query — the processor rejects `@FireAndForget` there, because a
     * question without an answer is an event.
     */
    val fireAndForget: Boolean
}
```

`…/service/ServiceDescriptor.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi

/**
 * Generated once per contract interface, whatever transport carries it.
 *
 * What the two transports do *not* share is how a client instance comes to be — a query needs a
 * channel and a timeout, an RPC call needs a target service. That difference lives in the two
 * sub-interfaces, and nowhere else.
 */
@InternalEventBusApi
interface ServiceDescriptor<Service : Any> {
    val simpleName: String
    val fqName: String
    val callables: Map<String, ServiceCallable<Service>>

    fun getCallable(name: String): ServiceCallable<Service>?
}
```

`…/service/ServiceDefaults.kt`:

```kotlin
package dev.slne.surf.eventbus.service

import dev.slne.surf.eventbus.InternalEventBusApi
import kotlinx.serialization.KSerializer
import kotlin.reflect.KClass
import kotlin.reflect.KType

@InternalEventBusApi
class ServiceTypeDefault(
    override val kType: KType,
    override val annotations: List<Annotation>
) : ServiceType {
    override fun toString(): String = kType.toString()
}

/**
 * A [ServiceType] that also carries the serializer instances named by
 * `@Serializable(with = ...)` on this type, keyed by their class.
 */
@InternalEventBusApi
class ServiceTypeKrpc(
    override val kType: KType,
    override val annotations: List<Annotation>,
    val serializers: Map<KClass<out KSerializer<*>>, KSerializer<*>>,
) : ServiceType {
    override fun toString(): String = kType.toString()
}

@InternalEventBusApi
class ServiceParameterDefault(
    override val name: String,
    override val type: ServiceType,
    override val isOptional: Boolean,
    override val annotations: List<Annotation>
) : ServiceParameter

@InternalEventBusApi
class ServiceCallableDefault<Service : Any>(
    override val name: String,
    override val returnType: ServiceType,
    override val invoker: ServiceInvoker<Service>,
    override val parameters: Array<out ServiceParameter>,
    override val fireAndForget: Boolean = false
) : ServiceCallable<Service>
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-api:test --tests '*ServiceDefaultsTest*'`
Expected: PASS, alle drei.

- [ ] **Step 5: Die zwei Descriptor-Untertypen umschreiben**

`…-api/…/query/descriptor/QueryServiceDescriptor.kt` — `callables` und `getCallable` erbt es jetzt:

```kotlin
package dev.slne.surf.eventbus.query.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.service.ServiceDescriptor
import dev.slne.surf.eventbus.transport.QueryTransport
import kotlinx.serialization.json.Json

/**
 * Generated once per `@QueryService` interface.
 *
 * [fqName] is also the channel name (`RedisChannels.query(fqName)`) and the value
 * `QueryFrame.contract` carries — the two must agree, which is exactly what generating both
 * from the same descriptor guarantees.
 */
@InternalEventBusApi
interface QueryServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    val timeoutMillis: Long

    fun createInstance(instanceId: String, json: Json, transport: QueryTransport): Service
}
```

`…-api/…/rabbitmq/api/rpc/descriptor/RabbitRpcServiceDescriptor.kt` wird zu
`RpcServiceDescriptor` in derselben Datei-Position, mit demselben Muster:

```kotlin
package dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor

import dev.slne.surf.eventbus.InternalEventBusApi
import dev.slne.surf.eventbus.rabbitmq.api.SurfRabbitApi
import dev.slne.surf.eventbus.rabbitmq.api.target.RabbitTarget
import dev.slne.surf.eventbus.service.ServiceDescriptor

@InternalEventBusApi
interface RpcServiceDescriptor<Service : Any> : ServiceDescriptor<Service> {
    /**
     * The service declared by `@RpcService(service = ...)`, or an empty string if none was
     * given, in which case `rpc(...)` requires an explicit service.
     */
    val defaultService: String

    fun createInstance(serviceId: Long, api: SurfRabbitApi, target: RabbitTarget): Service
}
```

Datei umbenennen: `RabbitRpcServiceDescriptor.kt` → `RpcServiceDescriptor.kt`.

- [ ] **Step 6: Die alten Typen löschen und die Aufrufstellen nachziehen**

```bash
git rm -r surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/query/callable
git rm -r surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/rpc/callable
git rm -r surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/rpc/invoker
git rm -r surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/api/rpc/type
```

Ersetzungen in jeder verbleibenden Datei:

| Alt                                                  | Neu                       |
|------------------------------------------------------|---------------------------|
| `QueryCallable<S>`, `RabbitRpcCallable<S>`           | `ServiceCallable<S>`      |
| `QueryCallableDefault`, `RabbitRpcCallableDefault`   | `ServiceCallableDefault`  |
| `QueryParameter`, `RabbitRpcParameter`               | `ServiceParameter`        |
| `QueryParameterDefault`, `RabbitRpcParameterDefault` | `ServiceParameterDefault` |
| `QueryInvoker<S>`, `RabbitRpcInvoker<S>`             | `ServiceInvoker<S>`       |
| `RabbitRpcType`                                      | `ServiceType`             |
| `RabbitRpcTypeDefault`                               | `ServiceTypeDefault`      |
| `RabbitRpcTypeKrpc`                                  | `ServiceTypeKrpc`         |
| `RabbitRpcServiceDescriptor<S>`                      | `RpcServiceDescriptor<S>` |

Zwei Stellen brauchen mehr als eine Umbenennung, weil der Query-Pfad bisher rohe `KType`
benutzte:

- `QueryDispatcher` und `QueryServiceRegistry` lesen `callable.returnType` und
  `parameter.type` als `KType`. Danach heißt es `callable.returnType.kType` und
  `parameter.type.kType`.
- `RabbitRpcService.serviceDescriptorOf` gibt `RpcServiceDescriptor<Service>` zurück.

- [ ] **Step 7: Kompilieren**

Run: `./gradlew compileKotlin`
Expected: SUCCESS. Fehler hier sind übersehene Aufrufstellen; sie werden benannt.

- [ ] **Step 8: Die zwei Serializer zu einem machen**

`…-core/src/main/kotlin/dev/slne/surf/eventbus/service/serialization/ParametersSerializer.kt` — die
RPC-Fassung gewinnt, weil sie eine Obermenge ist. Sie entsteht durch Umbenennen von
`CallableParametersSerializer` und Ersetzen des Typparameters:

```kotlin
package dev.slne.surf.eventbus.service.serialization

import dev.slne.surf.eventbus.service.ServiceCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.*
import kotlinx.serialization.modules.SerializersModule

/**
 * Encodes the argument array of one contract callable as a single object.
 *
 * One class for queries and for RPC. They had two, differing in whether an optional parameter
 * may be omitted and in whether contextual serializers are consulted — differences that were
 * accidents of which sub-project wrote them, not of what the transports need.
 */
class ParametersSerializer(
    private val callable: ServiceCallable<*>,
    private val module: SerializersModule
) : KSerializer<Array<Any?>> {
    // body: CallableParametersSerializer's, with RabbitRpcCallable replaced by ServiceCallable
}
```

Der Rumpf wird 1:1 aus `CallableParametersSerializer` übernommen — einschließlich
`module.buildContextual(...)`, `isOptional` im Descriptor und der
`!seen[i] && !parameter.isOptional`-Prüfung.
`RpcSerializationUtils.buildContextual` zieht mit nach `service/serialization/`.

`…/service/serialization/ServiceSerializerCache.kt`:

```kotlin
package dev.slne.surf.eventbus.service.serialization

import dev.slne.surf.eventbus.service.ServiceCallable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.modules.SerializersModule
import java.util.concurrent.ConcurrentHashMap

/** Caches the argument and return-type serializers of one contract callable. */
class ServiceSerializerCache {
    private val parameterCache = ConcurrentHashMap<ServiceCallable<*>, ParametersSerializer>()
    private val returnTypeCache = ConcurrentHashMap<ServiceCallable<*>, KSerializer<Any?>>()

    fun getParameterSerializer(
        callable: ServiceCallable<*>,
        module: SerializersModule
    ): ParametersSerializer =
        parameterCache.computeIfAbsent(callable) { ParametersSerializer(callable, module) }

    fun getReturnTypeSerializer(
        callable: ServiceCallable<*>,
        module: SerializersModule
    ): KSerializer<Any?> =
        returnTypeCache.computeIfAbsent(callable) { module.buildContextual(callable.returnType) }
}
```

```bash
git rm surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/query/serialization/QueryParametersSerializer.kt
git rm surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/core/query/serialization/QuerySerializerCache.kt
git rm surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/common/rpc/serialization/CallableParametersSerializer.kt
git rm surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/common/rpc/serialization/RpcSerializerCache.kt
```

- [ ] **Step 9: Die Namen im Codegen nachziehen**

Damit der Build wieder durchläuft, zeigen die beiden `ClassNames`-Objekte im KSP-Modul auf die neuen
Typen. Das ist Zwischenarbeit — Task 3 legt beide Objekte ohnehin zusammen —, aber ohne sie
kompiliert nichts:

`…-ksp/…/ksp/processor/query/Names.kt`:

```kotlin
    val queryServiceDescriptor = ClassName("dev.slne.surf.eventbus.query.descriptor", "QueryServiceDescriptor")
    val serviceInvoker = ClassName("dev.slne.surf.eventbus.service", "ServiceInvoker")
    val serviceCallable = ClassName("dev.slne.surf.eventbus.service", "ServiceCallable")
    val serviceCallableDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceCallableDefault")
    val serviceParameter = ClassName("dev.slne.surf.eventbus.service", "ServiceParameter")
    val serviceParameterDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceParameterDefault")
    val serviceTypeDefault = ClassName("dev.slne.surf.eventbus.service", "ServiceTypeDefault")
    val serviceSerializerCache = ClassName("dev.slne.surf.eventbus.service.serialization", "ServiceSerializerCache")
```

`…-ksp/…/rabbitmq/processor/Names.kt` bekommt dieselben Einträge, plus
`rpcServiceDescriptor = ClassName("dev.slne.surf.eventbus.rabbitmq.api.rpc.descriptor", "RpcServiceDescriptor")`
und `serviceTypeKrpc = ClassName("dev.slne.surf.eventbus.service", "ServiceTypeKrpc")`.

`QueryDescriptorCodegen` emittiert jetzt zusätzlich einen `ServiceTypeDefault` um jeden `KType`
— dort, wo bisher `%M<%T>()` (`typeOf`) direkt als `returnType` beziehungsweise `type` gesetzt
wurde, steht danach `%T(%M<%T>(), %M())` mit `ClassNames.serviceTypeDefault`,
`MemberNames.kotlinTypeOf` und `MemberNames.emptyList`. `simpleName` kommt als neue
`override`-Property dazu, wie im RPC-Descriptor.

- [ ] **Step 10: Alles laufen lassen**

Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS. Der Round-Trip-Test `RpcProxyRoundTripTest` und
`QueryServiceClientTest` sind hier die Wächter: sie fahren einen echten generierten Proxy.

Run: `./gradlew :surf-eventbus-ksp:test`
Expected: PASS — `FireAndForgetValidationTest` und `QueryServiceValidationTest` unverändert grün.

- [ ] **Step 11: ABI und Commit**

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

```bash
git add -A
git commit -m "refactor!: one contract model for @QueryService and @RpcService

Descriptor, callable, parameter, type and invoker existed twice, once per
transport, differing in names and in nothing else. Query parameters gain
type annotations along the way, so @Contextual now reaches them."
```

---

## Task 3: Ein KSP-Prozessor

**Files:**

- Create: `…-ksp/…/ksp/{ServiceProcessor,ServiceProcessorProvider}.kt`
- Create:
  `…-ksp/…/ksp/model/{ServiceModel,ServiceFunctionModel,ServiceParameterModel,ServiceModelFactory,ContractKind}.kt`
- Create:
  `…-ksp/…/ksp/codegen/{Names,CallableCodegen,TypeCodegen,InvokerCodegen,QueryDescriptorCodegen,QueryClientCodegen,RpcDescriptorCodegen,RpcClientCodegen,RpcAnnotationCodegen}.kt`
- Delete: `…-ksp/…/ksp/processor/**`, `…-ksp/…/rabbitmq/**`
- Modify:
  `…-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`
- Modify: `…-ksp/src/test/…/CompilationSupport.kt`
- Create: `…-ksp/src/test/…/OneProcessorTest.kt`

**Interfaces:**

- Consumes: das Vertragsmodell aus Task 2.
- Produces:
    - `enum class ContractKind { QUERY, RPC }`
    -
    `class ServiceModel(kind, serviceClassName, descriptorClassName, clientClassName, packageName, simpleName, fqName, defaultService, timeoutMillis, functions, containingFile)`
    -
    `class ServiceModelFactory(logger: KSPLogger) { fun create(declaration: KSClassDeclaration, kind: ContractKind): ServiceModel? }`
    - `class ServiceProcessorProvider : SymbolProcessorProvider` — der **einzige** Eintrag in
      `META-INF/services`

- [ ] **Step 1: Test schreiben, der einen Prozessor verlangt**

`surf-eventbus-ksp/src/test/kotlin/dev/slne/surf/eventbus/ksp/OneProcessorTest.kt`:

```kotlin
package dev.slne.surf.eventbus.ksp

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One processor reads both annotations.
 *
 * Two providers meant two resolver walks over the same file and two copies of every helper.
 * They also meant a consumer's `ksp(...)` line silently attached two processors, which is what
 * the KSP2 lifetime bug in the test doubles is downstream of.
 */
class OneProcessorTest {

    @Test
    fun `exactly one processor provider is registered`() {
        val registration = checkNotNull(
            javaClass.classLoader.getResource(
                "META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider"
            )
        ) { "the processor is not registered at all" }

        val entries = registration.readText()
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toList()

        assertEquals(
            listOf("dev.slne.surf.eventbus.ksp.ServiceProcessorProvider"),
            entries
        )
    }

    @Test
    fun `both contract kinds compile in one pass over one file`() {
        val result = compile(
            """
            import dev.slne.surf.eventbus.query.QueryService
            import dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService

            @QueryService
            interface Asks {
                suspend fun whoOwns(id: String): String?
            }

            @RpcService(service = "svc")
            interface Calls {
                suspend fun rename(id: String, name: String)
            }
            """.trimIndent()
        )

        assertTrue(result.succeeded, result.messages)
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test --tests '*OneProcessorTest*'`
Expected: FAIL im ersten Test — die Datei nennt zwei Einträge.

- [ ] **Step 3: Modell und Regelwerke schreiben**

`…/ksp/model/ContractKind.kt`:

```kotlin
package dev.slne.surf.eventbus.ksp.model

/**
 * Which promise a contract makes.
 *
 * The two differ in validation and in what is generated, not in how they are read — which is
 * why one factory serves both and takes this as a parameter.
 */
enum class ContractKind(val annotationFqName: String, val annotationSimpleName: String) {
    QUERY("dev.slne.surf.eventbus.query.QueryService", "QueryService"),
    RPC("dev.slne.surf.eventbus.rabbitmq.api.rpc.RpcService", "RpcService")
}
```

`…/ksp/model/ServiceModel.kt` vereint `QueryServiceModel` und `RpcServiceModel`:

```kotlin
package dev.slne.surf.eventbus.ksp.model

import com.google.devtools.ksp.symbol.KSFile
import com.squareup.kotlinpoet.ClassName

class ServiceModel(
    val kind: ContractKind,
    val serviceClassName: ClassName,
    val descriptorClassName: ClassName,
    val clientClassName: ClassName,
    val packageName: String,
    val simpleName: String,
    val fqName: String,
    /** `@RpcService(service = ...)`, empty for a query. */
    val defaultService: String,
    /** `@QueryService(timeoutMillis = ...)`, 0 for RPC. */
    val timeoutMillis: Long,
    val functions: List<ServiceFunctionModel>,
    val containingFile: KSFile,
)
```

`ServiceFunctionModel` und `ServiceParameterModel` entstehen aus `RpcFunctionModel` und dessen
Parameter-Träger; sie behalten `typeParameterResolver`, `parameters`, `returnType`, `name`,
`invokerName` und ergänzen `fireAndForget: Boolean` (für Queries immer `false`).

`ServiceModelFactory` entsteht aus `RpcServiceModelFactory` — der reicheren der beiden — und bekommt
am Anfang von `create` die kind-abhängige Validierung:

```kotlin
    fun create(declaration: KSClassDeclaration, kind: ContractKind): ServiceModel? {
        if (declaration.classKind != ClassKind.INTERFACE) {
            logger.error("${kind.annotationSimpleName} is only applicable to interfaces", declaration)
            return null
        }
        // Reading the declaration is identical for both kinds and is taken verbatim from
        // RpcServiceModelFactory.create: typeParameterResolver, getDeclaredFunctions filtered
        // to abstract members, and the per-function parameter/return-type mapping.
        val typeParameterResolver = declaration.typeParameters.toTypeParameterResolver()
        val functions = declaration.getDeclaredFunctions()
            .filter { it.isAbstract }
            .map { readFunction(it, typeParameterResolver, kind) }
            .toList()

        val rules = when (kind) {
            ContractKind.QUERY -> QueryRules
            ContractKind.RPC -> RpcRules
        }
        if (!rules.validate(declaration, functions, logger)) return null

        return ServiceModel(
            kind = kind,
            serviceClassName = declaration.toClassName(),
            descriptorClassName = ClassName(packageName, "${simpleName}Descriptor"),
            clientClassName = ClassName(packageName, "${simpleName}ClientImpl"),
            packageName = packageName,
            simpleName = simpleName,
            fqName = declaration.qualifiedName!!.asString(),
            defaultService = if (kind == ContractKind.RPC) readServiceAttribute(declaration) else "",
            timeoutMillis = if (kind == ContractKind.QUERY) readTimeoutAttribute(declaration) else 0L,
            functions = functions,
            containingFile = declaration.containingFile!!,
        )
    }
```

`readFunction`, `readServiceAttribute` und `readTimeoutAttribute` sind die entsprechenden privaten
Helfer aus `RpcServiceModelFactory` beziehungsweise `QueryServiceModelFactory`, unverändert
übernommen; `readFunction` setzt `fireAndForget` nur für `ContractKind.RPC` und sonst `false`.

`QueryRules` trägt die bestehenden Prüfungen aus `QueryServiceModelFactory`: jede Methode
`suspend`, jeder Rückgabetyp nullable, `@FireAndForget` abgelehnt, kein privater Vertrag.
`RpcRules` trägt die aus `RpcServiceModelFactory`: jede Methode `suspend`, `@FireAndForget` nur mit
`Unit`-Rückgabe. Beide implementieren

```kotlin
interface ContractRules {
    fun validate(
        declaration: KSClassDeclaration,
        functions: List<ServiceFunctionModel>,
        logger: KSPLogger
    ): Boolean
}
```

- [ ] **Step 4: Prozessor und Provider schreiben**

`…/ksp/ServiceProcessor.kt`:

```kotlin
package dev.slne.surf.eventbus.ksp

import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate
import dev.slne.surf.eventbus.ksp.codegen.QueryDescriptorCodegen
import dev.slne.surf.eventbus.ksp.codegen.RpcDescriptorCodegen
import dev.slne.surf.eventbus.ksp.model.ContractKind
import dev.slne.surf.eventbus.ksp.model.ServiceModelFactory

class ServiceProcessor(environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private val logger = environment.logger
    private val modelFactory = ServiceModelFactory(logger)
    private val queryCodegen = QueryDescriptorCodegen(environment.codeGenerator)
    private val rpcCodegen = RpcDescriptorCodegen(environment.codeGenerator)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val deferred = mutableListOf<KSAnnotated>()

        for (kind in ContractKind.entries) {
            resolver.getSymbolsWithAnnotation(kind.annotationFqName).forEach { declaration ->
                if (declaration !is KSClassDeclaration) {
                    logger.error(
                        "${kind.annotationSimpleName} is only applicable to interfaces, " +
                                "but was found on $declaration",
                        declaration
                    )
                    return@forEach
                }

                if (!declaration.validate()) {
                    deferred += declaration
                    return@forEach
                }

                val model = modelFactory.create(declaration, kind) ?: return@forEach

                when (kind) {
                    ContractKind.QUERY -> queryCodegen.generate(model)
                    ContractKind.RPC -> rpcCodegen.generate(model)
                }
            }
        }

        return deferred
    }
}
```

`…/ksp/ServiceProcessorProvider.kt`:

```kotlin
package dev.slne.surf.eventbus.ksp

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class ServiceProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        ServiceProcessor(environment)
}
```

- [ ] **Step 5: Codegen zusammenlegen**

Ein `…/ksp/codegen/Names.kt` hält `Names`, `ClassNames`, `MemberNames` und `Types` — die Vereinigung
der beiden heutigen Dateien, ohne die Dubletten (`kotlinTypeOf`, `arrayOf`, `emptyArray`, `mapOf`,
`emptyMap`, `kotlinKClass`, `kotlinArray`,
`anyNullableArray`, `optIn`).

Geteilte Emitter, aus den RPC-Fassungen gezogen, weil sie Annotationen und `isOptional` schon
können:

- `TypeCodegen.serviceType(model, type): CodeBlock` — emittiert `ServiceTypeDefault` oder
  `ServiceTypeKrpc`.
- `ParameterCodegen.parameters(function): CodeBlock` — emittiert das
  `arrayOf(ServiceParameterDefault(...))`.
- `InvokerCodegen.invokerProperty(model, function): PropertySpec`.
- `CallableCodegen.callablesProperty(model): PropertySpec` — emittiert die
  `mapOf(name to ServiceCallableDefault(...))`.

Die vier Backends benutzen sie: `QueryDescriptorCodegen` und `QueryClientCodegen` erzeugen
`fqName`, `simpleName`, `timeoutMillis`, `createInstance(instanceId, json, transport)`;
`RpcDescriptorCodegen` und `RpcClientCodegen` erzeugen `fqName`, `simpleName`, `defaultService`,
`createInstance(serviceId, api, target)`. `RpcAnnotationCodegen` zieht unverändert mit um.

```bash
git rm -r surf-eventbus-ksp/src/main/kotlin/dev/slne/surf/eventbus/ksp/processor
git rm -r surf-eventbus-ksp/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq
```

- [ ] **Step 6: Registrierung und Testhilfe nachziehen**

`surf-eventbus-ksp/src/main/resources/META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider`:

```
dev.slne.surf.eventbus.ksp.ServiceProcessorProvider
```

`CompilationSupport.kt`:

```kotlin
        configureKsp {
            symbolProcessorProviders.add(ServiceProcessorProvider())
        }
```

- [ ] **Step 7: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-ksp:test`
Expected: PASS — `OneProcessorTest`, `FireAndForgetValidationTest`, `QueryServiceValidationTest`.

Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS. `RpcProxyRoundTripTest` und `QueryServiceClientTest` beweisen, dass die
generierten Proxys weiter funktionieren.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: one KSP processor for both contract annotations

Two providers meant two resolver walks over the same file, two Names
objects, two model factories and two descriptor codegens. One walk, one
model, two backends where the output genuinely differs."
```

---

## Task 4: Pakete und Dateinamen

Rein mechanisch, aber breit — deshalb nach den inhaltlichen Änderungen und mit einem Test, der den
Zustand danach festhält.

**Files:**

- Create: `…-api/src/test/…/structure/PackageLayoutTest.kt`
- Move: siehe Tabellen unten
- Modify: `build.gradle.kts` (`buildConfig`-Paket), `surf-eventbus-api/build.gradle.kts`
  (`buildConfig.forClass`), `…-platform-*/build.gradle.kts` (`mainClass`, `bootstrapper`)

**Interfaces:**

- Produces: keine neuen Typen. Alle Typen aus Tasks 1–3 unter ihren neuen Paketnamen.

- [ ] **Step 1: Den Test schreiben, der den Zielzustand beschreibt**

`surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/structure/PackageLayoutTest.kt`:

```kotlin
package dev.slne.surf.eventbus.structure

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The package layout the merge was supposed to produce.
 *
 * Three names survived the merge and describe the old module structure rather than the code:
 * `common` (a drawer, not a subject), `rabbitmq.api` (an `.api` infix Redis never had), and
 * kebab-case file names. Each of them compiles fine, which is exactly why only a test notices.
 */
class PackageLayoutTest {

    private val repositoryRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun sourceFiles(): List<File> = repositoryRoot.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .filterNot { it.path.contains("${File.separator}build${File.separator}") }
        .toList()

    @Test
    fun `no package is called common`() {
        val offenders = sourceFiles()
            .filter { it.path.contains("${File.separator}common${File.separator}") }
            .map { it.relativeTo(repositoryRoot).path }

        assertTrue(offenders.isEmpty(), "'common' names a drawer, not a subject:\n$offenders")
    }

    @Test
    fun `no package under rabbitmq is called api`() {
        val offenders = sourceFiles()
            .filter { it.path.contains("rabbitmq${File.separator}api${File.separator}") }
            .map { it.relativeTo(repositoryRoot).path }

        assertTrue(offenders.isEmpty(), "redis has no .api infix; rabbitmq must not either:\n$offenders")
    }

    @Test
    fun `every source file name is UpperCamelCase`() {
        val offenders = sourceFiles()
            .filterNot { it.nameWithoutExtension.matches(Regex("[A-Z][A-Za-z0-9]*")) }
            .map { it.relativeTo(repositoryRoot).path }

        if (offenders.isNotEmpty()) fail("not UpperCamelCase:\n" + offenders.joinToString("\n"))
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-api:test --tests '*PackageLayoutTest*'`
Expected: FAIL, alle drei — mit der vollständigen Liste dessen, was umzuziehen ist.

- [ ] **Step 3: `surf-eventbus-api` umziehen**

| Von                                                                       | Nach                                   |
|---------------------------------------------------------------------------|----------------------------------------|
| `rabbitmq/api/*.kt`                                                       | `rabbitmq/`                            |
| `rabbitmq/api/{connection,exception,identity,packet,rpc,target,version}/` | `rabbitmq/{…}/`                        |
| `rabbitmq/api/internal/config/`                                           | `rabbitmq/config/`                     |
| `rabbitmq/api/internal/StandaloneLifecycleHook.kt`                        | `platform/StandaloneLifecycleHook.kt`  |
| `rabbitmq/api/internal/RabbitMQInstance.kt`                               | bleibt vorerst, verschwindet in Task 5 |
| `core/envelope/EventEnvelope.kt`                                          | `transport/EventEnvelope.kt`           |
| `common/circuitbreaker/`                                                  | `circuitbreaker/`                      |
| `common/serialization/`                                                   | `serialization/`                       |
| `common/platform/`                                                        | `platform/`                            |
| `common/config/LegacyEnvironmentGuard.kt`                                 | `config/LegacyEnvironmentGuard.kt`     |

Die Testquellen ziehen mit: `src/test/…/common/circuitbreaker/` → `src/test/…/circuitbreaker/`,
`src/test/…/common/config/` → `src/test/…/config/`,
`src/test/…/common/{PackageNamingTest,RelocationBaseTest}.kt` → `src/test/…/structure/`,
`src/test/…/core/envelope/EventEnvelopeTest.kt` → `src/test/…/transport/EventEnvelopeTest.kt`,
`src/test/…/rabbitmq/api/**` → `src/test/…/rabbitmq/**`.

- [ ] **Step 4: `surf-eventbus-core` umziehen**

| Von                                                          | Nach                                 |
|--------------------------------------------------------------|--------------------------------------|
| `rabbitmq/common/connection/client/`                         | `rabbitmq/connection/`               |
| `rabbitmq/common/connection/consumer/`                       | `rabbitmq/consumer/`                 |
| `rabbitmq/common/connection/publisher/`                      | `rabbitmq/publisher/`                |
| `rabbitmq/common/connection/*.kt`                            | `rabbitmq/connection/`               |
| `rabbitmq/common/health/`                                    | `rabbitmq/health/`                   |
| `rabbitmq/common/packet/`                                    | `rabbitmq/packet/`                   |
| `rabbitmq/common/rpc/{exception,packet}/`                    | `rabbitmq/rpc/{exception,packet}/`   |
| `rabbitmq/common/rpc/serialization/RpcSerializationUtils.kt` | `service/serialization/` (Task 2)    |
| `rabbitmq/common/topology/`                                  | `rabbitmq/topology/`                 |
| `rabbitmq/core/connection/`                                  | `rabbitmq/connection/`               |
| `rabbitmq/core/publish/MessageKind.kt`                       | `rabbitmq/publisher/MessageKind.kt`  |
| `rabbitmq/core/retry/`                                       | `rabbitmq/retry/`                    |
| `rabbitmq/core/rpc/BreakerGuardedRpc.kt`                     | `rabbitmq/rpc/BreakerGuardedRpc.kt`  |
| `rabbitmq/listener/`                                         | `rabbitmq/consumer/`                 |
| `rabbitmq/rpc/service/RpcServiceExecutor.kt`                 | `rabbitmq/rpc/RpcServiceExecutor.kt` |
| `rabbitmq/shared/serialization/`                             | `serialization/`                     |
| `RedisTransportLocator.kt` (Paketwurzel)                     | `core/RedisTransportLocator.kt`      |

Die Testquellen spiegeln das: `rabbitmq/common/**` → `rabbitmq/**`, `rabbitmq/core/**` →
`rabbitmq/**`, wobei `rabbitmq/common/testing/` zu `rabbitmq/testing/` wird.

- [ ] **Step 5: Dateinamen richtigstellen**

| Von                                   | Nach                                            |
|---------------------------------------|-------------------------------------------------|
| `redis/codec/byte-buf-extensions.kt`  | `redis/codec/ByteBufExtensions.kt`              |
| `redis/codec/codec-extension.kt`      | `redis/codec/CodecExtensions.kt`                |
| `redis/util/util.kt`                  | `redis/util/RedisUtils.kt`                      |
| `rabbitmq/exception/api.kt`           | `rabbitmq/exception/ApiExceptions.kt`           |
| `rabbitmq/exception/connection.kt`    | `rabbitmq/exception/ConnectionExceptions.kt`    |
| `rabbitmq/exception/packet.kt`        | `rabbitmq/exception/PacketExceptions.kt`        |
| `rabbitmq/exception/protocol.kt`      | `rabbitmq/exception/ProtocolExceptions.kt`      |
| `rabbitmq/exception/request.kt`       | `rabbitmq/exception/RequestExceptions.kt`       |
| `rabbitmq/exception/serialization.kt` | `rabbitmq/exception/SerializationExceptions.kt` |

`redis/codec/default/` heißt weiter so — `default` ist hier ein Sachbegriff (die mitgelieferten
Codecs), kein Schubladenname, und `PackageLayoutTest` verbietet ihn nicht.

- [ ] **Step 6: Die Build-Dateien nachziehen**

`surf-eventbus-api/build.gradle.kts`:

```kotlin
buildConfig {
    forClass("dev.slne.surf.eventbus.rabbitmq.version", "BuildVersion") {
        buildConfigField("VERSION", provider { version.toString() })
    }
}
```

In den Plattform-Modulen bleiben `mainClass`/`bootstrapper` vorerst unverändert — ihre Klassen
ziehen erst in Task 7 um.

- [ ] **Step 7: Test und Build laufen lassen**

Run: `./gradlew :surf-eventbus-api:test --tests '*PackageLayoutTest*'`
Expected: PASS, alle drei.

Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS.

- [ ] **Step 8: ABI und Commit**

Run: `./gradlew updateLegacyAbi`

`git diff surf-eventbus-api/api/surf-eventbus-api.api` lesen: erwartet werden ausschließlich
Paketumbenennungen. Eine Klasse, die neu **auftaucht** oder **verschwindet**, ist ein Fehler im
Umzug, kein Ergebnis davon.

Run: `./gradlew checkLegacyAbi`
Expected: SUCCESS.

```bash
git add -A
git commit -m "refactor!: name packages after their subject, not after old modules

'common' was a drawer. 'rabbitmq.api' carried an .api infix that redis never
had, inside a module already called -api. 'core' sat inside the api module.
PackageLayoutTest keeps all three from coming back."
```

---

## Task 5: Symmetrie zwischen den Transporten

**Files:**

- Create: `…-api/…/platform/EventBusInstance.kt`
- Create: `…-api/…/exception/SurfEventBusException.kt`
- Create: `…-api/…/credentials/{CredentialsProvider,RabbitCredentialsProvider}.kt`
- Move: `…-api/…/redis/credentials/RedisCredentialsProvider.kt` → `…-api/…/credentials/`
- Create: `…-core/…/redis/RedisRuntime.kt`
- Create: `…-core/…/rabbitmq/credentials/RabbitCredentialsProviderImpl.kt`
- Modify:
  `…-api/…/rabbitmq/config/{RabbitMQConfig,CommonRabbitMQConfig,RabbitEnvironment,RabbitMQEnvironmentConfig}.kt`
- Delete: `…-api/…/rabbitmq/config/PluginRabbitMQConfig.kt`,
  `…-api/…/rabbitmq/internal/RabbitMQInstance.kt`, `…-core/…/redis/RedisInstance.kt`
- Modify: `…-core/…/redis/config/{RedisConfig,RedisConfigResolver}.kt`,
  `…-core/…/redis/credentials/RedisCredentialsProviderImpl.kt`
- Modify: `…-api/…/redis/codec/RedisCodecException.kt`, alle `SurfRabbitException`-Dateien
- Create: `…-core/src/test/…/redis/credentials/RedisUriTest.kt`
- Modify: `…-api/src/test/…/rabbitmq/config/RabbitEnvironmentTest.kt`,
  `…-core/src/test/…/redis/config/RedisConfigLayeringTest.kt`

**Interfaces:**

- Consumes: `@InternalEventBusApi`, die Paketnamen aus Task 4.
- Produces:
    -
    `interface EventBusInstance { val dataPath: Path; fun getResourceAsStream(name: String): InputStream?; fun tryExtractPluginName(clazz: Class<*>): String; companion object { val instance: EventBusInstance } }`
    -
    `class RedisRuntime(instance: EventBusInstance) { val eventLoopGroup; val redissonExecutorService; val streamPollScheduler; val ttlRefreshScheduler; fun load(); fun disable() }`
    - `abstract class SurfEventBusException(message: String, cause: Throwable?) : RuntimeException`
    - `interface CredentialsProvider`
    - `interface RedisCredentialsProvider : CredentialsProvider { fun redisURI(): RedisURI }`
    -
    `interface RabbitCredentialsProvider : CredentialsProvider { fun connectionFactory(config: CommonRabbitMQConfig): ConnectionFactory }`
    - `data class RabbitMQConfig(...)` mit Sentinel-Typen und
      `companion object Global : SpongeYmlConfigClass<RabbitMQConfig>` sowie
      `object Plugin : SpongeYmlConfigClass<RabbitMQConfig>`

- [ ] **Step 1: Den URI-Fehler festnageln**

`surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/redis/credentials/RedisUriTest.kt`:

```kotlin
package dev.slne.surf.eventbus.redis.credentials

import dev.slne.surf.eventbus.redis.config.RedisConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A password belongs after the colon, not before the at-sign.
 *
 * `redis://secret@host` names a *user* called "secret" with no password. Redisson passes it
 * through, the broker rejects the auth, and the failure reads like a wrong password rather than
 * a malformed URI.
 */
class RedisUriTest {

    @Test
    fun `a password lands in the password position`() {
        val uri = redisUriOf(RedisConfig(host = "redis-1", port = 6380, password = "s3cret"))

        assertEquals("redis://:s3cret@redis-1:6380", uri.toString())
    }

    @Test
    fun `no password means no credentials section`() {
        val uri = redisUriOf(RedisConfig(host = "redis-1", port = 6380, password = null))

        assertEquals("redis://redis-1:6380", uri.toString())
    }

    @Test
    fun `a password with reserved characters is encoded`() {
        val uri = redisUriOf(RedisConfig(host = "h", port = 1, password = "a@b/c"))

        assertEquals("redis://:a%40b%2Fc@h:1", uri.toString())
    }
}
```

`redisUriOf(config: RedisConfig): RedisURI` ist die neue, testbare Funktion in
`RedisCredentialsProviderImpl.kt`; die `@AutoService`-Klasse ruft sie mit `redisConfig` auf.

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-core:test --tests '*RedisUriTest*'`
Expected: FAIL, „Unresolved reference: redisUriOf".

- [ ] **Step 3: Credentials für beide Transporte bauen**

`…-api/…/credentials/CredentialsProvider.kt`:

```kotlin
package dev.slne.surf.eventbus.credentials

/**
 * The seam an operator hooks a secret store into.
 *
 * It existed for Redis and not for RabbitMQ, which meant one transport could read its password
 * from a vault and the other could only read it from a file. Neither the transports nor the
 * operators asked for that difference.
 */
interface CredentialsProvider
```

`…-api/…/credentials/RedisCredentialsProvider.kt` — der bestehende Typ, umgezogen und um
`: CredentialsProvider` ergänzt. `…-api/…/credentials/RabbitCredentialsProvider.kt`:

```kotlin
package dev.slne.surf.eventbus.credentials

import com.rabbitmq.client.ConnectionFactory
import dev.slne.surf.api.core.util.requiredService
import dev.slne.surf.eventbus.rabbitmq.config.CommonRabbitMQConfig

interface RabbitCredentialsProvider : CredentialsProvider {
    fun connectionFactory(config: CommonRabbitMQConfig): ConnectionFactory

    companion object : RabbitCredentialsProvider by requiredService<RabbitCredentialsProvider>()
}
```

`…-core/…/rabbitmq/credentials/RabbitCredentialsProviderImpl.kt` zieht den heute in
`RabbitConnectionFactoryImpl` inline gebauten `ConnectionFactory` hierher — Host, Port,
Benutzername, Passwort, Vhost, Timeout aus `config` — und `RabbitConnectionFactoryImpl` ruft
`RabbitCredentialsProvider.connectionFactory(config)`.

`redisUriOf` schreiben:

```kotlin
fun redisUriOf(config: RedisConfig): RedisURI = RedisURI(
    buildString {
        append(RedisURI.REDIS_PROTOCOL)
        val password = config.password
        if (!password.isNullOrEmpty()) {
            // The colon is not decoration: without it the value lands in the *user* slot.
            append(':')
            append(URLEncoder.encode(password, StandardCharsets.UTF_8))
            append('@')
        }
        append(config.host)
        append(':')
        append(config.port)
    }
)
```

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-core:test --tests '*RedisUriTest*'`
Expected: PASS, alle drei.

- [ ] **Step 5: Die zwei RabbitMQ-Konfigurationsklassen zu einer machen**

`GlobalRabbitMQConfig` und `PluginRabbitMQConfig` sind vierzehn Felder mit derselben KDoc, zweimal.
Danach eine `RabbitMQConfig` mit durchgehenden Sentinel-Typen (`StringOrDefault`, `IntOr.Default`,
`BooleanOr.Default`) — die Plugin-Fassung, weil eine Sentinel-Schicht die andere ausdrücken kann und
nicht umgekehrt — plus die Defaults an genau einer Stelle:

Die vierzehn Felder, ihre Sentinel-Typen und die eingebauten Standardwerte — die aus
`GlobalRabbitMQConfig` heute, jetzt an genau einer Stelle:

| Feld                              | Typ                 | Standard                |
|-----------------------------------|---------------------|-------------------------|
| `host`                            | `StringOrDefault`   | `"localhost"`           |
| `port`                            | `IntOr.Default`     | `5672`                  |
| `username`                        | `StringOrDefault`   | `"guest"`               |
| `password`                        | `StringOrDefault`   | `"guest"`               |
| `vhost`                           | `StringOrDefault`   | `"/"`                   |
| `timeout`                         | `IntOr.Default`     | `30`                    |
| `requestTimeoutSeconds`           | `IntOr.Default`     | `60`                    |
| `publisherPoolSize`               | `IntOr.Default`     | `2`                     |
| `serverPrefetchCount`             | `IntOr.Default`     | `128`                   |
| `persistRequests`                 | `BooleanOr.Default` | `true`                  |
| `persistResponses`                | `BooleanOr.Default` | `false`                 |
| `outgoingRequestChunkingEnabled`  | `BooleanOr.Default` | `true`                  |
| `outgoingResponseChunkingEnabled` | `BooleanOr.Default` | `true`                  |
| `auditServiceName`                | `StringOrDefault`   | `"surf-eventbus-audit"` |

```kotlin
@ConfigSerializable
@InternalEventBusApi
data class RabbitMQConfig(
    @field:Comment("RabbitMQ server hostname or IP address.")
    @Trimmed
    @JvmField
    val host: StringOrDefault = StringOrDefault.USE_DEFAULT,

    @field:Comment("RabbitMQ server port.")
    @MinNumber(1.0)
    @MaxNumber(65535.0)
    @JvmField
    val port: IntOr.Default = IntOr.Default.USE_DEFAULT,

    // ... die übrigen zwölf Felder, jedes mit dem @field:Comment aus GlobalRabbitMQConfig,
    //     jedes genau einmal statt zweimal ...
) : CommonRabbitMQConfig {

    // The built-in defaults, at one place. `or` is surf-api-core's sentinel resolution:
    // "the configured value, or this when the file said USE_DEFAULT".
    override val host: String get() = host or "localhost"
    override val port: Int get() = port or 5672
    override val username: String get() = username or "guest"
    override val password: String get() = password or "guest"
    override val vhost: String get() = vhost or "/"
    override val timeout: Int get() = timeout or 30
    override val requestTimeoutSeconds: Int get() = requestTimeoutSeconds or 60
    override val publisherPoolSize: Int get() = publisherPoolSize or 2
    override val serverPrefetchCount: Int get() = serverPrefetchCount or 128
    override val persistRequests: Boolean get() = persistRequests or true
    override val persistResponses: Boolean get() = persistResponses or false
    override val outgoingRequestChunkingEnabled: Boolean get() = outgoingRequestChunkingEnabled or true
    override val outgoingResponseChunkingEnabled: Boolean get() = outgoingResponseChunkingEnabled or true
    override val auditServiceName: String get() = auditServiceName or "surf-eventbus-audit"

    /** The broker-wide file every service on this host reads. */
    companion object Global : SpongeYmlConfigClass<RabbitMQConfig>(
        RabbitMQConfig::class.java, EventBusInstance.instance.dataPath, "rabbitmq.yml"
    )

    /** The per-plugin file that overrides it, field by field. */
    object Plugin : SpongeYmlConfigClass<RabbitMQConfig>(
        RabbitMQConfig::class.java,
        EventBusInstance.instance.dataPath,
        "rabbitmq-plugin.yml",
        migrations = ConfigMigrationBuilder().add(ClearCompleteConfigMigration()).build()
    )
}
```

Die Sichtbarkeitskollision zwischen dem Feld `host: StringOrDefault` und der Property
`host: String` löst Kotlin nicht von selbst — die Felder heißen im Konstruktor deshalb
`rawHost`, `rawPort` und so fort, mit `@ConfigSerializable`-Namen über
`@Setting("host")`. Das ist die einzige Stelle, an der die Zusammenlegung Kosten hat, und sie ist
billiger als vierzehn Felder in zwei Dateien.

`resolveRabbitMQConfig(global: RabbitMQConfig, plugin: RabbitMQConfig?, environment)` bleibt in der
Signatur gleich bis auf die Typen. `CommonRabbitMQConfig` bekommt Kotlin-Properties statt
`getHost()`/`isPersistRequests()`; `RabbitEnvironment` und `PluginWithGlobalFallback` ziehen mit.
`RabbitEnvironmentTest` prüft danach `resolved.host` statt `resolved.getHost()` — sonst unverändert,
denn die Schichtenlogik ändert sich nicht.

- [ ] **Step 6: Redis die vierte Schicht wirklich geben**

`redisConfig` liest heute `resolveRedisConfig(global = RedisConfig.getConfig(), plugin = null)` —
die Plugin-Schicht ist verdrahtet und nie gefüllt. Danach:

```kotlin
/** `env > plugin yaml > global yaml > default`, resolved once per process. */
val redisConfig by lazy {
    resolveRedisConfig(global = RedisConfig.Global.getConfig(), plugin = RedisConfig.Plugin.getConfigOrNull())
}
```

mit denselben zwei Companions wie bei RabbitMQ. `RedisConfigLayeringTest` bekommt einen Fall dazu,
der beweist, dass die Plugin-Schicht die globale schlägt und die Umgebung beide.

- [ ] **Step 7: Eine Plattform-SPI statt zwei**

`…-api/…/platform/EventBusInstance.kt`:

```kotlin
package dev.slne.surf.eventbus.platform

import dev.slne.surf.api.core.util.requiredService
import java.io.InputStream
import java.nio.file.Path

/**
 * The platform this process runs in: Paper, Velocity or a standalone JVM.
 *
 * There were two of these — `RabbitMQInstance`, an interface in the api module, and
 * `RedisInstance`, an abstract class in core that also built a Netty event loop. A platform had
 * to implement both, and Paper and Velocity implemented only one, so Redis was unreachable
 * there. One SPI, and the runtime objects move to where behaviour belongs.
 */
interface EventBusInstance {
    val dataPath: Path

    fun getResourceAsStream(name: String): InputStream? = javaClass.getResourceAsStream(name)

    /** The plugin name to attribute a caller to, for per-plugin configuration and logging. */
    fun tryExtractPluginName(clazz: Class<*>): String

    companion object {
        val instance: EventBusInstance by lazy { requiredService<EventBusInstance>() }
    }
}
```

`…-core/…/redis/RedisRuntime.kt` nimmt aus `RedisInstance` den `init`-Block, die zwei Scheduler,
`load()` und `disable()` — unverändert, nur nicht mehr auf der SPI. Es wird einmal pro Prozess von
`RedisComponentProviderImpl` gebaut.

```bash
git rm surf-eventbus-api/src/main/kotlin/dev/slne/surf/eventbus/rabbitmq/internal/RabbitMQInstance.kt
git rm surf-eventbus-core/src/main/kotlin/dev/slne/surf/eventbus/redis/RedisInstance.kt
```

Jede `RabbitMQInstance.instance.dataPath`- und `RedisInstance.instance.dataPath`-Stelle wird
`EventBusInstance.instance.dataPath`.

- [ ] **Step 8: Eine Ausnahmewurzel**

`…-api/…/exception/SurfEventBusException.kt`:

```kotlin
package dev.slne.surf.eventbus.exception

/**
 * The root of everything this project throws on purpose.
 *
 * A consumer that wants to catch "the bus failed" had to name two unrelated hierarchies and
 * knew of neither that it was the complete set.
 */
abstract class SurfEventBusException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
```

`SurfRabbitException` erbt davon. `RedisCodecException` zieht unter ein neues
`SurfRedisException : SurfEventBusException` in `…-api/…/redis/RedisExceptions.kt`.
`SurfEventBusNotFrozenException` bleibt bewusst `IllegalStateException` — es ist eine
Vorbedingungsverletzung, kein Transportfehler; der bestehende KDoc-Absatz sagt das bereits und
bleibt stehen.

- [ ] **Step 9: `RequiresDocker` einmal**

```bash
git rm surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/redis/testing/RequiresDocker.kt
git mv surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/rabbitmq/testing/RequiresDocker.kt \
       surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/testing/RequiresDocker.kt
```

Paket auf `dev.slne.surf.eventbus.testing`, KDoc-Verweis auf das gelöschte `surf-eventbus-test`
streichen, alle Importe in den Testquellen nachziehen.

- [ ] **Step 10: Alles laufen lassen**

Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS.

Run: `./gradlew updateLegacyAbi && ./gradlew checkLegacyAbi`

- [ ] **Step 11: Commit**

```bash
git add -A
git commit -m "refactor!: give both transports the same shape

One credentials seam instead of one for Redis and none for RabbitMQ, one
RabbitMQConfig instead of two files of identical fields, one EventBusInstance
instead of an interface here and an abstract class there, one exception root.

Fixes the Redis URI: redis://secret@host names a user called 'secret'; the
password belongs after a colon."
```

---

## Task 6: Hacks auflösen

**Files:**

- Modify: `…-core/src/test/…/core/testing/FakeStandaloneLifecycleHook.kt`
- Delete: `…-core/src/test/…/redis/testing/FakeRedisInstance.kt`,
  `…-core/src/test/resources/META-INF/services/…StandaloneLifecycleHook`,
  `…-api/src/test/…/AggregateCompletenessTest.kt`
- Create: `…-api/src/test/…/structure/InternalNotInAbiTest.kt`
- Modify: `…-api/…/SurfEventBusBuilder.kt`, `…-core/…/core/SurfEventBusBuilderImpl.kt`
- Modify: `…-api/src/test/…/structure/{PackageNamingTest,RelocationBaseTest}.kt`
- Create: `…-core/…/rabbitmq/audit/AuditReports.kt`
- Modify: `…-core/…/rabbitmq/consumer/RabbitListenerHandlerManager.kt:104`,
  `…-core/…/rabbitmq/connection/RabbitConnectionImpl.kt:73`
- Modify: `.github/workflows/publish.yml:13`

**Interfaces:**

- Consumes: `EventBusInstance`, `StandaloneLifecycleHook` aus Task 5.
- Produces:
    - `SurfEventBusBuilder.withStandaloneHook(hook: StandaloneLifecycleHook): SurfEventBusBuilder`
      — die Testnaht, die den `ServiceLoader` überflüssig macht
    -
    `object AuditReports { fun handlerFailed(...): AuditReport; fun unroutable(...): AuditReport; fun undeserializable(...): AuditReport; fun chunkSeriesExpired(...): AuditReport }`

- [ ] **Step 1: Die Testnaht schreiben, die den ServiceLoader ersetzt**

`SurfEventBusBuilder` bekommt neben `withRedis(event, query)` eine zweite Naht desselben Zuschnitts:

```kotlin
    /**
     * Test seam: uses [hook] instead of looking one up via `ServiceLoader`.
     *
     * Without it a unit test had to register a double in `META-INF/services` by hand, because
     * running `@AutoService`'s processor next to this project's own on one `kspTest` task hits a
     * KSP2 analysis-API lifetime bug. Injection removes the reason to run either.
     */
    fun withStandaloneHook(hook: StandaloneLifecycleHook): SurfEventBusBuilder
```

`SurfEventBusBuilderImpl` hält den Wert und benutzt `requiredService<StandaloneLifecycleHook>()`
nur noch, wenn keiner gesetzt wurde.

- [ ] **Step 2: Die handgeschriebene Registrierung entfernen**

`SurfEventBusLifecycleTest` und die übrigen Nutzer von `FakeStandaloneLifecycleHook` rufen
`.withStandaloneHook(FakeStandaloneLifecycleHook())`.

```bash
git rm surf-eventbus-core/src/test/resources/META-INF/services/dev.slne.surf.eventbus.rabbitmq.api.internal.StandaloneLifecycleHook
git rm surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/redis/testing/FakeRedisInstance.kt
```

`FakeStandaloneLifecycleHook` behält seinen ersten KDoc-Absatz (warum ein No-op-Double nötig ist)
und verliert den zweiten (die handgeschriebene Registrierung), weil es sie nicht mehr gibt.

`FakeRedisInstance` verschwindet: die Klasse wird nirgends referenziert, und ihre KDoc beschreibt
eine `META-INF/services`-Registrierung, die nie existiert hat.

- [ ] **Step 3: Tests laufen lassen**

Run: `./gradlew :surf-eventbus-core:test -PskipIntegration`
Expected: PASS. Kein Test hängt mehr an einer Datei unter `src/test/resources/META-INF`.

- [ ] **Step 4: Den vakuösen Test ersetzen**

```bash
git rm surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/AggregateCompletenessTest.kt
```

`surf-eventbus-api/src/test/kotlin/dev/slne/surf/eventbus/structure/InternalNotInAbiTest.kt`:

```kotlin
package dev.slne.surf.eventbus.structure

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Nothing internal reaches the published ABI.
 *
 * The test this replaces asserted that classes of this module are on this module's classpath,
 * which cannot fail. The question worth asking is the opposite one: does a declaration marked
 * `@InternalEventBusApi` leak into what consumers compile against?
 */
class InternalNotInAbiTest {

    private val repositoryRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    @Test
    fun `no internal package appears in the abi dump`() {
        val dump = File(repositoryRoot, "surf-eventbus-api/api/surf-eventbus-api.api")
        assertTrue(dump.isFile, "run ./gradlew updateLegacyAbi first")

        val internalRoots = listOf(
            "dev/slne/surf/eventbus/service/",
            "dev/slne/surf/eventbus/rabbitmq/config/",
        )

        val offenders = dump.readLines()
            .filter { line -> internalRoots.any { line.contains(it) } }

        assertTrue(offenders.isEmpty(), "internal declarations in the ABI:\n$offenders")
    }
}
```

- [ ] **Step 5: Die zwei CWD-abhängigen Tests robust machen**

`PackageNamingTest` und `RelocationBaseTest` benutzen `Path.of("..")`. Beide bekommen dieselbe
Wurzelsuche wie `PackageLayoutTest`:

```kotlin
    private val repositoryRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
```

- [ ] **Step 6: `AuditReports` nachtragen**

Plan 4 Task 1 Step 9 sah die Datei vor; die fünf Meldepfade wurden ohne sie verdrahtet, jeder mit
seinem eigenen `AuditReport(...)`-Aufruf. Sie bündelt die gemeinsamen Felder:

```kotlin
package dev.slne.surf.eventbus.rabbitmq.audit

import com.rabbitmq.client.AMQP
import dev.slne.surf.eventbus.audit.AuditKind
import dev.slne.surf.eventbus.audit.AuditReport

/**
 * The four Rabbit loss paths, each turned into an [AuditReport] at one place.
 *
 * Every call site filled in the same six identity fields — message id, origin service and
 * instance, reporter service and instance, timestamp — and read the same three off the same
 * `BasicProperties`. Each could get one of them subtly wrong on its own, and a wrong
 * `messageUuid` shows up as four unrelated incidents instead of one message that failed four
 * times.
 */
class AuditReports(
    private val serviceName: String,
    private val instanceId: String,
    private val maxPayloadBytes: Int,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun handlerFailed(
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        originQueue: String?,
        handler: String?,
        attempt: Int,
        terminal: Boolean,
        retryTier: String?,
        throwable: Throwable,
    ): AuditReport = base(AuditKind.HANDLER_FAILED, properties, body, exchange, routingKey, originQueue)
        .copy(handler = handler, attempt = attempt, terminal = terminal, retryTier = retryTier)
        .withCause(throwable)

    fun unroutable(
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        replyText: String?,
    ): AuditReport = base(AuditKind.UNROUTABLE, properties, body, exchange, routingKey, originQueue = null)
        .copy(exceptionMessage = replyText)

    fun undeserializable(
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        originQueue: String?,
        throwable: Throwable,
    ): AuditReport = base(AuditKind.UNDESERIALIZABLE, properties, body, null, null, originQueue)
        .withCause(throwable)

    fun chunkSeriesExpired(
        messageUuid: String,
        originQueue: String?,
        receivedChunks: Int,
        expectedChunks: Int,
    ): AuditReport = AuditReport(
        messageUuid = messageUuid,
        kind = AuditKind.CHUNK_SERIES_EXPIRED,
        originService = serviceName,
        originInstance = instanceId,
        reportedByService = serviceName,
        reportedByInstance = instanceId,
        failedAtEpochMs = clock(),
        originQueue = originQueue,
        exceptionMessage = "chunk series expired with $receivedChunks of $expectedChunks chunks",
    )

    private fun base(
        kind: AuditKind,
        properties: AMQP.BasicProperties,
        body: ByteArray?,
        exchange: String?,
        routingKey: String?,
        originQueue: String?,
    ): AuditReport {
        // Truncating here rather than at the writer means the limit also applies to a report
        // that a differently-configured process sends.
        val truncated = body != null && body.size > maxPayloadBytes

        return AuditReport(
            messageUuid = AuditMessageIdentity.of(properties),
            kind = kind,
            originService = properties.appId ?: serviceName,
            originInstance = properties.headers?.get("x-surf-instance")?.toString(),
            reportedByService = serviceName,
            reportedByInstance = instanceId,
            failedAtEpochMs = clock(),
            exchange = exchange,
            routingKey = routingKey,
            originQueue = originQueue,
            messageType = properties.type,
            correlationId = properties.correlationId,
            payloadEncoding = properties.contentEncoding,
            payloadSizeBytes = body?.size ?: 0,
            payloadTruncated = truncated,
            payload = if (truncated) body!!.copyOf(maxPayloadBytes) else body,
            headers = properties.headers.orEmpty()
                .mapValues { (_, value) -> value?.toString().orEmpty() },
        )
    }

    private fun AuditReport.withCause(throwable: Throwable) = copy(
        exceptionClass = throwable::class.qualifiedName,
        exceptionMessage = throwable.message,
        stacktrace = throwable.stackTraceToString(),
    )
}
```

Die vier Funktionen ersetzen die `AuditReport(...)`-Aufrufe, die `RetryPublisher`,
`ReturnListenerBridge`, `RabbitConsumer` (zweimal) und `RabbitPacketChunkAssembler` heute je einzeln
zusammensetzen. `RabbitAuditSinkTest` bleibt unverändert grün — die Senke ändert sich nicht, nur wer
ihre Argumente baut.

Ein Test hält die eine Eigenschaft fest, die über alle vier Pfade gelten muss —
`…-core/src/test/…/rabbitmq/audit/AuditReportsTest.kt`:

```kotlin
    @Test
    fun `a payload over the limit is truncated and keeps its real size`() {
        val reports = AuditReports("surf-factions", "lobby-3", maxPayloadBytes = 4) { 1L }
        val properties = AMQP.BasicProperties.Builder().build()

        val report = reports.undeserializable(properties, ByteArray(10), "q", RuntimeException("x"))

        assertEquals(4, report.payload?.size)
        assertEquals(10, report.payloadSizeBytes, "the real size is the interesting one")
        assertTrue(report.payloadTruncated)
    }

    @Test
    fun `every path reports under the same message identity`() {
        val reports = AuditReports("surf-factions", "lobby-3", maxPayloadBytes = 1024) { 1L }
        val properties = AuditMessageIdentity.stamp(AMQP.BasicProperties.Builder().build(), "id-1")

        assertEquals("id-1", reports.unroutable(properties, null, "x", "y", null).messageUuid)
        assertEquals(
            "id-1",
            reports.undeserializable(properties, null, "q", RuntimeException("x")).messageUuid
        )
    }
```

- [ ] **Step 7: Die zwei TODOs auflösen**

`RabbitListenerHandlerManager` fängt `SurfRabbitProtocolVersionMismatchException` und tut nichts
Bestimmtes damit. Ein Versions-Mismatch ist kein wiederholbarer Fehler: die Gegenstelle spricht ein
anderes Protokoll und wird es beim nächsten Versuch auch. Er wird geloggt und als
`AuditKind.UNDESERIALIZABLE` gemeldet, die Nachricht wird geackt und verworfen — dieselbe Behandlung
wie ein Deserialisierungsfehler, weil es einer ist.

`RabbitConnectionImpl:73` trägt den Hinweis, dass Meldungen dauerhaft in der Queue liegen oder
verschwinden, je nachdem ob der Microservice sie je deklariert hat. Der `TODO(...)`-Marker wird zu
einer Aussage: der Zustand ist gewollt und in `docs/rollout-2.0.md` Schritt 3 beschrieben.

- [ ] **Step 8: Den toten Publish-Workflow richtigstellen**

`.github/workflows/publish.yml:13` nennt `surf-rabbitmq-velocity-*-all.jar` und
`surf-rabbitmq-paper-*-all.jar`. Die Artefakte heißen seit dem Umbau
`surf-eventbus-platform-velocity-*-all.jar` und `surf-eventbus-platform-paper-*-all.jar`; der
Workflow lädt heute nichts hoch, ohne dass es auffällt.

- [ ] **Step 9: Alles laufen lassen und committen**

Run: `./gradlew updateLegacyAbi`
Run: `./gradlew build -PskipIntegration`
Expected: SUCCESS.

```bash
git add -A
git commit -m "fix: remove the workarounds instead of documenting them

The hand-written META-INF/services entry existed because @AutoService and
this project's processor cannot share a kspTest task. Injecting the double
removes the reason to run either. FakeRedisInstance was dead and described a
registration that never existed; AggregateCompletenessTest could not fail.

The publish workflow named jars that stopped existing at the rename."
```

---

## Task 7: Plattform-Module vereinen (Plan 4 Task 4)

**Files:**

- Create: `…-platform-paper/…/paper/PaperEventBusInstance.kt`
- Create: `…-platform-velocity/…/velocity/VelocityEventBusInstance.kt`
- Create: `…-platform-standalone/…/standalone/StandaloneEventBusInstance.kt`
- Delete: `…-platform-paper/…/PaperRabbitMqInstance.kt`,
  `…-platform-velocity/…/VelocityRabbitMqInstance.kt`,
  `…-platform-standalone/…/StandaloneRedisInstance.kt`,
  `…-core/…/rabbitmq/StandaloneRabbitMqInstance.kt`
- Modify: `…-platform-paper/…/{PaperBootstrap,PaperMain}.kt`,
  `…-platform-velocity/…/VelocityMain.kt`
- Modify: alle drei `build.gradle.kts` (Paketpfade in `mainClass`/`bootstrapper`)
- Create: `…-platform-standalone/src/test/…/StandaloneEventBusInstanceTest.kt`

**Interfaces:**

- Consumes: `EventBusInstance` aus Task 5.
- Produces: `class StandaloneEventBusInstance(override val dataPath: Path) : EventBusInstance`,
  registriert mit `@AutoService(EventBusInstance::class)`; dazu die beiden Plugin-Fassungen.

- [ ] **Step 1: Test schreiben**

`surf-eventbus-platform/surf-eventbus-platform-standalone/src/test/kotlin/dev/slne/surf/eventbus/platform/standalone/StandaloneEventBusInstanceTest.kt`:

```kotlin
package dev.slne.surf.eventbus.platform.standalone

import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * One instance serves both transports.
 *
 * There were two per-transport instances and no platform implemented both: Paper and Velocity
 * shipped a RabbitMQ instance and no Redis one, so `publish` and `query` — the two verbs that
 * ride Redis — could not work there at all.
 */
class StandaloneEventBusInstanceTest {

    @Test
    fun `the data path reaches the instance`() {
        val dataPath = Files.createTempDirectory("standalone")
        val instance = StandaloneEventBusInstance(dataPath)

        assertEquals(dataPath, instance.dataPath)
    }

    @Test
    fun `the plugin name of an unknown caller falls back to the service name`() {
        val instance = StandaloneEventBusInstance(Files.createTempDirectory("standalone"))

        assertEquals("surf-eventbus-standalone", instance.tryExtractPluginName(String::class.java))
    }
}
```

- [ ] **Step 2: Test laufen lassen**

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-standalone:test`
Expected: FAIL, „Unresolved reference: StandaloneEventBusInstance".

Das Modul hat heute keinen Testblock; er kommt in `…-platform-standalone/build.gradle.kts` dazu:

```kotlin
dependencies {
    api(projects.surfEventbusCore)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}
```

- [ ] **Step 3: Die drei Instanzen schreiben**

`StandaloneEventBusInstance` entsteht aus `StandaloneRedisInstance` und
`StandaloneRabbitMqInstance` — beide setzten einen Instanznamen und einen Konfigurationspfad und
luden die Instanz; die neue Klasse tut es einmal und behält das `@AutoService`-Muster.
`StandaloneRabbitMqInstance` verlässt dabei `surf-eventbus-core`, wo eine Standalone-Klasse nie
hingehörte.

`PaperEventBusInstance` und `VelocityEventBusInstance` entstehen aus `PaperRabbitMqInstance` und
`VelocityRabbitMqInstance`, ergänzt um das, was `RedisInstance` von einer Plattform brauchte:
`tryExtractPluginName` über die Reflection-Proxies aus `dev.slne.surf.eventbus.platform`
(`JavaPluginLoaderProxy`, `SerializedPluginDescriptionProxy`).

Die Pakete heißen danach `dev.slne.surf.eventbus.platform.{paper,velocity,standalone}` statt
`dev.slne.surf.eventbus.rabbitmq.{paper,velocity}` und `dev.slne.surf.eventbus.redis`.
`PaperBootstrap`, `PaperMain` und `VelocityMain` ziehen mit; die drei `build.gradle.kts`
nennen die neuen `mainClass`- und `bootstrapper`-Werte.

- [ ] **Step 4: Test laufen lassen**

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-standalone:test`
Expected: PASS, beide.

- [ ] **Step 5: Die Jars prüfen**

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-paper:shadowJar`
Expected: SUCCESS.

```bash
unzip -l surf-eventbus-platform/surf-eventbus-platform-paper/build/libs/*-all.jar \
  | grep -c "dev/slne/surf/eventbus/shaded/io/netty/buffer/ByteBuf.class"
```

Expected: `1`

```bash
unzip -l surf-eventbus-platform/surf-eventbus-platform-paper/build/libs/*-all.jar \
  | grep -c "io/netty/buffer/ByteBuf.class"
```

Expected: `1` — dieselbe Datei, kein unrelocierter Zweitpfad.

Run: `./gradlew :surf-eventbus-platform:surf-eventbus-platform-velocity:shadowJar`
Expected: SUCCESS, dieselben zwei Zählungen.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat!: one instance per platform, serving both transports

Paper and Velocity shipped a RabbitMQ instance and no Redis one, so publish
and query — the two verbs that ride Redis — did not work there at all.
StandaloneRabbitMqInstance also leaves surf-eventbus-core, where a standalone
class never belonged."
```

---

## Task 8: Container-Suiten (Plan 4 Task 5)

Plan 4 wollte sie in `surf-eventbus-test`. Das Modul wurde am 2026-08-01 gelöscht; die Suiten
entstehen in `surf-eventbus-core/src/test`, neben `RabbitBrokerExtension`, das dort schon liegt und
auf CI gebaut wird.

**Files:**

- Create: `…-core/src/test/…/testing/{RedisContainerExtension,DatabaseContainerExtension}.kt`
- Create:
  `…-core/src/test/…/suite/{EventSuiteTest,QuerySuiteTest,RpcSuiteTest,TransportEnablementTest}.kt`
- Modify: `…-core/build.gradle.kts` (Testcontainers-Redis-Abhängigkeit)
- Modify: `docs/superpowers/notes/2026-07-31-fundament-verification.md`

**Interfaces:**

- Consumes: alles aus Tasks 1–7.
- Produces:
  `class RedisContainerExtension : BeforeAllCallback, AfterAllCallback { val uri: String }`,
  `class DatabaseContainerExtension : …`, und die vier Suiten mit der Spec-Nummer im Methodennamen.

- [ ] **Step 1: Die Redis-Extension schreiben**

Nach dem Muster von `RabbitBrokerExtension`: ein Container pro Suite-Lauf, ein eindeutiger
Schlüsselraum je Test für Kollisionsfreiheit, Abbau am Ende. Testcontainers hat kein Redis-Modul;
ein `GenericContainer` genügt.

`surf-eventbus-core/src/test/kotlin/dev/slne/surf/eventbus/testing/RedisContainerExtension.kt`:

```kotlin
package dev.slne.surf.eventbus.testing

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger

/**
 * One Redis container per suite run.
 *
 * Per-test containers cost more than they buy: Redis has no cross-test state a suite cares
 * about beyond keys, and [uniqueKeyspace] keeps those apart for a fraction of the startup cost.
 */
class RedisContainerExtension : BeforeAllCallback, AfterAllCallback {

    private val container = GenericContainer(DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(REDIS_PORT)

    private val counter = AtomicInteger()

    val uri: String
        get() = "redis://${container.host}:${container.getMappedPort(REDIS_PORT)}"

    /** A key prefix no other test in this run uses. */
    fun uniqueKeyspace(name: String): String = "$name-${counter.incrementAndGet()}"

    override fun beforeAll(context: ExtensionContext) = container.start()

    override fun afterAll(context: ExtensionContext) = container.stop()

    private companion object {
        const val REDIS_PORT = 6379
    }
}
```

Die Abhängigkeit steht bereits in `surf-eventbus-core/build.gradle.kts`
(`testImplementation(libs.testcontainers.core)`); es kommt nichts dazu.

- [ ] **Step 2: Die Datenbank-Extension schreiben**

Dasselbe Muster mit `PostgreSQLContainer`. Sie wird von der Audit-Suite gebraucht, die erst laufen
kann, wenn Task 2 aus Plan 4 entblockt ist — deshalb entsteht die Extension jetzt und
`AuditSuiteTest` **nicht**.

- [ ] **Step 3: Die vier Suiten schreiben**

| Suite                     | Testnummern aus dem Spec |
|---------------------------|--------------------------|
| `EventSuiteTest`          | 1–12, 12a–12c            |
| `QuerySuiteTest`          | 13–23                    |
| `RpcSuiteTest`            | 24–32                    |
| `TransportEnablementTest` | 33–37 (ohne Container)   |

Jede Methode heißt nach ihrer Nummer und Aussage, etwa
``fun `11 - an event with every subscriber offline expires`()``, damit Spec und Suite aufeinander
zeigen. Tests, die schon in Plan 2 oder 3 entstanden sind, werden **nicht** kopiert; die Suite
verweist im KDoc auf ihren Ort. Neu entstehen hier nur die Fälle, die mehr als ein Subsystem
gleichzeitig hochfahren.

`AuditSuiteTest` (38–49) entsteht nicht: Fälle 38, 47 und 48 brauchen den Microservice aus Plan 4
Task 2, der blockiert ist. Der Grund gehört als KDoc an `DatabaseContainerExtension`.

- [ ] **Step 4: Ausführbarkeit prüfen, nicht Ausführung behaupten**

Run: `./gradlew :surf-eventbus-core:compileTestKotlin`
Expected: SUCCESS — das ist hier das erreichbare Kriterium.

Run: `./gradlew :surf-eventbus-core:test`
Expected: alle Container-Tests SKIPPED, weil kein Docker-Daemon erreichbar ist. Die Meldung lautet
„Docker is not available - integration test skipped, NOT verified".

- [ ] **Step 5: Verifikationsnotiz fortschreiben**

In `docs/superpowers/notes/2026-07-31-fundament-verification.md` einen Abschnitt „Stand nach der
Konsolidierung" ergänzen: welche Suiten existieren, wie viele Tests sie enthalten, und dass
**keiner** davon ausgeführt wurde. Dazu die Liste aus:

```bash
grep -rl "@RequiresDocker" --include=*.kt . | grep -v /build/
```

Der Abschnitt hält außerdem fest, dass `surf-eventbus-test` gelöscht wurde und die Notiz von Plan 4,
die es noch nennt, damit überholt ist.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add the event, query, rpc and transport-enablement suites

Every test is named after its number in the spec so the two stay pointed at
each other. None of them ran: no Docker daemon on this machine. The audit
suite is missing on purpose — its microservice is still blocked."
```

---

## Task 9: Dokumentation und Abschluss (Plan 4 Task 6)

**Files:**

- Modify: `README.md` (vollständig ersetzt)
- Create: `docs/rollout-2.0.md`
- Modify: `docs/superpowers/plans/2026-07-31-eventbus-4-audit-plattform-doku.md` (Task 2 Stand)

**Interfaces:**

- Consumes: alles.
- Produces: eine README, die die drei Verben, die Aufgabenteilung und die Zusagen beschreibt, und
  eine Rollout-Notiz mit den Schritten, die ein Betreiber tun muss.

- [ ] **Step 1: Prüfen, ob Task 2 aus Plan 4 noch blockiert ist**

Bevor die Dokumentation den Zustand festschreibt, wird die Blockade einmal nachgeprüft:

```bash
./gradlew dependencies --configuration compileClasspath 2>/dev/null | grep -i "surf-database"
```

Und die Frage, die die Notiz stellt: löst ein neueres `surf-database-r2dbc` als 2.3.1 die
`@Metadata`-Frage? Wenn ja, bleibt Task 2 aus Plan 4 stehen und wird dort abgearbeitet — nicht hier.
Wenn nein, bekommt
`docs/superpowers/notes/2026-08-01-audit-microservice-blocked.md` eine Zeile mit dem heutigen Datum
und der geprüften Version.

- [ ] **Step 2: README schreiben**

Die heutige README heißt noch `surf-rabbitmq` und dokumentiert `SURF_RABBITMQ_*` — Variablen, die
seit Plan 1 `SURF_EVENTBUS_RABBITMQ_*` heißen und deren alte Namen ein Startfehler sind. Reihenfolge
nach Nutzen, nicht nach Modulstruktur:

1. Die drei Verben mit je einem Beispiel und der Zusage darunter.
2. Die Aufgabenteilung als Tabelle — welche Zusage welchen Transport hat und was im Fehlerfall
   passiert.
3. **An erster Stelle unter den Zusagen:** Events haben keine Durability. Wer offline ist, verpasst
   sie, es gibt keine Wiederzustellung, und ein gescheiterter Handler hinterlässt eine Audit-Zeile
   statt einer Wiederholung. Alles, was nicht verloren gehen darf, ist ein
   `@FireAndForget`-Aufruf.
4. `@QueryService`-Anleitung mit der Falle: `null` heißt Abstinenz. Ein Vertrag muss „nichts
   gefunden" von „nicht zuständig" unterscheidbar machen — bei `Boolean?` über `false`, sonst über
   einen Ergebnistyp.
5. Konfiguration: die vier Schichten und die vollständige Tabelle der `SURF_EVENTBUS_*`-Variablen,
   für **beide** Transporte, weil Redis seit Task 5 dieselben vier Schichten hat.
6. Redis-Sync-Strukturen: vor `freeze()` erstellen. Diese Falle bleibt und bekommt einen eigenen
   Abschnitt, einmal statt zweimal.
7. Das Audit: welche Meldepfade es gibt, dass der schreibende Dienst noch nicht existiert, und was
   das für einen Betreiber heute bedeutet.

- [ ] **Step 3: Rollout-Notiz schreiben**

`docs/rollout-2.0.md`, in der Reihenfolge, in der ein Betreiber sie braucht:

1. **Env-Variablen umbenennen.** Vollständige Alt-nach-Neu-Tabelle für beide Transporte. Eine
   gesetzte alte Variable ist ein Startfehler — das ist Absicht, nicht ein Bug.
2. **Alte Queues löschen.** `x-dead-letter-exchange` ist aus den Argumenten gefallen, und Argumente
   sind Teil der Queue-Identität: die Neudeklaration einer bestehenden
   `surf.service.*` scheitert mit `PRECONDITION_FAILED (406)`. Entweder die betroffenen Queues
   löschen oder den Vhost neu aufsetzen:
   ```bash
   rabbitmqctl list_queues name | grep '^surf\.'
   rabbitmqadmin delete queue name=surf.service.<dienst>
   ```
3. **Das Audit hat noch kein Ziel.** Der Meldeweg steht, der schreibende Microservice ist blockiert.
   Meldungen gehen mit `mandatory = false` raus und werden verworfen, solange seine Queue nicht
   existiert — das kostet nichts und blockiert nichts, aber es heißt auch, dass eine fehlende
   Audit-Zeile heute keine Aussage ist.
4. **Ein Plugin statt zwei.** Ein Server lädt `surf-eventbus-platform-paper` beziehungsweise
   `-velocity` und damit eine Netty-Kopie statt zwei. Die alten Plugins `surf-rabbitmq-paper`
   und die surf-redis-Plugins müssen vorher weg.
5. **Gemeinsam deployen.** Wire-Format und API sind inkompatibel zu 1.6.x/1.5.x; gemischter Betrieb
   funktioniert nicht.

- [ ] **Step 4: Prüfen, dass die README keine falschen Versprechen macht**

Gegen `docs/superpowers/specs/2026-07-29-surf-eventbus-design.md` lesen und vier Dinge bestätigen:
dass nirgends „durable" über Events steht, dass `InstanceTarget` als „für namentlich bekannte
Server" beschrieben ist und nicht als Server-zu-Server-Kanal, dass das Audit als best effort
beschrieben ist — keine Zeile ist keine Garantie, dass nichts passiert ist —, und dass keine
Umgebungsvariable ohne `SURF_EVENTBUS_`-Präfix mehr vorkommt:

```bash
grep -n "SURF_RABBITMQ_\|SURF_REDIS_" README.md docs/rollout-2.0.md
```

Expected: keine Ausgabe.

- [ ] **Step 5: Der letzte vollständige Lauf**

Run: `./gradlew clean build -PskipIntegration`
Expected: SUCCESS.

Run: `./gradlew checkLegacyAbi`
Expected: SUCCESS.

Run: `./gradlew :surf-eventbus-core:test`
Expected: Container-Tests SKIPPED — und genau so wird es berichtet.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "docs: rewrite the README and add the 2.0 rollout note

The README still called this project surf-rabbitmq and documented
SURF_RABBITMQ_* variables whose old names are a startup error since plan 1.

The rollout note leads with the three things that break silently otherwise:
renamed environment variables, queue arguments that cannot be redeclared, and
an audit path whose writer does not exist yet."
```

---

## Self-Review

**Spec-Abdeckung (Spec-Abschnitt → Task):**

| Spec                                                                                | Task        |
|-------------------------------------------------------------------------------------|-------------|
| 1 Ein Vertragsmodell (`service/`, `ParametersSerializer`, `ServiceSerializerCache`) | 2           |
| 2 Ein KSP-Prozessor                                                                 | 3           |
| 3 Pakete und Dateinamen                                                             | 4           |
| 4 Ein Marker statt drei                                                             | 1           |
| 5 Konfiguration: eine `RabbitMQConfig`, Kotlin-Properties, Redis-Plugin-Schicht     | 5 Steps 5–6 |
| 5 Credentials für beide, URI-Fehler                                                 | 5 Steps 1–4 |
| 5 `EventBusInstance`, `RedisRuntime`, `StandaloneLifecycleHook` bus-weit            | 5 Step 7    |
| 5 Ausnahmewurzel                                                                    | 5 Step 8    |
| 5 `RequiresDocker` einmal                                                           | 5 Step 9    |
| 6 `META-INF/services`-Hack, `FakeRedisInstance`, `AggregateCompletenessTest`        | 6 Steps 1–4 |
| 6 CWD-abhängige Tests, `AuditReports`, zwei TODOs, `publish.yml`                    | 6 Steps 5–8 |
| 7 Plan-4 Task 4 Plattform-Module                                                    | 7           |
| 7 Plan-4 Task 5 Container-Suiten                                                    | 8           |
| 7 Plan-4 Task 6 Dokumentation                                                       | 9           |
| 7 Plan-4 Task 2 als blockiert weitertragen                                          | 9 Step 1    |
| „Wie geprüft wird": Test gegen `common`, `rabbitmq.api`, kebab-case                 | 4 Step 1    |

**Typkonsistenz:** `ServiceCallable`, `ServiceParameter`, `ServiceType`, `ServiceInvoker` und die
vier `*Default`-Klassen werden in Task 2 Step 3 definiert und in Task 3 Step 5 vom Codegen
emittiert — unter denselben Namen. `RpcServiceDescriptor` (Task 2 Step 5) ist der Name, den Task 3
Step 5 in `ClassNames.rpcServiceDescriptor` einträgt; `RabbitRpcServiceDescriptor`
kommt nach Task 2 nirgends mehr vor. `EventBusInstance` (Task 5 Step 7) ist der Typ, den Task 7 Step
3 dreimal implementiert und den `RabbitMQConfig` (Task 5 Step 5) für seinen
`dataPath` benutzt. `StandaloneLifecycleHook` zieht in Task 4 Step 3 nach
`dev.slne.surf.eventbus.platform` und ist dort der Typ, den `withStandaloneHook` in Task 6 Step 1
nimmt. `ParametersSerializer` und `ServiceSerializerCache` (Task 2 Step 8) liegen in
`dev.slne.surf.eventbus.service.serialization` und werden von Task 3 Step 5 unter genau diesem Paket
referenziert.

**Reihenfolgeabhängigkeiten:** Task 1 vor 4, weil sonst jede Datei zweimal angefasst wird. Task 2
vor 3, weil der Prozessor auf die Typen zeigt. Task 4 nach 1–3, damit inhaltlich geänderte Dateien
nicht zusätzlich umziehen. Task 5 vor 7, weil die Plattform-Module
`EventBusInstance` brauchen. Task 8 nach 7, weil die Suiten beide Transporte auf einer Plattform
hochfahren. Task 9 zuletzt, weil sie den Endzustand beschreibt.

**Bewusst nicht hier:** die Wiederaufspaltung in Untermodule, eine gemeinsame Annotation für
`@QueryService` und `@RpcService`, der Audit-Microservice, die Korrektur der
`RabbitModule`-Enumeration in surf-microservice, die Umbenennung des GitHub-Repositories, der Umbau
der Consumer.
