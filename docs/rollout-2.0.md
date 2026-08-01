# Rolling out surf-eventbus 2.0

In the order an operator needs it. Steps 1 and 2 break **silently** if skipped — the process
either comes up healthy pointing at nothing, or fails to declare a queue at connect time.

Version 2.0 is neither wire- nor ABI-compatible with 1.6.x/1.5.x. Mixed operation does not work;
see step 5.

---

## 1. Rename the environment variables

Every variable moved under one prefix. **A pre-2.0 variable is simply not read** — the value
falls back to its default, which is `localhost` for both hosts, and the process starts looking
perfectly healthy while pointing at nothing.

| Before | Now |
|---|---|
| `SURF_RABBITMQ_HOST` | `SURF_EVENTBUS_RABBITMQ_HOST` |
| `SURF_RABBITMQ_PORT` | `SURF_EVENTBUS_RABBITMQ_PORT` |
| `SURF_RABBITMQ_USERNAME` | `SURF_EVENTBUS_RABBITMQ_USERNAME` |
| `SURF_RABBITMQ_PASSWORD` | `SURF_EVENTBUS_RABBITMQ_PASSWORD` |
| `SURF_RABBITMQ_VHOST` | `SURF_EVENTBUS_RABBITMQ_VHOST` |
| `SURF_RABBITMQ_TIMEOUT` | `SURF_EVENTBUS_RABBITMQ_TIMEOUT` |
| `SURF_RABBITMQ_REQUEST_TIMEOUT_SECONDS` | `SURF_EVENTBUS_RABBITMQ_REQUEST_TIMEOUT_SECONDS` |
| `SURF_RABBITMQ_PUBLISHER_POOL_SIZE` | `SURF_EVENTBUS_RABBITMQ_PUBLISHER_POOL_SIZE` |
| `SURF_RABBITMQ_SERVER_PREFETCH_COUNT` | `SURF_EVENTBUS_RABBITMQ_SERVER_PREFETCH_COUNT` |
| `SURF_RABBITMQ_PERSIST_REQUESTS` | `SURF_EVENTBUS_RABBITMQ_PERSIST_REQUESTS` |
| `SURF_RABBITMQ_PERSIST_RESPONSES` | `SURF_EVENTBUS_RABBITMQ_PERSIST_RESPONSES` |
| `SURF_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED` | `SURF_EVENTBUS_RABBITMQ_OUTGOING_REQUEST_CHUNKING_ENABLED` |
| `SURF_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED` | `SURF_EVENTBUS_RABBITMQ_OUTGOING_RESPONSE_CHUNKING_ENABLED` |
| `SURF_REDIS_HOST` | `SURF_EVENTBUS_REDIS_HOST` |
| `SURF_REDIS_PORT` | `SURF_EVENTBUS_REDIS_PORT` |
| `SURF_REDIS_PASSWORD` | `SURF_EVENTBUS_REDIS_PASSWORD` |
| `SURF_REDIS_CLIENT_NAME` | `SURF_EVENTBUS_REDIS_CLIENT_NAME` |

Find what is still set:

```bash
env | grep -E '^SURF_(RABBITMQ|REDIS)_'
```

> Earlier 2.0 pre-releases failed the start on a leftover old variable. That guard was removed:
> it could only ever check a list someone maintained by hand, and it made an unrelated variable
> that happened to match the prefix fatal. **Nothing warns you now — run the command above.**

### Config files moved too

`rabbitmq.yml` and the Redis `config.yml` are replaced by one `eventbus.yml` with a `rabbitmq:`
and a `redis:` section, plus an optional per-plugin `eventbus-plugin.yml`. There is no migration:
a fresh `eventbus-plugin.yml` is written full of `__default__` sentinels, which override nothing
until you edit them. That is the correct starting point — the global file supplies the values.

---

## 2. Delete the old `surf.service.*` queues

`x-dead-letter-exchange` is gone from the queue arguments, and **arguments are part of a queue's
identity**. Redeclaring an existing `surf.service.*` with different arguments fails at connect
with `PRECONDITION_FAILED (406)` — the service will not start.

```bash
rabbitmqctl list_queues name | grep '^surf\.'
rabbitmqadmin delete queue name=surf.service.<service>
```

Delete every `surf.service.*`, or recreate the vhost. Do this while the old services are stopped
and before the new ones start.

The dead-letter queues themselves (`surf.dlx`, `surf.dlq.*`, `surf.unroutable`) are no longer
declared or read by anything. Delete them once the rollout is done; anything still in them is
from before the upgrade and this version will not process it.

---

## 3. The audit trail has no writer yet

The reporting paths are wired and every loss now produces an `AuditReport`. The microservice
that writes those to a database does not exist yet.

Reports are published with `mandatory = false`, so while nothing consumes them they are
discarded at the broker. That costs nothing and blocks nothing.

**What it means for you today:** a missing audit row is not evidence that nothing went wrong.
Until the writer ships, the logs are the record. Watch for `SEVERE` lines from
`RabbitListenerHandlerManager`, `ReturnListenerBridge` and `QueryDispatcher`.

---

## 4. One plugin per server, not two

A server now loads a single plugin — `surf-eventbus-platform-paper` or
`surf-eventbus-platform-velocity` — which serves both transports and ships one relocated Netty.

**Remove the old plugins first:** `surf-rabbitmq-paper`/`-velocity` and the surf-redis plugins.
Two plugins each bringing their own Netty is exactly what this replaces, and running both at
once is not a supported configuration.

Paper and Velocity could not reach Redis at all before 2.0, so `publish` and `query` are new
capabilities there rather than changed ones.

---

## 5. Deploy everything together

Wire format and API both changed. A 1.6.x service and a 2.0 service cannot talk to each other:

- RPC packets differ, and a mismatch is discarded as `UNDESERIALIZABLE` rather than retried —
  a peer speaking another protocol will speak it again on redelivery.
- Events and queries moved to Redis entirely; a 1.6.x process is not listening there.

Stop everything, upgrade, start everything. There is no rolling upgrade path.

---

## Checklist

- [ ] `env | grep -E '^SURF_(RABBITMQ|REDIS)_'` returns nothing
- [ ] `SURF_EVENTBUS_*` variables set for both transports
- [ ] `eventbus.yml` in place; old `rabbitmq.yml` / Redis `config.yml` retired
- [ ] every `surf.service.*` queue deleted, or the vhost recreated
- [ ] old `surf-rabbitmq-*` and surf-redis plugins removed from every server
- [ ] all services stopped, upgraded, and started together
- [ ] someone knows the audit trail is best effort until its writer ships
