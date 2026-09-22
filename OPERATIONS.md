# Operations

What to set, what to watch, and what to do at three in the morning.

Everything here was read off a running instance rather than remembered: the
metric names come from `/actuator/prometheus`, the environment variables from
`application.yml`, and the log lines are real.

## Contents

- [Configuration](#configuration)
- [Profiles](#profiles)
- [Health](#health)
- [Metrics](#metrics)
- [Logs, and following one booking](#logs-and-following-one-booking)
- [What to alert on](#what-to-alert-on)
- [Playbooks](#playbooks)
- [What is not wired up](#what-is-not-wired-up)

## Configuration

Every environment variable the application reads. Anything not listed is not
read, and anything listed without a default fails startup if absent — which is
deliberate, because a missing database password should stop the pod rather than
let it come up and fail on the first request.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/flightops` | JDBC URL. **No default in the `prod` profile** |
| `DB_USER` | `postgres` | database user. No default in `prod` |
| `DB_PASSWORD` | *(none)* | **required.** Startup fails without it, by design — see `application.yml` |
| `API_PASSWORD` | `{noop}dev-secret` | the `api` account. **Must carry an `{id}` prefix**, e.g. `{bcrypt}$2y$10$…` |
| `OPS_PASSWORD` | `{noop}dev-ops` | the `ops` account. Same prefix rule |
| `APP_EVENTS_PUBLISHER` | **read only in `prod`**, where it defaults to `sqs` | `sqs` or `log`, and nothing else — `EventProperties` rejects a third value at startup. Outside `prod` the variable is **ignored**: the base document hard-codes `app.events.publisher: log`, with no placeholder, so a laptop or the compose stack cannot be talked into publishing to a real queue by an exported variable. `log` writes and drains the outbox without sending anything. In `prod` it is `${APP_EVENTS_PUBLISHER:sqs}`, which is why a pod with a blank `SQS_QUEUE_URL` fails every publish and a laptop never does |
| `SQS_QUEUE_URL` | *(empty)* | required when the publisher is `sqs` |
| `AWS_REGION` | `ap-south-1` | |
| `OUTBOX_ENABLED` | `true` | `false` stops the drain. Rows still accumulate |
| `OUTBOX_POLL_INTERVAL` | `1000` | milliseconds between drain attempts |
| `OUTBOX_BATCH_SIZE` | `100` | rows claimed per pass. With the default interval, ~100 events/sec/replica |
| `OUTBOX_MAX_ATTEMPTS` | `10` | after this many failures a row is dead and never claimed again |
| `OUTBOX_RETRY_BACKOFF` | `2s` | how long the **first** retry of a failed row waits, doubling per attempt. Zero disables backoff and is a test-only setting |
| `OUTBOX_MAX_RETRY_BACKOFF` | `5m` | the cap on that doubling. With the defaults, ten attempts span roughly 13 minutes rather than the ten seconds they took before the backoff existed |
| `OUTBOX_RETENTION` | `7d` | how long published rows are kept before pruning |
| `OUTBOX_PRUNE_INTERVAL` | `1h` | how often the pruner runs |
| `OUTBOX_PRUNE_BATCH_SIZE` | `1000` | rows per DELETE, to keep the lock short |
| `SWAGGER_UI_ENABLED` | `true` | serves `/swagger-ui.html`. The OpenAPI JSON is served regardless |
| `OTLP_METRICS_ENABLED` | `false` | |
| `SECURITY_LOG_LEVEL` | `INFO` | `DEBUG` explains every authorisation decision. Verbose |
| `SQL_LOG_LEVEL` | `WARN` | `DEBUG` logs every statement |

To check this table has not drifted:

```bash
grep -oE '\$\{[A-Z_]+' src/main/resources/application.yml | sort -u
```

## Profiles

| Profile | Database | Use |
|---|---|---|
| *(default)* | H2, in memory, seeded with 3 flights | laptop, tests. **State is lost on restart** |
| `postgres` | PostgreSQL, Flyway migrations, `SET lock_timeout` | `compose.yaml`, and local work against real SQL |
| `prod` | PostgreSQL, everything from the environment | the cluster |

The default profile is a laptop profile and it is the one you get by running the
container with no `SPRING_PROFILES_ACTIVE`. That fails open: the application
starts, serves every endpoint, passes the demo, and stores nothing.
`compose.yaml` pins it directly, and `k8s/base/configmap.yaml` sets it for the cluster (the Deployment pulls the whole ConfigMap in with `envFrom`)
for that reason.

## Health

| Endpoint | Auth | Answers |
|---|---|---|
| `/actuator/health` | none | `UP`/`DOWN` and nothing else |
| `/actuator/health/liveness` | none | should the kubelet restart this container |
| `/actuator/health/readiness` | none | should traffic come here |
| `/actuator/health` | `ops` | the same, plus components: `db`, `diskSpace`, `ssl`, … |

Open to anonymous callers on purpose: the kubelet has no credentials and cannot
be given any. Detail is not open — anonymous gets a status, `ops` gets the
component breakdown, so an unauthenticated caller cannot enumerate which
database this is.

The two probes are genuinely different questions, and conflating them is a
classic outage: a readiness failure takes one pod out of the load balancer,
while a liveness failure restarts it. Point liveness at the database and a
database blip restarts every replica simultaneously.

## Metrics

`/actuator/prometheus`, `ops` credentials required. Five domain counters and
two gauges, on top of everything Micrometer provides — three about bookings,
two about the outbox:

| Metric | Type | Labels | Reading |
|---|---|---|---|
| `bookings_booked_total` | counter | `outcome=created\|replayed` | a high `replayed` share means clients are retrying — fine, and worth knowing |
| `bookings_cancelled_total` | counter | `outcome=cancelled\|already_cancelled` | `already_cancelled` is a no-op replay, not an error |
| `bookings_lock_timeout_total` | counter | — | seat-lock contention gave up after 3s. **Non-zero means users are seeing 503s** |
| `outbox_pending` | gauge | — | rows waiting to publish and still within the attempt ceiling. **Includes rows the retry backoff is deliberately holding back**, so a transport outage shows as a plateau here for as long as ten attempts take — roughly 13 minutes with the defaults — rather than as an instant move to `outbox_dead` |
| `outbox_dead` | gauge | — | rows that exhausted `OUTBOX_MAX_ATTEMPTS`. **Should always be 0** |
| `outbox_publish_total` | counter | `result=success\|failure\|exhausted` | |
| `outbox_pruned_total` | counter | — | published rows deleted by retention |

These exist because HTTP metrics cannot express any of them. `http_server_requests`
counts a 201 for a genuinely new booking and a 201 for an idempotent replay
identically — they are the same status on the same route — and the difference
between them is the entire behaviour worth monitoring.

Useful queries:

```promql
# events are draining, not accumulating
outbox_pending

# anything at all here is an incident
outbox_dead > 0

# contention: the share of bookings that timed out on the seat lock
rate(bookings_lock_timeout_total[5m])
  / rate(bookings_booked_total[5m])

# how much traffic is retries
rate(bookings_booked_total{outcome="replayed"}[5m])
  / rate(bookings_booked_total[5m])
```

## Logs, and following one booking

Every log line carries the application name, trace id, span id and request id:

```
[flight-ops-service,10cd4f19102abf7a3f922f252b6d4a97,da65ef2344ac0aa1,599214ae-5bf0-4fca-8bbf-2eddc2ce7cda]
```

The last field is the `X-Request-Id`, which is on every response including 401
and 403 — so a user reporting "it said 403" can hand over one string that finds
the exact request.

Following one booking across the queue is the interesting case, because the
outbox drain runs on a scheduler and therefore has a *different* trace from the
request that created the row. The link is the `traceparent` the writer captured:

```
[flight-ops-service,10cd4f19…,da65ef23…,599214ae-…] BookingWriter : Booked 2 seat(s) on UA123 (booking 1, 178 seats left)
[flight-ops-service,d21efe52…,010b24c1…,]            LoggingEventPublisher : [EVENT] BookingCreated {traceparent=00-10cd4f19…-da65ef23…-02} -> {"bookingId":"1",…}
```

The publisher's own trace is `d21efe52…`; the `traceparent` it carries is
`10cd4f19…`, the booking request's. That value goes onto the SQS message as an
attribute and the Lambda logs it, so one `grep` spans three processes:

```bash
kubectl logs -n flight-ops -l app=flight-ops --tail=10000 | grep 10cd4f19102abf7a3f922f252b6d4a97

aws logs filter-log-events \
  --log-group-name /aws/lambda/booking-event-handler \
  --filter-pattern '"10cd4f19102abf7a3f922f252b6d4a97"'
```

On the `prod` profile the same lines are ECS JSON, one object per line, with
`trace.id` and `requestId` as fields. Both forms carry the same data; the
human-readable one is the default because the demo cluster has nothing
collecting logs.

## What to alert on

In the order they matter:

| | Condition | Why |
|---|---|---|
| 1 | `outbox_dead > 0` | an event will never be published. Silent data loss downstream |
| 2 | `outbox_pending` rising for 10 min | the drain is losing to the write rate, or SQS is rejecting |
| 3 | `rate(bookings_lock_timeout_total[5m]) > 0` | users are getting 503s on seat contention |
| 4 | SQS `ApproximateNumberOfMessagesVisible` on the **DLQ** `> 0` | the Lambda failed three times on the same message |
| 5 | readiness failing on any pod for 5 min | usually the database |
| 6 | RDS `DatabaseConnections` near 100 | the pool ceiling; see [DEPLOYMENT.md §7](DEPLOYMENT.md#7-what-breaks-first) |

## Playbooks

### Pods CrashLoopBackOff, logs mention a password

`DB_PASSWORD` is missing. The application refuses to start rather than default
to something — the reasoning is in `application.yml` beside the property.

```bash
kubectl get secret flight-ops-secret -n flight-ops -o jsonpath='{.data}' | tr ',' '\n'
```

All three of `DB_PASSWORD`, `API_PASSWORD`, `OPS_PASSWORD` must be present.

### Pods start, every request 500s on authentication

`API_PASSWORD` has no `{id}` prefix. Spring's delegating encoder throws on a
bare hash — verified: an unprefixed bcrypt string raises
`IllegalArgumentException` rather than quietly failing to match. Prefix it:

```
{bcrypt}$2y$10$…
```

### Events stop arriving; `outbox_pending` climbs

```bash
kubectl logs -n flight-ops -l app=flight-ops | grep -i outbox | tail -20
```

Then, in order: is `SQS_QUEUE_URL` set and correct; does the pod have IRSA
(`kubectl describe pod` should show `AWS_WEB_IDENTITY_TOKEN_FILE`); is
`OUTBOX_ENABLED` still `true`.

**Read `outbox_pending` together with `outbox_publish_total{result="failure"}`,
not on its own.** The gauge counts every unpublished row still inside the
attempt ceiling, and that includes rows the retry backoff is holding back, so
these two situations produce the same climbing gauge:

| | `outbox_pending` | `outbox_publish_total{result="failure"}` | What it is |
|---|---|---|---|
| Publishing faster than draining | climbing | flat | throughput. Raise `OUTBOX_BATCH_SIZE`, or lower `OUTBOX_POLL_INTERVAL` |
| The transport is refusing | climbing | climbing | an outage or a misconfiguration. The rows are waiting out their backoff and will reach `outbox_dead` about 13 minutes after the first failure, with the defaults |

To see what a row is actually waiting for, ask the table rather than the gauge —
`next_attempt_at` in the future is a row that is deferred, not stuck:

```sql
SELECT id, attempts, next_attempt_at, last_error
  FROM outbox_events
 WHERE published_at IS NULL
 ORDER BY id
 LIMIT 20;
```

### `outbox_dead > 0` — a poison row

A row that failed `OUTBOX_MAX_ATTEMPTS` times is skipped by the claim query
forever, which is what stops it blocking the queue head. It takes roughly 13
minutes of failures to get there with the default backoff, so `outbox_dead`
rising is a *late* signal — the failure counter moved ten minutes earlier.

Find it, fix the cause, then re-drive. The `next_attempt_at = NULL` in the
`UPDATE` is not decoration: without it the row keeps the future timestamp its
last failure set, and the claim query skips it for as long as five more minutes
while the operator watches a re-drive that appears to do nothing.

```sql
SELECT id, event_type, attempts, last_error, created_at
  FROM outbox_events
 WHERE published_at IS NULL AND attempts >= 10;

UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = 42;
```

The next poll picks it up. Resetting without fixing the cause just burns ten
more attempts.

### The DLQ has messages

```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10
```

Read the body, fix the handler or the data, then redrive with the console's
"Start DLQ redrive", or re-send them to the main queue. A permanently
malformed message is deleted deliberately, with a note, not left to expire
quietly after 14 days.

### 503s with `Retry-After` — lock timeout storm

Concurrent bookings for the *same flight* serialise behind `SELECT … FOR
UPDATE`. That queueing is the oversell guarantee, not a bug. Past
`lock_timeout = 3s` the request gets 503 with `Retry-After`, which is correct
behaviour under contention and a problem if it is sustained. Check whether one
flight is hot (`bookings_lock_timeout_total` against the per-flight booking
rate) and whether a transaction is stuck:

```sql
SELECT pid, state, wait_event_type, query_start, left(query, 80)
  FROM pg_stat_activity
 WHERE state <> 'idle' ORDER BY query_start;
```

### Flyway refuses to start

`FlywayValidateException` on a checksum means a migration file changed after it
was applied. Never edit an applied migration — add a new one. If it is a demo
database, drop and recreate; if it is not, `flyway repair` after establishing
which version is actually correct.

### RDS unreachable

The security group admits the cluster SG and the shared node SG on 5432, set by
`deploy/aws/data.yaml` from the live eksctl stack outputs. If the cluster was
recreated, those ids changed and the data stack is pointing at the old ones.

### Rotating the API or ops password

```bash
NEW=$(openssl rand -base64 18 | tr -d '/+= ')
HASH="{bcrypt}$(htpasswd -bnBC 10 "" "$NEW" | tr -d ':\n')"
kubectl patch secret flight-ops-secret -n flight-ops \
  -p "{\"stringData\":{\"API_PASSWORD\":\"$HASH\"}}"
kubectl rollout restart deployment/flight-ops -n flight-ops
```

The restart is required: the password is injected as an environment variable,
and environment variables are read once at container start. A mounted volume
would update in place — and would still need a restart, because Spring binds
the property at startup either way.

## What is not wired up

Stated plainly, because a monitoring document that implies more than exists is
worse than none.

- **Nothing scrapes `/actuator/prometheus`.** There is no Prometheus, no
  Grafana, no alertmanager on the demo cluster. The metrics are correct and
  exported; the PromQL above is what you would write once something scrapes
  them. Adding kube-prometheus-stack is roughly $1/day of extra node capacity.
- **Nothing ships pod logs anywhere.** `kubectl logs` is the interface, so the
  cross-process trace hunt is `kubectl logs | grep <traceId>` on this side and
  CloudWatch Logs Insights on the Lambda's group on the other.
- **No traces are exported.** Micrometer Tracing generates the ids and puts
  them in the logs; OTLP export activates only when
  `management.opentelemetry.tracing.export.otlp.endpoint` is set, and it is
  not. The log line is the trace.
- **The alerts above are a list, not a configuration.** Nothing pages anyone.

All four are deliberate for a two-week demonstration on a $7.72/day cluster,
and all four would be wrong for production.
