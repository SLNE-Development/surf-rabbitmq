# surf-eventbus stage 1 ("fundament"): verification

Recorded after Tasks 1-7 of `docs/superpowers/plans/2026-07-31-eventbus-1-fundament.md` were
implemented on `feat/surf-event-bus`.

## What is green

```
./gradlew clean build
BUILD SUCCESSFUL in 2m 7s
159 actionable tasks: 151 executed, 8 up-to-date
```

Modules: `surf-eventbus-common`, `surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-api`,
`surf-eventbus-rabbitmq:surf-eventbus-rabbitmq-core`, `surf-eventbus-ksp`,
`surf-eventbus-redis:surf-eventbus-redis-api`, `surf-eventbus-redis:surf-eventbus-redis-core`,
`surf-eventbus-platform:surf-eventbus-platform-paper`,
`surf-eventbus-platform:surf-eventbus-platform-velocity`,
`surf-eventbus-platform:surf-eventbus-platform-standalone`, `surf-eventbus-test` and its three
submodules.

```
./gradlew test
```

233 tests across 48 test classes, 0 failures, 0 errors, 0 skipped (JUnit XML totals from every
`build/test-results/test/TEST-*.xml`). Includes:

- `PackageNamingTest`, `CommonPurityTest`, `RelocationBaseTest` (the three permanent guards from
  Tasks 1, 4, 7).
- `LegacyEnvironmentGuardTest`, `RabbitEnvironmentTest` (Task 5).
- All seven test classes carried over from surf-redis 1.10.1, including
  `EventCodecRegistryLincheckTest` via the separate `lincheckTest` task.

```
./gradlew checkLegacyAbi
BUILD SUCCESSFUL
```

Covers `surf-eventbus-rabbitmq-api` and `surf-eventbus-redis-api`, the only two modules with
`abiValidation` configured.

## What was verified that the plan expected to be unverified

The plan's Global Constraints assumed Docker is unreachable on this machine and that any test
tagged `@RequiresDocker` would come back "not verified." That assumption did not hold this run:
Docker was reachable, and every one of the 15 files carrying `@RequiresDocker`
(`RabbitBrokerExtensionTest`, `RabbitTopologyDeclarerTest`, `CompetingConsumersTest`,
`BrokerRestartTest`, `QueueOverflowTest`, `UnroutableTest`, `EventDeliveryTest`,
`BrokerLossDuringSendTest`, `ConsumerDeathTest`, `ChunkingTest`, `RetryIntegrationTest`,
`RetryQueueTest`, `RpcProxyRoundTripTest`, `RpcRoundTripTest`, `FireAndForgetTest`) ran and passed
against a real broker via Testcontainers rather than being skipped. `RequiresDocker` checks
`DockerClientFactory.instance().isDockerAvailable` at test time, so this is a property of the
machine the plan ran on, not of the code.

Nothing in this stage was left "not executed" as a result — every test in the repository ran.

## What changed in behaviour

Exactly what the plan scoped in:

- RabbitMQ environment variables moved from `SURF_RABBITMQ_*` to `SURF_EVENTBUS_RABBITMQ_*`,
  resolved through `surf-api-core`'s `EnvironmentVariables` instead of hand-rolled parsing.
- Redis environment variables moved from `SURF_REDIS_*` to `SURF_EVENTBUS_REDIS_*`.
- A new startup check, `LegacyEnvironmentGuard.check()`, fails fast with `IllegalStateException`
  when any old-named variable is still set, naming the variable and its replacement without
  leaking sensitive values.
- Netty converges on `4.2.16.Final` for both transports (previously `4.2.16.Final` for RabbitMQ,
  `4.2.15.Final` for Redis's own pin).

No other behavioural change was made. Everything else in this stage was package moves, module
nesting, and file relocation with content otherwise unchanged, guarded by `PackageNamingTest`,
`CommonPurityTest`, and `RelocationBaseTest`.

## Deviations from the plan worth noting

- Two `.java` files existed under `surf-rabbitmq-core` (`HandlerMethodHandleProvider`,
  `HandlerTemplate`) and six under surf-redis (`RedisVarInt`, `RedisVarLong`, `UUIDCodec`,
  `RedisEventInvokerTemplate`, `RedisInvokerLookupProvider`,
  `RedisRequestHandlerInvokerTemplate`) that the plan's `sed` commands (scoped to `*.kt`/`*.kts`)
  did not touch. Moved and renamed by hand in the same tasks.
- A `META-INF/services/com.google.devtools.ksp.processing.SymbolProcessorProvider` resource file
  under `surf-eventbus-ksp` referenced the old FQCN and needed a manual rename in Task 2.
- `RelocationBaseTest`'s second assertion assumed the checkout directory is literally named
  `surf-eventbus`; this checkout's directory is still `surf-rabbitmq` (the plan does not rename
  the working directory). Rewrote the exclusion to compare the resolved path against the root
  `build.gradle.kts` instead of comparing directory names, preserving the guard's intent.
- `surf-eventbus-common` needed a `compileOnly("com.velocitypowered:velocity-api:3.5.0-SNAPSHOT")`
  dependency (the coordinate the `dev.slne.surf.api.gradle.velocity` convention plugin injects)
  because the Velocity reflection proxies moved there but the module itself doesn't apply that
  plugin. Same module needed `testImplementation(libs.coroutines.test)` for the circuit breaker's
  suspend-function tests.

---

## Stand nach der Konsolidierung (2026-08-01)

**Docker ist auf dieser Maschine erreichbar** (Server 24.0.7). Der Konsolidierungsplan ging vom
Gegenteil aus und schrieb vor, `@RequiresDocker`-Tests zu schreiben und nicht auszuführen. Diese
Annahme war falsch: alle Integrationstests wurden ausgeführt.

`./gradlew build` (ohne `-PskipIntegration`), vollständiger Lauf:

| Modul | Tests | Fehler | Übersprungen |
|---|---|---|---|
| `surf-eventbus-api` | 78 | 0 | 0 |
| `surf-eventbus-core` | 185 | 0 | 0 |
| `surf-eventbus-ksp` | 9 | 0 | 0 |
| `surf-eventbus-platform-standalone` | 3 | 0 | 0 |
| **Summe** | **275** | **0** | **0** |

Null übersprungene Tests: die Container-Suiten liefen gegen echte RabbitMQ- und Redis-Container.

### Was das Ausführen gefunden hat

Zwei Defekte, die nur ein laufender Broker zeigt. Beide existierten schon vor dieser Arbeit —
der erste ist bei Commit `256e950` reproduziert worden, mit derselben Fehlermeldung.

1. **`RedisComponentProviderImpl.tryExtractPluginNameFromClass`** warf
   `ServiceConfigurationError`, wenn keine Plattform registriert ist. Damit scheiterte
   `RedisApi.create(uri)` in jedem Prozess ohne Plattform — auch in den sieben vorhandenen
   `RedisEventTransportTest`- und fünf `RedisQueryTransportTest`-Fällen, die deshalb **nie grün
   gelaufen sind**. Der Name ist eine Beschriftung für `CLIENT LIST`; er fällt jetzt auf
   `"surf-eventbus"` zurück.

2. **`EventSubscriptionRegistry.register`** rief `trySetAccessible()` nicht auf. Eine
   `public`-Methode auf einer nicht-öffentlichen Klasse steht in `Class.methods`, ist aus einem
   anderen Paket aber nicht aufrufbar: die Registrierung galt als erfolgreich, der Dispatcher
   warf bei **jeder** Zustellung `IllegalAccessException`. Weil ein werfender Handler
   absichtlich eingefangen wird, war die einzige Spur eine `EVENT_HANDLER_FAILED`-Zeile pro
   Ereignis — der Handler sah registriert aus und lief nie. Festgehalten in
   `NonPublicListenerTest`, das keinen Container braucht.

### Suiten

| Suite | Spec-Nummern | Fälle | Anmerkung |
|---|---|---|---|
| `EventSuiteTest` | 1–5, 9–11, 12a | 9 | 6–8, 12b, 12c fehlen noch |
| `QuerySuiteTest` | 13–19, 23 | 8 | 20 fehlt; 21/22 sind Compile-Fehler in `surf-eventbus-ksp` |
| `RpcSuiteTest` | 24, 27, 31 | 3 | 25/26/28/29/32 liegen in den Plan-3-Tests, 30 im KSP-Modul |
| `TransportEnablementTest` | 37 | 2 | 33–36 liegen in `SurfEventBusLifecycleTest` |

`AuditSuiteTest` (38–49) entsteht **nicht**: Fälle 38, 47 und 48 brauchen den schreibenden
Microservice aus Plan 4 Task 2, der weiter blockiert ist. `DatabaseContainerExtension` existiert
bereits, damit das Entblocken der einzige verbleibende Schritt ist.

### Überholt

`surf-eventbus-test` wurde am 2026-08-01 gelöscht. Die Plan-4-Notiz, die das Modul noch als Ort
der Container-Suiten nennt, ist damit überholt: alles Testbare liegt in
`surf-eventbus-api/src/test`, `surf-eventbus-core/src/test`, `surf-eventbus-ksp/src/test` und
`surf-eventbus-platform-standalone/src/test`.
