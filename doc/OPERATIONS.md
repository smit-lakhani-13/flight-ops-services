# Operations

What to set, what to watch, and what to do when it breaks.

The metric names come from `/actuator/prometheus` on a running instance and
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
| `HTTP_MAX_BODY_BYTES` | `16384` | the largest request body read, in bytes. A declared `Content-Length` over it gets `413 PAYLOAD_TOO_LARGE` unread; a body without one, such as a chunked body, gets it once a read passes the limit, so nothing parses more. A body nothing reads is never counted. Zero or less stops startup with `app.http.max-body-bytes must be positive` |
| `DB_URL` | `jdbc:postgresql://localhost:5432/flightops` in `postgres`. **None in `prod`** | JDBC URL. Unset in `prod`, startup fails with `'url' must start with "jdbc"`. The default profile uses H2 and does not connect to it. Set while the datasource is still in-memory H2, under no profile or one no document matches, it stops startup. See [Profiles](#profiles) |
| `DB_USER` | `postgres` in `postgres`. None in `prod` | database user, for the connection pool and for Flyway alike. In the cluster it is the RDS master user. See [The database user](#the-database-user) |
| `DB_PASSWORD` | *(none)* | **Required** in `postgres` and `prod`. Unset, startup fails at Flyway's first connection with `password authentication failed`, which does not name the variable. See the `postgres` profile in `application.yml` |
| `API_PASSWORD` | `{noop}dev-secret`. None in `prod` | the `api` account. **Must carry an `{id}` prefix** the encoder knows, such as `{bcrypt}$2y$10$…`. Unprefixed, or unset in `prod`, startup fails naming `app.security.api-password (API_PASSWORD)`. An unknown id stops startup too, and so, in `prod`, does any id but `bcrypt`, `pbkdf2`, `scrypt` and `argon2`, such as `{noop}` or `{ldap}`. See [the playbook](#pods-crash-loop-at-startup-and-the-log-names-appsecurityapi-password) |
| `OPS_PASSWORD` | `{noop}dev-ops`. None in `prod` | the `ops` account. Same prefix rule, named `app.security.ops-password (OPS_PASSWORD)` |
| `APP_EVENTS_PUBLISHER` | `log`; `sqs` in `prod` | `sqs` or `log`. Any other value stops startup with `app.events.publisher must be one of [log, sqs], not "<value>"`. Read in every profile: the base document is `${APP_EVENTS_PUBLISHER:log}` and `prod` is `${APP_EVENTS_PUBLISHER:sqs}`. `log` writes and drains the outbox without sending anything. Choosing `sqs` without `SQS_QUEUE_URL` stops startup with `app.events.publisher=sqs requires app.aws.sqs-queue-url (env SQS_QUEUE_URL)`. A laptop publishes only when someone sets both. The startup log names the choice: `Outbox publisher started with event transport 'log'` |
| `SQS_QUEUE_URL` | *(empty)* | required when the publisher is `sqs` |
| `AWS_REGION` | `ap-south-1` | |
| `OUTBOX_ENABLED` | `true` | `true`, `on`, `yes` or `1` runs the drain and the pruner. `false`, `off`, `no` or `0` stops both, and rows still accumulate. Any other value stops startup, naming `app.outbox.enabled`. So does an empty `OUTBOX_ENABLED=`, which is set and so does not take the default |
| `OUTBOX_POLL_INTERVAL` | `1000` | milliseconds from the end of one drain to the start of the next |
| `OUTBOX_BATCH_SIZE` | `100` | rows claimed per pass. They are sent one at a time, so a replica drains about `batch / (poll interval + batch × send latency)` events a second, with both times in seconds: under 100 with the defaults, and never more than `1 / send latency` however large the batch. See [the throughput playbook](#events-stop-arriving-outbox_pending-climbs) |
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

### The database user

The service and its migrations share one database login. The `prod` profile
sets no `spring.flyway.user`, so Flyway, which runs inside each pod at
startup, connects with the pool's `DB_USER` and `DB_PASSWORD`. In the cluster
that login is the RDS master user. `deploy/aws/data.yaml` passes its
`DBUsername` parameter, `flightops` by default, as the `MasterUsername`.
`deploy/k8s/base/configmap.yaml` sets `DB_USER` to `flightops`, and
`deploy/aws/up.sh` writes the master password into `flight-ops-secret` as
`DB_PASSWORD`. On RDS the master user is a member of `rds_superuser`, and the
service's login owns every table, because Flyway created them with it.

So nothing in the database stands between the web tier and the schema. Code
running in a pod, or anyone who can read the Secret, can drop the seat checks
that V2 added (`ck_flights_seat_floor`, `ck_flights_seat_ceiling`) or a whole
table, and can create roles and databases. The data stack keeps no backups
(`BackupRetentionPeriod: 0`), so there is nothing to restore from.

The fix is two logins, and it is not built. A migration user owns the schema,
and only the step that runs Flyway holds its password: an initContainer or a
Job, with `SPRING_FLYWAY_ENABLED=false` on the application container. A
runtime user gets `SELECT`, `INSERT`, `UPDATE` and `DELETE` on the tables and
`USAGE` on their sequences, and nothing else. A compromised pod could still
delete rows, but it could no longer change the schema or use the rights of
`rds_superuser`. [ARCHITECTURE.md](ARCHITECTURE.md#still-open) lists it as
still open.

## Profiles

| Profile | Database | Use |
|---|---|---|
| *(default)* | H2, in memory, seeded with 3 flights | laptop, tests. **State is lost on restart** |
| `postgres` | PostgreSQL, Flyway migrations, `SET lock_timeout` | `compose.yaml`, and local work against real SQL |
| `prod` | PostgreSQL, everything from the environment | the cluster |

The default profile is a laptop profile, and it fails open: the application
starts, serves every endpoint, passes the demo and stores nothing.
`./mvnw spring-boot:run` and a bare `java -jar` get it. The image does not: the
Dockerfile sets `SPRING_PROFILES_ACTIVE=prod`, so a container started with no
profile fails closed. With no `DB_URL`, a bare `docker run` stops with
`'url' must start with "jdbc"`. CI checks that on every push or pull request to
`main`, in the `image` job's step "The image will not start without
a database". The deploy job, which is gated off, would push the image that
step checked and runs no copy of it. `compose.yaml` selects `postgres`, and
`deploy/k8s/base/configmap.yaml` sets `prod` for the cluster. The Deployment
pulls the whole ConfigMap in with `envFrom`.

A profile no document matches, such as `Prod` (profile names are
case-sensitive) or `aws`, keeps the base document's in-memory H2. Each pod would
then run on its own database and pass readiness.
`config/EmbeddedDatabaseGuard.java#refuseInMemoryH2WithDbUrl` stops that at
startup. When `DB_URL` is set and the datasource is still `jdbc:h2:mem:`, the
log reads `DB_URL is set, but the datasource is still the laptop default`,
names the active profiles, and says `SPRING_PROFILES_ACTIVE` must include
`prod` for the cluster or `postgres` for compose or a local PostgreSQL. It
also says never to use `postgres` in the cluster, where it would seed the demo
flights, log SQL at `DEBUG` by default and skip the check that refuses an
unhashed password. The guard keys on `DB_URL` because the ConfigMap and
`compose.yaml` set it and a laptop run on H2 does not. It does not key on the
publisher, because H2 with `sqs` is a supported laptop setup. The catch: a
developer with `DB_URL` exported in the shell has a default-profile
`./mvnw spring-boot:run` refused too, and the H2 tests fail the same way. The
message says to unset `DB_URL` to run on H2.

## Health

| Endpoint | Auth | Answers |
|---|---|---|
| `/actuator/health` | none, or the `api` credential | `UP`/`DOWN` and the probe group names, nothing else |
| `/actuator/health/liveness` | none | should the kubelet restart this container |
| `/actuator/health/readiness` | none | should traffic come here |
| `/actuator/health` | `ops`, in every profile | the same, plus components: `db`, `diskSpace`, `ssl`, … |

The probes are open to anonymous callers because the kubelet holds no
credentials for this service. A probe could send a fixed `Authorization`
header, but that would put a password in the Deployment and fail every probe
after the next rotation. An anonymous caller gets a status only, so it cannot
find out which database this is.

Component detail follows the role. `application.yml` sets
`show-details: when-authorized` with `roles: OPS`, so only the `ops` user sees
the components, `prod` included. The `api` user gets the same status and group
names as an anonymous caller. That keeps the absolute path `diskSpace` reports
away from the credential every client holds.

The two probes ask different questions, and mixing them up is a classic
outage. A readiness failure takes one pod out of the load balancer, and a
liveness failure restarts it. Liveness checks only `livenessState`, and
readiness checks only `readinessState`. Neither checks the database, because
every replica shares it. Point liveness at the database and a database blip
restarts every replica at once. Point readiness at it and the same blip, or
one hot flight's lock waiters filling the pool, takes every pod out at once,
and the load balancer has no target left for any request. Instead the pods
stay in (`HealthGroupsTest.java#readinessLeavesTheDatabaseOut`), and each
answers `503 DATABASE_UNAVAILABLE` with `Retry-After` once the pool's
`connection-timeout`, 5 s in `prod`, runs out. `/actuator/health` still
includes `db`, and [alert 6](#what-to-alert-on) reads it there
(`HealthGroupsTest.java#operatorSeesWhichComponentIsDown`).

## Metrics

`/actuator/prometheus` needs `ops` credentials. On top of everything
Micrometer provides, the service adds five counters and two gauges, from
`observability/BookingMetrics.java` and `observability/OutboxMetrics.java`.

| Metric | Type | Labels | Reading |
|---|---|---|---|
| `bookings_booked_total` | counter | `outcome=created\|replayed` | a replay and a new booking are both a 201 on the same URI, and only this label separates selling seats from a client stuck in a retry loop. A high `replayed` share means clients are retrying. That is fine, and useful to know |
| `bookings_cancelled_total` | counter | `outcome=cancelled\|already_cancelled` | `already_cancelled` is a repeated `DELETE`: it returns 200 and releases nothing. It is not an error. If it climbs while `cancelled` stays flat, a client thinks its cancellations are not sticking |
| `bookings_lock_timeout_total` | counter | | a write gave up after 3s waiting for the flight row lock: a booking or a cancellation, or a flight status change or flight cancellation queued behind one. **A non-zero rate means users are seeing 503s.** It is the earliest sign of the whole write path stalling |
| `outbox_pending` | gauge | | rows waiting to publish and still within the attempt ceiling, including rows the retry backoff is holding back. A rising line is publisher lag, and it shows an SQS outage before any consumer notices missing events. Each failing row stays here for as long as its ten attempts take (about 13.5 minutes with the defaults), and only then moves to `outbox_dead` |
| `outbox_dead` | gauge | | rows that exhausted `OUTBOX_MAX_ATTEMPTS`. **Should always be 0.** A dead row does not come back by itself: the claim never returns it, and its event is never sent until someone re-drives it ([the playbook](#outbox_dead--0-a-poison-row)) |
| `outbox_publish_total` | counter | `result=success\|failure\|exhausted` | the transport's health. A send that uses up a row's last attempt counts under `failure` as well as `exhausted`, so `failure` is the full error rate. That rate shows a partial outage that `outbox_pending` hides while the backlog still drains faster than it grows |
| `outbox_pruned_total` | counter | | published rows deleted by retention. Flat at zero while published rows older than `OUTBOX_RETENTION` pile up means the pruner has stopped, and no other series shows it |

The exporter prints one `# HELP` line per meter name, so both series of a
meter share one description. `BookingMetrics` holds each shared description in
a constant.

The console's Ops page reads the same seven meters as `ops`, through
`/actuator/metrics/{name}` under their dotted names, such as `bookings.booked`
and `outbox.pending`, and splits each labelled counter by its label, so
`created` and `replayed` appear side by side
([web/README.md](../web/README.md#pages)).

HTTP metrics cannot express any of these. `http_server_requests` counts a 201
for a new booking and a 201 for an idempotent replay the same way, because
both are the same status on the same route. The difference between them is
the behaviour I most want to watch.

Both gauges read counts held in memory, and a scrape never touches the
database.
`src/main/java/com/smit/flightops/observability/OutboxMetrics.java#refresh`
runs the two counts every 15 seconds on a thread of its own, `outbox-metrics`,
not on the scheduler thread the drain and the pruner share. A count on the
scrape thread would wait out the pool's connection timeout (5 s under
`postgres` and `prod`, 30 s on H2) while the database is unreachable, once
per gauge. Prometheus's default scrape timeout is 10 s, so every other series
in the response would be lost with the two gauges, during the outage they are
needed for.

A failed count is logged at DEBUG, and the gauge keeps its last good value. A
gauge reports `NaN` until its first successful count, and again once that
count is more than 45 seconds old, three refresh intervals. `NaN` says the
value is unknown, where a stale last value would look like a healthy flat
line. So in a database outage the two gauges turn `NaN` within a minute, and
the rest of the response still arrives on time.
`src/test/java/com/smit/flightops/observability/OutboxMetricsTest.java#aHungDatabaseDoesNotHoldUpTheScrape`
scrapes while the refresher is stuck inside a count.

Useful queries:

```promql
# events are draining, not accumulating
outbox_pending

# anything at all here is an incident
outbox_dead > 0

# contention: lock timeouts per successful booking. The counter also
# counts cancellations and flight writes that timed out
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

The last field is the `X-Request-Id`, which is on every response the
application handles, including 401 and 403. Tomcat's own 400 page and a
`TRACE` refusal carry none. A user reporting "it said 403" can hand over one
string that finds the request.

`RequestIdFilter` runs at `HIGHEST_PRECEDENCE`, ahead of Spring Security. A 401
comes from the security filter chain (`BasicAuthenticationFilter` or
`ExceptionTranslationFilter`) before `DispatcherServlet` runs. A
filter ordered after the security chain would leave the calls people report,
such as "my credentials stopped working", with no id.
`SecurityRulesTest#everyResponseCarriesARequestId` asserts the header on a 401,
on a 403 and on an echoed inbound value.

A caller can bring its own id:

```bash
curl -si -u api:dev-secret -H 'X-Request-Id: ticket-4471' \
  -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Test Passenger","seats":2,"idempotencyKey":"k1"}' \
  | grep -i x-request-id
```

The answer carries the caller's id back:

```text
X-Request-Id: ticket-4471
```

An inbound id reaches a log line and a response header. A `\r\n` in it could
forge a second line or a second header. A megabyte of text would make every
line for that request a megabyte long. The filter keeps a value that matches
`^[A-Za-z0-9._:-]{1,128}$` and replaces anything else with a generated UUID. A
malformed diagnostic header is no reason to fail a booking. The id stays out of
the error body, because `ErrorResponse` is part of the API contract and the
header already carries it.

Following one booking across the queue is the interesting case. The outbox
drain runs on a scheduler, so it has a *different* trace from the request that
created the row. The link is the `traceparent` the writer captured:

```
[flight-ops-service,10cd4f19…,da65ef23…,599214ae-…] BookingWriter : Booked 2 seat(s) on UA123 (booking 1, 178 seats left)
[flight-ops-service,d21efe52…,010b24c1…,]            LoggingEventPublisher : [EVENT] BookingCreated {traceparent=00-10cd4f19…-da65ef23…-02} -> {"bookingId":"1",…}
```

The publisher's own trace is `d21efe52…`. The `traceparent` it carries is
`10cd4f19…`, the booking request's. That value goes onto the SQS message as an
attribute and the Lambda logs it, so one trace id links the booking request to
the Lambda that handled its event, and two searches find it:

```bash
kubectl logs -n flight-ops -l app=flight-ops --tail=10000 | grep 10cd4f19102abf7a3f922f252b6d4a97

aws logs filter-log-events \
  --log-group-name /aws/lambda/booking-event-handler \
  --filter-pattern '"10cd4f19102abf7a3f922f252b6d4a97"'
```

On the `prod` profile the same lines are ECS JSON, one object per line, with
`traceId`, `spanId` and `requestId` as fields. `application.yml` sets
`logging.structured.format.console: ecs` there, so in a log store `requestId`
is a term query in place of a substring search. Both forms carry the same data.
The other profiles keep the readable format for a laptop, and
`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs ./mvnw spring-boot:run` turns ECS on
anywhere. The booking line in ECS, wrapped here and one line in the real
output:

```json
{"@timestamp":"2026-09-22T23:48:23.969318Z","log":{"level":"INFO","logger":"com.smit.flightops.service.BookingWriter"},
 "process":{"pid":59730,"thread":{"name":"tomcat-handler-1"}},"service":{"name":"flight-ops-service","version":"1.1.0","node":{}},
 "message":"Booked 2 seat(s) on UA123 (booking 1, 178 seats left)",
 "traceId":"d5c7f7f85e5ca15a94bb489678506d22","spanId":"e9cfbc31f3226cac","requestId":"ecs-check-1","ecs":{"version":"8.11"}}
```

The `sqs` transport's line for a send is
`Published BookingCreated to SQS (messageId=…)`, and it does not print the
headers. So the drain puts each event's stored `traceparent` in the MDC while
it sends that event, and removes it afterwards
(`src/main/java/com/smit/flightops/service/OutboxPublisher.java#drainOutbox`).
In ECS that line, and the drain's retry and exhaustion warnings for the same
event, carry a `traceparent` field that contains the booking request's trace
id, so the `kubectl logs | grep` above finds them too. The readable format
prints only the four bracketed fields, so on the other profiles only the `log`
transport's line shows the traceparent.

### What a failure logs

A 500 logs its stack trace at ERROR, with the request id. An exception inside
Spring MVC reaches `GlobalExceptionHandler`, which logs `Unhandled exception`
and answers `INTERNAL_ERROR` with `An unexpected error occurred`. An exception
thrown outside MVC, in a filter for example, is forwarded to `/error`.
`ApiErrorController` logs `Unhandled failure on <METHOD> <URI>` there.
`RequestIdFilter` has already cleared the MDC by then, so the controller reads
the id back from the response header
(`ApiErrorControllerTest#aFailureThatEscapedTheChainIsLoggedWithTheRequestId`).
That 500 body tells the caller to quote the id:
`The request failed. The X-Request-Id header identifies it in the logs.` A 4xx
forwarded to `/error` is a client mistake, and `ApiErrorController` does not
log it.

Every answer of 400 or above that the application handles, a 401 or a 413
included, logs one INFO line from `RequestIdFilter`, with the method, the path
and the status, and the request id in the MDC:

```
INFO … [flight-ops-service,,,support-ticket-4471] c.s.f.observability.RequestIdFilter : GET /api/v1/flights/UA123 -> 401
```

The trace and span fields are empty, because the request's observation has
closed by the time the filter writes the line. The request id is the one to
search by. The path goes through the 403 line's rule, which turns anything
outside visible ASCII into `?`. The query string, the headers and the
principal are never logged. Paths under `/actuator/` are skipped, so a failing
readiness probe does not log a line every period. The line is INFO because a
4xx is the caller's mistake. If 401 or 404 scanning makes it noisy,
`logging.level.com.smit.flightops.observability.RequestIdFilter=WARN` turns it
off. `SecurityRulesTest#everyResponseCarriesARequestId` finds the line for a
401 by the caller's id, through the real filter chain.

A failure that escapes the filter chain gets no such line, although the status
reads 500 by the time `RequestIdFilter` finishes. Spring's
`ServerHttpObservationFilter`, which Boot registers one step inside
`RequestIdFilter`, sets 500 on the response before it rethrows. So
`RequestIdFilter` notes that the chain threw and skips its line, and the ERROR
line `ApiErrorController` writes at `/error` is the one line the failure leaves
under the request id.
`EscapedFailureLogTest#anEscapedFailureIsLoggedOnceByApiErrorController` checks
this in a running server, and checks that the observation filter is in the
chain.

A 403 also logs a WARN that names the method and the path, never the
principal or a header:

```
WARN … [flight-ops-service,affe185c…,a72584ea…,put-1] c.s.f.security.JsonAccessDeniedHandler : Denied PUT /api/v1/flights/UA123 for an authenticated caller: no rule grants this method and path to its authorities
```

## What to alert on

In the order they matter:

| | Condition | Why |
|---|---|---|
| 1 | `outbox_dead > 0` | an event will never be published, and nothing downstream will report it missing |
| 2 | `outbox_pending` rising for 10 min | the drain is losing to the write rate, or SQS is rejecting |
| 3 | `rate(bookings_lock_timeout_total[5m]) > 0` | users are getting 503s on flight row contention |
| 4 | SQS `ApproximateNumberOfMessagesVisible` on the **DLQ** `> 0` | a message was received three times without success, usually three Lambda failures on it. Declared as `lambda/template.yaml#BookingEventDLQAlarm`, never deployed, with no notification target |
| 5 | SQS `ApproximateAgeOfOldestMessage` on the main queue above 600 s for 5 minutes | the Lambda is behind at its concurrency cap of five, throttled by a dry account pool, or not polling; retries alone take about 540 s. A message still on the main queue 10 days after it was sent is deleted without reaching the DLQ. By then the default `OUTBOX_RETENTION` of `7d` has pruned its outbox row, so it cannot be re-sent from the outbox. The booking row in PostgreSQL is untouched; only the DynamoDB projection lacks the item. Declared as `lambda/template.yaml#BookingEventBacklogAlarm`, never deployed, with no notification target |
| 6 | `db` is `DOWN` in `/actuator/health` (the `ops` view) on any pod for 5 min, or `hikaricp_connections_pending` stays above 0 for 5 min | the database is unreachable, so a request that needs a connection gets `503 DATABASE_UNAVAILABLE` once the 5 s pool wait runs out (one already running a statement is cut off after 30 s by the driver's `socketTimeout` and gets the same 503), or the pool is full, so callers queue for a connection and any that wait the full 5 s get the same 503. Readiness leaves the database out, so the pods stay Ready and no probe shows it. If alert 3 fires too, look for a hot flight first ([the playbook](#503s-with-retry-after-a-lock-timeout-storm)) |
| 7 | RDS `DatabaseConnections` above 50 | more than the service's own pools can open: 4 pods × 10, or 5 × 10 during a rollout surge. Something else is connecting, or `maxReplicas` went up without a bigger instance class (fewer than 112 connections; read the limit with `SHOW max_connections`). See [DEPLOYMENT.md §7](DEPLOYMENT.md#7-what-breaks-first) |

## Playbooks

None of these playbooks has been followed on a cluster, because nothing here
has run in AWS. The log excerpts come from local runs, and
`OutboxPoisonRowTest` runs the re-drive `UPDATE` verbatim.

The `kubectl` commands need the cluster's kubeconfig. `up.sh` keeps it in
`deploy/aws/.state/kubeconfig`, not `~/.kube/config`, so first run the
`export KUBECONFIG=...` line that its closing summary prints.

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
first. An unset value does not name itself. Spring hands the unresolved literal
`${DB_PASSWORD}` to the driver, and Flyway's first connection fails as if the
password were wrong. The application has no default to fall back on, and
`application.yml` explains why beside the property.

If the log names `app.security.api-password` or `app.security.ops-password`
instead, see the next playbook.

Either way, check the Secret. All three of `DB_PASSWORD`, `API_PASSWORD` and
`OPS_PASSWORD` must be present. `describe` lists each key and its size, and
never a value:

```bash
kubectl describe secret flight-ops-secret -n flight-ops
```

### Pods crash-loop at startup, and the log names `app.security.api-password`

The same applies to `app.security.ops-password`. Each startup check stops the
pod before it becomes Ready. The crashing pod's
`--previous` log (see the playbook above) shows which one fired.

`API_PASSWORD` (or `OPS_PASSWORD`) is unset or has no `{id}` prefix.
`ApiSecurityProperties` rejects it when the properties are bound, and the log
has Spring Boot's failure report:

```
APPLICATION FAILED TO START
…
Failed to bind properties under 'app.security' to com.smit.flightops.config.ApiSecurityProperties:

    Reason: java.lang.IllegalArgumentException: app.security.api-password (API_PASSWORD) must be an encoded password with a {id} algorithm prefix, for example {bcrypt}$2a$10$...; the value is not shown
```

When the variable is unset, the message ends with `API_PASSWORD is not set`.
Prefix the hash:

```
{bcrypt}$2y$10$…
```

The value has a prefix the encoder cannot use. `SecurityConfig` asks the
`DelegatingPasswordEncoder` to verify each password once at startup. An id it
does not know, such as `{BCRYPT}` or `{bcyrpt}`, fails there. The last two
`Caused by` lines of the stack trace are these:

```
Caused by: java.lang.IllegalStateException: app.security.api-password cannot be verified by the configured DelegatingPasswordEncoder: java.lang.IllegalArgumentException: There is no password encoder mapped for the id 'BCRYPT'. Check your configuration to ensure it matches one of the registered encoders.
…
Caused by: java.lang.IllegalArgumentException: There is no password encoder mapped for the id 'BCRYPT'. Check your configuration to ensure it matches one of the registered encoders.
```

The first of the two names the property, so read it to tell `api` from `ops`.
The last is the encoder's own exception, and it names only the id.

The id is case-sensitive, so write `{bcrypt}`. An `{argon2}` or `{scrypt}` hash
fails the same self-check with a `NoClassDefFoundError`. Both encoders need
BouncyCastle, and the build does not include it. Use `{bcrypt}` or `{pbkdf2}`.

The profile is `prod` and the id names no adaptive hash. Under `prod`,
`config/SecurityConfig.java#refuseUnhashed` accepts only `bcrypt`, `pbkdf2`,
`scrypt` and `argon2`, so `{noop}`, `{ldap}`, `{MD5}`, `{SHA-256}` and the
other deprecated ids stop startup. The last `Caused by` line names
`app.security.api-password (API_PASSWORD)` and says it
`must be a hashed password under the prod profile`. Put a `{bcrypt}` hash in
the Secret; every profile but `prod` takes `{noop}`.

The prefix check and the `prod` refusal name the property and never the
value. The encoder's own exception can quote part of it: for an unknown id,
both `Caused by` lines give the text between the value's first `{` and the
next `}`, and never what follows. A plaintext password that starts with a
braced group could leave part of itself in the pod log.

The prefix check and the self-check exist because Spring's delegating encoder
throws on a value it cannot read, where a wrong password would simply fail to
match. Without them the pod would go Ready and fail on the first login.
I checked this: an unprefixed bcrypt string raises `IllegalArgumentException`
on the first login.

### Every login gets 401, and the log warns `Encoded password does not look like BCrypt`

The value has the `{bcrypt}` prefix, but what follows is not a bcrypt hash.
The usual cause is `{bcrypt}REPLACE_ME`, copied from
`deploy/k8s/secret.example.yaml` without the real hash. Every startup check
passes it. `BCryptPasswordEncoder` logs a WARN and returns false in place of
throwing, so the pod goes Ready and every login as that user gets a 401.

The WARN comes from `o.s.s.c.bcrypt.BCryptPasswordEncoder`. The self-check
logs it once at startup for each such value, and every login logs it again. A
WARN at startup therefore means a stored value is not a bcrypt hash, before
anyone has tried to log in. Set a real hash, as in
[Rotating the API or ops password](#rotating-the-api-or-ops-password).
[SECURITY.md](../SECURITY.md#known-limitations) lists this as a known limit.

### Events stop arriving; `outbox_pending` climbs

```bash
kubectl logs -n flight-ops -l app=flight-ops --tail=-1 --prefix \
  | grep -i outbox | tail -20
```

Keep `--tail=-1`. With a label selector, `kubectl logs` reads only the last
10 lines of each pod unless `--tail` is given, and `--since` alone does not
lift that. Bookings and successful sends log too, so those 10 lines are
whatever came last. A row's `exhausted` line, the one that says an event will
not be retried unless someone re-drives it, is logged once and soon falls out
of them. kubectl prints one pod's log after the other, so the last 20 matches
can all come from one pod, and `--prefix` names the pod on each line.

On `prod` each line is one ECS JSON object, and the grep matches the logger
name, `com.smit.flightops.service.OutboxPublisher`, as well as the message.
A failed send logs `failed to publish on attempt`, or, when it was the row's
last attempt, `exhausted 10 attempts and will not be retried` (10 being the
default `OUTBOX_MAX_ATTEMPTS`). A drain with any failure then logs
`Outbox drain published N of M claimed events`. A successful send logs
`Published BookingCreated to SQS` from `SqsEventPublisher`, which the grep
leaves out. The grep also finds `OutboxPruner`'s hourly
`Pruned N outbox event(s)` line, which only says that old published rows were
deleted. When the publisher is created at startup it logs
`Outbox publisher started with event transport 'sqs'`, which answers step 3
below while the pod's log still reaches back that far.

Then check, in order:

1. Is `SQS_QUEUE_URL` set and correct?
2. Does the pod have IRSA? `kubectl describe pod` should show
   `AWS_WEB_IDENTITY_TOKEN_FILE`.
3. Is `OUTBOX_ENABLED` still true? `false`, `off`, `no` or `0` stops the drain.

Read `outbox_pending` together with `outbox_publish_total{result="failure"}`.
The gauge counts every unpublished row still inside the attempt ceiling,
including rows the retry backoff is holding back. So these two situations
produce the same climbing gauge:

| | `outbox_pending` | `outbox_publish_total{result="failure"}` | What it is |
|---|---|---|---|
| Rows arrive faster than the drain | climbing | flat | throughput. Each replica sends one row at a time, which caps it at `1 / send latency`. Below the cap, shorten `OUTBOX_POLL_INTERVAL`. At the cap, add replicas. See below |
| The transport is refusing | climbing | climbing | an outage or a misconfiguration. The rows are waiting out their backoff. With the defaults they reach `outbox_dead` about 13.5 minutes after the first failure |

When it is throughput, send latency sets the limit.
`OutboxPublisher#drainOutbox` sends the rows it claimed one at a time, each a
blocking `SendMessage`, and the next drain starts `OUTBOX_POLL_INTERVAL` after
the last one ends. One replica therefore drains about

```
batch / (poll interval + batch × send latency)
```

events a second, with both times in seconds, and never more than
`1 / send latency`, whatever the settings. Lowering `OUTBOX_POLL_INTERVAL` or
raising `OUTBOX_BATCH_SIZE` brings a replica closer to that cap, and does
little once `batch × send latency` is well above the interval. Prefer the
interval. A larger batch holds its row locks, its transaction and a pooled
connection for `batch × send latency` on every drain, while a shorter
interval only runs the claim query more often.
[DEPLOYMENT.md §7](DEPLOYMENT.md#7-what-breaks-first) works the numbers
through.

At the cap, add replicas. `SKIP LOCKED` gives each one a disjoint batch, so
each adds up to another `1 / send latency`. The HPA watches only CPU, and a
drain waiting on SQS uses little, so a backlog alone does not make it add
any. Raise its floor, up to the `maxReplicas: 4` that the database's
connection limit sets:

```bash
kubectl patch hpa flight-ops-hpa -n flight-ops -p '{"spec":{"minReplicas":4}}'
```

The next apply of the overlay puts back the `minReplicas: 2` in
`deploy/k8s/base/hpa.yaml`, so change it there too if the load will last.

To measure rather than assume, take one pod's success rate while the backlog
lasts: `rate(outbox_publish_total{result="success"}[5m])`. While nothing
scrapes the pods ([What is not wired up](#what-is-not-wired-up)), read the
counter from the pod's `/actuator/prometheus` twice, a minute apart, and
divide the difference by 60. Every drain claims a full batch while the
backlog lasts, so that rate is what the pod can drain at its settings, and
`(batch / rate - poll interval) / batch` is roughly its send latency, the
claim and the commit included.

To see what a row is waiting for, ask the table. A row whose `next_attempt_at`
is in the future and whose `attempts` is below `OUTBOX_MAX_ATTEMPTS` is
deferred, and the poller will try it again. A row at or above the ceiling is
dead whatever its `next_attempt_at` says, and waits for
[the re-drive below](#outbox_dead--0-a-poison-row). The query lists every
unsent row, dead ones included:

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
```

```sql
UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?
```

Put the row's id in place of `?`.
`OutboxPoisonRowTest.java#resettingAttemptsRedrivesTheRow` runs this statement
verbatim, so the one an operator pastes is a tested one.

The next poll picks it up. Resetting without fixing the cause burns ten more
attempts.

### The DLQ has messages

```bash
aws sqs receive-message --queue-url <dlq-url> --max-number-of-messages 10
```

A message reaches the DLQ after three receives that did not succeed
(`maxReceiveCount: 3` in `lambda/template.yaml`). Usually the handler failed
on it three times. `ScalingConfig.MaximumConcurrency: 5` caps the poller at five
invocations, and messages over the cap wait in the queue with no receive
counted. The cap reserves nothing from the account's concurrency pool. It limits
only the poller, so an account pool that runs dry can still throttle a message
into the DLQ. The value must be 2 to 1000.

Read the body. It does not say why the message failed, and if DynamoDB refused
the write, the body is a valid event. The handler's `FAILED <messageId>` line
in the log group `/aws/lambda/booking-event-handler` says why. No such line
means the handler never reported the message: the function was throttled,
timed out, failed to start or crashed. SQS keeps the message id when it moves
a message to the DLQ, and the log group keeps 14 days, like the DLQ, so the
line outlasts the message. Fix the handler or the data. Then redrive with the
SQS console's "Start DLQ redrive", or re-send the messages to the main queue.
Delete a message that is permanently malformed, and leave a note saying why.
Left alone, it expires 14 days after it was first sent, because SQS keeps a
message's original enqueue time when it moves it to the DLQ. Nothing notes
the expiry.

### 503s with `Retry-After`: a lock timeout storm

Concurrent bookings for the *same flight* queue behind `SELECT … FOR UPDATE`.
That queueing is the oversell guarantee, and it is working as designed.
Cancellations, flight status changes and flight cancellations need the same
row lock, so they queue too, and their timeouts count in
`bookings_lock_timeout_total`. Past `lock_timeout = 3s` the request gets a 503
with `Retry-After`. That is correct
under contention, and a problem if it is sustained. Check whether one flight
is hot (no meter carries a flight tag, so count recent rows per `flight_id` in
`bookings`) and whether a transaction is stuck:

```sql
SELECT pid, state, wait_event_type, query_start, left(query, 80)
  FROM pg_stat_activity
 WHERE state <> 'idle' ORDER BY query_start;
```

### Flyway refuses to start

`FlywayValidateException` on a checksum means a migration file changed after
it was applied. Never edit an applied migration; add a new one. On a demo
database, drop and recreate it. On any other, first establish which version is
correct, then run `flyway repair` with the Flyway command-line tool, which this
repository does not include. Point it at `src/main/resources/db/migration` and
run it from somewhere that can reach the database.

`Detected failed migration to version 11` means the `CREATE INDEX
CONCURRENTLY` in `V11__flights_departure_time_index.sql` stopped part way,
for example on the 3s `lock_timeout` while it waited for older transactions.
It runs outside a transaction, so nothing was rolled back. Run
`DROP INDEX CONCURRENTLY IF EXISTS idx_flights_departure_time;`, then
`flyway repair`, then start the service again. Without the drop, the
migration's `IF NOT EXISTS` would pass over the INVALID index it left.

### RDS unreachable

The database security group admits the cluster SG and the shared node SG on
5432. `deploy/aws/up.sh` reads both from the live eksctl stack outputs and
passes them to `deploy/aws/data.yaml` only when it creates the data stack. If
the cluster was recreated, those ids changed, and the data stack still points
at the old ones.

### `up.sh` stops, or CI stops before the deploy

The table in
[deploy/aws/README.md](../deploy/aws/README.md#when-something-goes-wrong) maps
each stop to its cause. For `up.sh` that is step 1 on the JDK, eksctl or the
controller policy's checksum, step 8 if that file changed during the run, and
step 4 while it finishes a cluster that already exists. It is also step 5 on
`AmazonEKSEditPolicy`, step 6 on the data stack's status, and step 9 when the
database password is not available. It also covers the 30-minute wait at step 10
for CI to create the Deployment, and CI stopping at "Is this commit already in
ECR?". Once the Deployment exists, `up.sh` waits up to 20 more minutes for it to
become available.

`deploy/aws/selftest.sh` runs `down.sh`, `cost-check.sh`, `ecr-image-exists.sh`
and `up.sh`'s checks in `lib.sh` against stubbed `aws`, `kubectl`, `helm`,
`eksctl`, `sleep` and `mvnw` commands. It uses no credentials, takes a few
seconds, and runs in CI's `infra-lint` job.

### Rolling back a deploy

The deploy job never rolls back by itself. A rollout that stops at its first
new pod leaves the old pods serving, because the rollout uses
`maxUnavailable: 0`; one that stops later leaves some new pods serving, and a
smoke test that fails after a completed rollout leaves every pod on the new
release. Read the job's "Diagnose a failed deploy" step, then roll back by
hand:

```bash
kubectl rollout history deployment/flight-ops -n flight-ops
kubectl rollout undo deployment/flight-ops -n flight-ops
```

`rollout undo` goes to the previous revision. After more than one failed
deploy, that revision failed too. The history lists only revision numbers,
because nothing sets a change cause, so read each revision's image, whose tag
is its commit's SHA, and ask ECR whether that SHA also has a `deployed-<sha>`
tag:

```bash
kubectl rollout history deployment/flight-ops -n flight-ops --revision=<n>
aws ecr describe-images --repository-name flight-ops-service \
  --image-ids imageTag=deployed-<sha>
```

`describe-images` fails with `ImageNotFoundException` when the tag is not
there. Pass the newest revision whose image has it as `--to-revision=<n>`. The
job adds that tag in its step "Tag the image as deployed", which runs only
after the smoke test passes, and the lifecycle policy keeps the last ten
images tagged that way. A deploy whose rollout completed and whose smoke test
then failed is serving on every pod, and its image has no such tag: roll it
back before five more images are pushed, or the lifecycle policy can expire
the image the pods run. `kubectl rollout undo` does not undo migrations, so
every migration must keep the previous release working (expand now, contract
in a later release).

Re-running an old workflow run is not a rollback when its commit has the
deploy job's step "Is this commit still the head of main?": its deploy job
applies nothing unless its commit is still the head of `main`, and says so in
the job summary. Runs of commits from before that step have no such check, and
GitHub lets a run be re-run for 30 days after it started. So until 28 October
2026, once `DEPLOY_ENABLED` is set, a re-run of one of those runs could apply
its commit over the release. Do not re-run them.

A re-run queued while another run on `main` is in progress takes the place of
any run already waiting in the same concurrency group, and GitHub cancels the
waiting one. After a "Nothing deployed" notice, check that the head of `main`
has a run that deployed it, and start a manual run on `main` if that run was
cancelled.

### Rotating the API or ops password

```bash
NEW=$(openssl rand -base64 18 | tr -d '/+= ')
HASH="{bcrypt}$(printf '%s' "$NEW" | htpasswd -niBC 10 "" | tr -d ':\n')"
printf '{"stringData":{"API_PASSWORD":"%s"}}' "$HASH" \
  | kubectl patch secret flight-ops-secret -n flight-ops \
      --type merge --patch-file /dev/stdin
kubectl rollout restart deployment/flight-ops -n flight-ops
printf 'new password: %s\n' "$NEW"
```

For the ops password, patch `OPS_PASSWORD` instead. The last line prints the
new password. Hand it to the callers before closing the shell: the Secret holds
only its bcrypt hash, so `$NEW` is the only copy. The password reaches htpasswd
and the hash reaches kubectl on stdin, as `up.sh` hands them over, so neither
is in the process list.

The restart is required. The password is injected as an environment variable,
and environment variables are read once at container start. A mounted volume
would update in place, but it would still need a restart, because Spring binds
the property at startup either way.

## What is not wired up

- **Nothing scrapes `/actuator/prometheus`.** Nothing is deployed, and the
  cluster that `deploy/aws/up.sh` would build has no Prometheus, no Grafana
  and no Alertmanager. The metrics are correct and exported, and the PromQL
  above is what you would write once something scrapes them. Nobody has sized
  kube-prometheus-stack for this cluster. If it needs a third t3.medium, that
  node costs about $1/day at the rate in
  [DEPLOYMENT.md §5](DEPLOYMENT.md#5-what-it-costs).

- **Nothing ships pod logs anywhere.** `kubectl logs` is the interface. The
  cross-process trace hunt is `kubectl logs | grep <traceId>` on this side and
  CloudWatch Logs Insights on the Lambda's log group on the other.

- **The service exports no traces.** Micrometer Tracing generates the ids and
  puts them in the logs. They come from OpenTelemetry through
  `spring-boot-starter-opentelemetry`. OTLP export activates only when
  `management.opentelemetry.tracing.export.otlp.endpoint` is set, and it is
  not. The log line is the trace. The Lambda has X-Ray active tracing, so
  X-Ray records a sample of its invocations. Those traces start at the Lambda
  and do not carry the service's trace id.

- **No metrics are pushed.** The same starter's metrics half is opt-out. It
  brings `micrometer-registry-otlp`, a push registry that targets
  `http://localhost:4318/v1/metrics` every 60 seconds, collector or not. This
  service exposes its metrics for scraping, so `application.yml` sets
  `management.otlp.metrics.export.enabled: ${OTLP_METRICS_ENABLED:false}`.

- **No alerting.** Nothing pages anyone. Rows 4 and 5 of the alert table have
  CloudWatch alarms declared in `lambda/template.yaml`
  (`lambda/template.yaml#BookingEventDLQAlarm`,
  `lambda/template.yaml#BookingEventBacklogAlarm`), linted in CI and never
  deployed, with no notification target: an alarm would change state in the
  CloudWatch console and tell no one. Rows 1 to 3 and the pool half of row 6 are
  Prometheus conditions, and nothing scrapes `/actuator/prometheus`. The `db`
  half of row 6 would need an authenticated poll of `/actuator/health`, and
  row 7 an RDS alarm, and neither exists.

That is fine for a two-week demo on a $7.72/day cluster, and not for
production.
