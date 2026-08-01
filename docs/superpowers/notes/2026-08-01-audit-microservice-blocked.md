# surf-eventbus-audit-microservice: blocked on surf-database-r2dbc's shading

Recorded while implementing Task 2 of
`docs/superpowers/plans/2026-07-31-eventbus-4-audit-plattform-doku.md` on `feat/surf-event-bus`.
Task 1 (the audit contract and the five reporting paths) is done and committed; Task 2 (the
microservice that writes the reports to a database) is reverted and not started.

## The blocker

`dev.slne.surf:surf-database-r2dbc` is published as a single self-shaded `-all.jar` with Exposed
and R2DBC relocated under `dev.slne.surf.database.libs.*`. Checked versions 2.3.0 and 2.3.1 (the
only ones cached locally) by decompiling the jar directly:

- The bytecode itself is relocated correctly — classes and methods really do live under
  `dev.slne.surf.database.libs.org.jetbrains.exposed.v1.*`.
- The embedded Kotlin `@Metadata` annotations were **not** rewritten to match. They still encode
  the pre-relocation names, e.g. `TransactionsKt`'s metadata says its owner is
  `org/jetbrains/exposed/v1/r2dbc/transactions/TransactionsKt`, not
  `dev/slne/surf/database/libs/org/jetbrains/exposed/v1/r2dbc/transactions/TransactionsKt`.

Kotlin resolves extension functions and other top-level declarations through that metadata, not
raw bytecode. The mismatch means `suspendTransaction`, `insert`, `select`, `deleteWhere` and
similar top-level Exposed DSL functions are **unresolvable from Kotlin source** in a consuming
module, even though they exist and work fine from Java or via reflection. Members inherited
through a class hierarchy (`varchar()`, `integer()`, `bool()` on `AuditableLongIdTable`, for
example) resolve fine, because that path doesn't depend on the metadata being correct — only
top-level/extension-function resolution is broken.

Separately: the plan's Task 2 build file assumes a `withSurfDatabaseR2dbc(...)` Gradle DSL helper.
It doesn't exist in the `surf-api-gradle-plugin` version actually resolvable here (`2.0.8` is the
only one cached, and its `StandaloneSurfExtension` has no such method) — a plain
`implementation("dev.slne.surf:surf-database-r2dbc:2.3.0")` works as a substitute for that part,
independent of the metadata issue above.

## What this blocks

Everything in Task 2 that needs to call into Exposed: `AuditMessagesTable` /
`AuditFailuresTable` / `AuditHeadersTable` (table *declarations* are fine — only their column
builders are used, all inherited), `AuditRepository.insert` / `.deleteOlderThan` (blocked — needs
`suspendTransaction`, `insert`, `select`, `deleteWhere`), and by extension `AuditServiceImpl`,
`AuditRetention` and `EventbusAuditMicroservice`, none of which were written.

## What was reverted

The `surf-eventbus-audit-microservice` module (build file, table declarations, repository,
`AuditServiceImpl`, test scaffolding) and the settings.gradle.kts / `surf-eventbus-audit`
buildscript changes that supported it were all removed from the working tree. None of it had
been committed. `surf-eventbus-audit-api` (Task 1, the `AuditService` contract) is untouched and
stays committed — it doesn't depend on surf-database.

## Options for whoever picks this back up

- Fix or bump `surf-database-r2dbc` so its shaded jar's Kotlin metadata matches the relocated
  package (the actual bug, upstream).
- Write `AuditRepository` against raw R2DBC (`ConnectionFactory`/`Statement`) instead of the
  Exposed DSL, sidestepping the broken metadata entirely. More verbose, but doesn't depend on a
  fix landing upstream.
- Call the shaded Exposed functions via reflection. Not recommended — fragile and defeats the
  point of using Exposed at all.

## Where to resume

`TODO(surf-eventbus-audit-microservice)` markers are left at the points where a client would
otherwise reach the (nonexistent) microservice:

- `surf-eventbus-rabbitmq/surf-eventbus-rabbitmq-core/.../core/connection/RabbitConnectionImpl.kt`,
  at the `auditSinkDelegate` construction — the client-side wiring is complete and correct
  (Task 1), it just has nothing listening on `surf-eventbus-audit` yet.
- `docs/superpowers/plans/2026-07-31-eventbus-4-audit-plattform-doku.md`, Task 2 — steps
  unchecked, with a pointer back to this note.
