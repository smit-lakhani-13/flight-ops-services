# Operations

What to set, what to watch, and what to do at three in the morning.

I took the metric names from `/actuator/prometheus` on a running instance and
the environment variables from `application.yml`. The log lines are copied from
real output.

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

These are the environment variables `application.yml` reads. Where a variable
has no default in a profile, startup fails in that profile when it is absent. I
chose that so a missing credential stops the pod before it takes traffic.
Beyond the table, `SPRING_PROFILES_ACTIVE` picks the profile (see
[Profiles](#profiles)), and the image's entrypoint passes `JAVA_OPTS` to the
JVM.

| Variable | Default | What it does |
|---|---|---|
| `SERVER_PORT` | `8080` | HTTP port |
| `DB_URL` | `jdbc:postgresql://localhost:5432/flightops` in `postgres`. **None in `prod`** | JDBC URL. Unset in `prod`, startup fails with `'url' must start with "jdbc"`. The default profile uses H2 and does not read it |
| `DB_USER` | `postgres` in `postgres`. None in `prod` | database user |
| `DB_PASSWORD` | *(none)* | **Required** in `postgres` and `prod`. Unset, startup fails at Flyway's first connection with `password authentication failed`, which does not name the variable. See the `postgres` profile in `application.yml` |
| `API_PASSWORD` | `{noop}dev-secret`. None in `prod` | the `api` account. **Must carry an `{id}` prefix**, such as `{bcrypt}$2y$10$…`. Unprefixed, or unset in `prod`, startup fails naming `app.security.apiPassword` |
| `OPS_PASSWORD` | `{noop}dev-ops`. None in `prod` | the `ops` account. Same prefix rule |
| `APP_EVENTS_PUBLISHER` | `log`; `sqs` in `prod` | `sqs` or `log`; any other value stops startup. Read in every profile: the base document is `${APP_EVENTS_PUBLISHER:log}` and `prod` is `${APP_EVENTS_PUBLISHER:sqs}`. `log` writes and drains the outbox without sending anything. Choosing `sqs` without `SQS_QUEUE_URL` fails at startup, because `SqsEventPublisher` requires `app.aws.sqs-queue-url`. A laptop publishes only when someone sets both |
| `SQS_QUEUE_URL` | *(empty)* | required when the publisher is `sqs` |
| `AWS_REGION` | `ap-south-1` | |
| `OUTBOX_ENABLED` | `true` | `false` stops the drain and the pruner. Rows still accumulate |
| `OUTBOX_POLL_INTERVAL` | `1000` | milliseconds between drain attempts |
| `OUTBOX_BATCH_SIZE` | `100` | rows claimed per pass. With the default interval, about 100 events a second per replica |
| `OUTBOX_MAX_ATTEMPTS` | `10` | after this many failures a row is dead and is never claimed again |
| `OUTBOX_RETRY_BACKOFF` | `2s` | how long the **first** retry of a failed row waits, doubling per attempt. Zero disables backoff and is a test-only setting |
| `OUTBOX_MAX_RETRY_BACKOFF` | `5m` | the cap on that doubling. With the defaults, ten attempts span about 13.5 minutes (810 s of waits). Before the backoff existed they took ten seconds |
| `OUTBOX_RETENTION` | `7d` | how long published rows are kept before pruning |
| `OUTBOX_PRUNE_INTERVAL` | `1h` | how often the pruner runs |
| `OUTBOX_PRUNE_BATCH_SIZE` | `1000` | rows per `DELETE`, to keep the lock short |
| `SWAGGER_UI_ENABLED` | `true` | serves `/swagger-ui.html`. The OpenAPI JSON is served either way |
| `OTLP_METRICS_ENABLED` | `false` | pushes metrics over OTLP. Leave it off unless a collector exists |
| `SECURITY_LOG_LEVEL` | `INFO` | `DEBUG` explains every authorisation decision. Verbose |
| `SQL_LOG_LEVEL` | `WARN`; `DEBUG` in `postgres` | `DEBUG` logs every statement |

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

The default profile is a laptop profile. A container started with no
`SPRING_PROFILES_ACTIVE` gets it, and that fails open: the application starts,
serves every endpoint, passes the demo and stores nothing. For that reason
`compose.yaml` pins the profile directly, and `k8s/base/configmap.yaml` sets it
for the cluster. The Deployment pulls the whole ConfigMap in with `envFrom`.

## Health

| Endpoint | Auth | Answers |
|---|---|---|
| `/actuator/health` | none | `UP`/`DOWN` and the probe group names, nothing else |
| `/actuator/health/liveness` | none | should the kubelet restart this container |
| `/actuator/health/readiness` | none | should traffic come here |
| `/actuator/health` | any valid credential, in every profile except `prod` | the same, plus components: `db`, `diskSpace`, `ssl`, … |

The probes are open to anonymous callers because the kubelet has no
credentials and cannot be given any. An anonymous caller gets a status only,
so it cannot find out which database this is.

Component detail depends on the profile. The base configuration sets
`show-details: when-authorized` with no role, so outside `prod` the `api` user
sees the components as well as `ops`. That includes the absolute path
`diskSpace` reports. The `prod` profile sets `show-details: never`, so on the
cluster no one sees components, and `ops` reads `/actuator/metrics` instead.

The two probes ask different questions, and mixing them up is a classic
outage. A readiness failure takes one pod out of the load balancer, and a
liveness failure restarts it. Liveness checks only `livenessState`, and
readiness adds `db`. Point liveness at the database and a database blip
restarts every replica at once.

## Metrics

`/actuator/prometheus` needs `ops` credentials. On top of everything
Micrometer provides, the service adds five counters and two gauges. Three
counters cover bookings; the outbox has two counters and both gauges.

| Metric | Type | Labels | Reading |
|---|---|---|---|
| `bookings_booked_total` | counter | `outcome=created\|replayed` | a high `replayed` share means clients are retrying. That is fine, and useful to know |
| `bookings_cancelled_total` | counter | `outcome=cancelled\|already_cancelled` | `already_cancelled` is a replay that changed nothing. It is not an error |
| `bookings_lock_timeout_total` | counter | | a booking gave up after 3s waiting for the seat lock. **Non-zero means users are seeing 503s** |
| `outbox_pending` | gauge | | rows waiting to publish and still within the attempt ceiling. It includes rows the retry backoff is holding back. A transport outage therefore shows here as a plateau for as long as ten attempts take (about 13.5 minutes with the defaults), and only then moves to `outbox_dead` |
| `outbox_dead` | gauge | | rows that exhausted `OUTBOX_MAX_ATTEMPTS`. **Should always be 0** |
| `outbox_publish_total` | counter | `result=success\|failure\|exhausted` | |
| `outbox_pruned_total` | counter | | published rows deleted by retention |

HTTP metrics cannot express any of these. `http_server_requests` counts a 201
for a new booking and a 201 for an idempotent replay the same way, because
both are the same status on the same route. The difference between them is
the behaviour I most want to watch.

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

The last field is the `X-Request-Id`, which is on every response, including 401
and 403. A user reporting "it said 403" can hand over one string that finds the
request.

Following one booking across the queue is the interesting case. The outbox
drain runs on a scheduler, so it has a *different* trace from the request that
created the row. The link is the `traceparent` the writer captured:

```
[flight-ops-service,10cd4f19…,da65ef23…,599214ae-…] BookingWriter : Booked 2 seat(s) on UA123 (booking 1, 178 seats left)
[flight-ops-service,d21efe52…,010b24c1…,]            LoggingEventPublisher : [EVENT] BookingCreated {traceparent=00-10cd4f19…-da65ef23…-02} -> {"bookingId":"1",…}
```

The publisher's own trace is `d21efe52…`. The `traceparent` it carries is
`10cd4f19…`, the booking request's. That value goes onto the SQS message as an
attribute and the Lambda logs it, so one `grep` spans three processes:

```bash
kubectl logs -n flight-ops -l app=flight-ops --tail=10000 | grep 10cd4f19102abf7a3f922f252b6d4a97

aws logs filter-log-events \
  --log-group-name /aws/lambda/booking-event-handler \
  --filter-pattern '"10cd4f19102abf7a3f922f252b6d4a97"'
```

On the `prod` profile the same lines are ECS JSON, one object per line, with
`trace.id` and `requestId` as fields. Both forms carry the same data. The
human-readable form is the default because the demo cluster has nothing
collecting logs.

## What to alert on

In the order they matter:

| | Condition | Why |
|---|---|---|
| 1 | `outbox_dead > 0` | an event will never be published, and nothing downstream will report it missing |
| 2 | `outbox_pending` rising for 10 min | the drain is losing to the write rate, or SQS is rejecting |
| 3 | `rate(bookings_lock_timeout_total[5m]) > 0` | users are getting 503s on seat contention |
| 4 | SQS `ApproximateNumberOfMessagesVisible` on the **DLQ** `> 0` | the Lambda failed three times on the same message |
| 5 | readiness failing on any pod for 5 min | usually the database |
| 6 | RDS `DatabaseConnections` above 50 | more than the service's own pools can open: 4 pods × 10, or 5 × 10 during a rollout surge. Something else is connecting, or `maxReplicas` went up without a bigger instance class (about 112 connections). See [DEPLOYMENT.md §7](DEPLOYMENT.md#7-what-breaks-first) |

## Playbooks

### Pods CrashLoopBackOff, logs mention a password

Find the pod that is crashing, then read the log of its last run:

```bash
kubectl get pods -n flight-ops -l app=flight-ops
kubectl logs <pod-in-CrashLoopBackOff> -n flight-ops --previous
```

Name the pod. After a Secret change and a `rollout restart`, the old pods stay
Ready, because the rollout uses `maxUnavailable: 0`. Pointed at the
Deployment, `kubectl logs` picks a Ready pod, and that pod has no previous run
to show.

`FATAL: password authentication failed for user …` points at `DB_PASSWORD`
first. An unset value does not name itself. Relaxed binding hands the literal
`${DB_PASSWORD}` to the driver, and Flyway's first connection fails as if the
password were wrong. The application has no default to fall back on, and
`application.yml` explains why beside the property.

If the log names `app.security.apiPassword` or `app.security.opsPassword`
instead, see the next playbook.

Either way, check the Secret. All three of `DB_PASSWORD`, `API_PASSWORD` and
`OPS_PASSWORD` must be present:

```bash
kubectl get secret flight-ops-secret -n flight-ops -o jsonpath='{.data}' | tr ',' '\n'
```

### Pods crash-loop at startup, and the log names `app.security.apiPassword`

`API_PASSWORD` (or `OPS_PASSWORD`) is unset or has no `{id}` prefix.
`ApiSecurityProperties` rejects it when the properties are bound, so the pod
never becomes Ready. The crashing pod's `--previous` log (see the playbook
above) has this line, followed by the property and the reason:

```
Binding to target com.smit.flightops.config.ApiSecurityProperties failed
```

Prefix the hash:

```
{bcrypt}$2y$10$…
```

The failure report prints the rejected value on its `Value:` line. If someone
set a plaintext password without the prefix, that password is now in the pod
log, so rotate it (see
[Rotating the API or ops password](#rotating-the-api-or-ops-password)).

The check exists because Spring's delegating encoder throws on a value it
cannot read, where a wrong password would simply fail to match. I checked this:
an unprefixed bcrypt string raises `IllegalArgumentException` on the first
login.

The check only requires some `{id}`. An id the encoder does not know, such as
`{BCRYPT}` or `{bcyrpt}`, passes it. The pod then goes Ready, and every login
as that user gets a 500 with the `INTERNAL_ERROR` body. The log has
`There is no password encoder mapped for the id 'BCRYPT'`. The id is
case-sensitive, so write `{bcrypt}`.

### Events stop arriving; `outbox_pending` climbs

```bash
kubectl logs -n flight-ops -l app=flight-ops | grep -i outbox | tail -20
```

Then check, in order:

1. Is `SQS_QUEUE_URL` set and correct?
2. Does the pod have IRSA? `kubectl describe pod` should show
   `AWS_WEB_IDENTITY_TOKEN_FILE`.
3. Is `OUTBOX_ENABLED` still `true`?

Read `outbox_pending` together with `outbox_publish_total{result="failure"}`.
The gauge counts every unpublished row still inside the attempt ceiling,
including rows the retry backoff is holding back. So these two situations
produce the same climbing gauge:

| | `outbox_pending` | `outbox_publish_total{result="failure"}` | What it is |
|---|---|---|---|
| Rows arrive faster than the drain | climbing | flat | throughput. Raise `OUTBOX_BATCH_SIZE`, or lower `OUTBOX_POLL_INTERVAL` |
| The transport is refusing | climbing | climbing | an outage or a misconfiguration. The rows are waiting out their backoff. With the defaults they reach `outbox_dead` about 13.5 minutes after the first failure |

To see what a row is waiting for, ask the table. A row whose `next_attempt_at`
is in the future is deferred, and the poller will try it again:

```sql
SELECT id, attempts, next_attempt_at, last_error
  FROM outbox_events
 WHERE published_at IS NULL
 ORDER BY id
 LIMIT 20;
```

### `outbox_dead > 0`: a poison row

The claim query skips a row that has failed `OUTBOX_MAX_ATTEMPTS` times, for
good, so it cannot block the head of the queue. With the default backoff a
row gets there after about 13.5 minutes of failures. So `outbox_dead` rising is
a *late* signal: the failure counter started moving that much earlier.

Find the row, fix the cause, then re-drive it. The `UPDATE` has to set
`next_attempt_at = NULL`. Otherwise the row keeps the future timestamp from its
last failure, and the claim query skips it for up to five more minutes. The
operator is left watching a re-drive that appears to do nothing.

```sql
SELECT id, event_type, attempts, last_error, created_at
  FROM outbox_events
 WHERE published_at IS NULL AND attempts >= 10;

UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = 42;
```

The next poll picks it up. Resetting without fixing the cause burns ten more
attempts.

### The DLQ has messages

```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10
```

Read the body and fix the handler or the data. Then redrive with the console's
"Start DLQ redrive", or re-send the messages to the main queue. Delete a
message that is permanently malformed, and leave a note saying why. Left
alone, it expires after 14 days with no record.

### 503s with `Retry-After`: a lock timeout storm

Concurrent bookings for the *same flight* queue behind `SELECT … FOR UPDATE`.
That queueing is the oversell guarantee, and it is working as designed. Past
`lock_timeout = 3s` the request gets a 503 with `Retry-After`. That is correct
under contention, and a problem if it is sustained. Check whether one flight
is hot (`bookings_lock_timeout_total` against the per-flight booking rate) and
whether a transaction is stuck:

```sql
SELECT pid, state, wait_event_type, query_start, left(query, 80)
  FROM pg_stat_activity
 WHERE state <> 'idle' ORDER BY query_start;
```

### Flyway refuses to start

`FlywayValidateException` on a checksum means a migration file changed after
it was applied. Never edit an applied migration; add a new one. On a demo
database, drop and recreate it. On any other, first establish which version is
correct, then run `flyway repair`.

### RDS unreachable

The database security group admits the cluster SG and the shared node SG on
5432. `deploy/aws/data.yaml` sets both from the live eksctl stack outputs. If
the cluster was recreated, those ids changed, and the data stack still points
at the old ones.

### Rotating the API or ops password

```bash
NEW=$(openssl rand -base64 18 | tr -d '/+= ')
HASH="{bcrypt}$(htpasswd -bnBC 10 "" "$NEW" | tr -d ':\n')"
kubectl patch secret flight-ops-secret -n flight-ops \
  -p "{\"stringData\":{\"API_PASSWORD\":\"$HASH\"}}"
kubectl rollout restart deployment/flight-ops -n flight-ops
```

For the ops password, patch `OPS_PASSWORD` instead.

The restart is required. The password is injected as an environment variable,
and environment variables are read once at container start. A mounted volume
would update in place, but it would still need a restart, because Spring binds
the property at startup either way.

## What is not wired up

- **Nothing scrapes `/actuator/prometheus`.** The demo cluster has no
  Prometheus, no Grafana and no Alertmanager. The metrics are correct and
  exported, and the PromQL above is what you would write once something scrapes
  them. Adding kube-prometheus-stack costs roughly $1/day of extra node
  capacity.

- **Nothing ships pod logs anywhere.** `kubectl logs` is the interface. The
  cross-process trace hunt is `kubectl logs | grep <traceId>` on this side and
  CloudWatch Logs Insights on the Lambda's log group on the other.

- **No traces are exported.** Micrometer Tracing generates the ids and puts
  them in the logs. OTLP export activates only when
  `management.opentelemetry.tracing.export.otlp.endpoint` is set, and it is
  not. The log line is the trace.

- **No alerting.** The alert table above is a list. Nothing pages anyone.

That is fine for a two-week demo on a $7.72/day cluster, and not for
production.
