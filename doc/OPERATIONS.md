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
| `DB_URL` | `jdbc:postgresql://localhost:5432/flightops` in `postgres`. **None in `prod`** | JDBC URL. Unset in `prod`, startup fails with `'url' must start with "jdbc"`. The default profile uses H2 and does not read it |
| `DB_USER` | `postgres` in `postgres`. None in `prod` | database user |
| `DB_PASSWORD` | *(none)* | **Required** in `postgres` and `prod`. Unset, startup fails at Flyway's first connection with `password authentication failed`, which does not name the variable. See the `postgres` profile in `application.yml` |
| `API_PASSWORD` | `{noop}dev-secret`. None in `prod` | the `api` account. **Must carry an `{id}` prefix** the encoder knows, such as `{bcrypt}$2y$10$…`. Unprefixed, or unset in `prod`, startup fails naming `app.security.api-password (API_PASSWORD)`. An unknown id stops startup too. See [the playbook](#pods-crash-loop-at-startup-and-the-log-names-appsecurityapi-password) |
| `OPS_PASSWORD` | `{noop}dev-ops`. None in `prod` | the `ops` account. Same prefix rule, named `app.security.ops-password (OPS_PASSWORD)` |
| `APP_EVENTS_PUBLISHER` | `log`; `sqs` in `prod` | `sqs` or `log`. Any other value stops startup with `app.events.publisher must be one of [log, sqs], not "<value>"`. Read in every profile: the base document is `${APP_EVENTS_PUBLISHER:log}` and `prod` is `${APP_EVENTS_PUBLISHER:sqs}`. `log` writes and drains the outbox without sending anything. Choosing `sqs` without `SQS_QUEUE_URL` stops startup with `app.events.publisher=sqs requires app.aws.sqs-queue-url (env SQS_QUEUE_URL)`. A laptop publishes only when someone sets both. The startup log names the choice: `Outbox publisher started with event transport 'log'` |
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

The default profile is a laptop profile, and it fails open: the application
starts, serves every endpoint, passes the demo and stores nothing.
`./mvnw spring-boot:run` and a bare `java -jar` get it. The image does not: the
Dockerfile sets `SPRING_PROFILES_ACTIVE=prod`, so a container started with no
profile fails closed. With no `DB_URL`, a bare `docker run` stops with
`'url' must start with "jdbc"`. CI checks that on every push or pull request to
`main`, in the `image` job's step "The image will not start without
a database". The deploy job runs the same step before it pushes an image. That
job is gated off, so its copy has never run. `compose.yaml` selects `postgres`,
and `deploy/k8s/base/configmap.yaml` sets `prod` for the cluster. The Deployment
pulls the whole ConfigMap in with `envFrom`.

## Health

| Endpoint | Auth | Answers |
|---|---|---|
| `/actuator/health` | none, or the `api` credential | `UP`/`DOWN` and the probe group names, nothing else |
| `/actuator/health/liveness` | none | should the kubelet restart this container |
| `/actuator/health/readiness` | none | should traffic come here |
| `/actuator/health` | `ops`, in every profile | the same, plus components: `db`, `diskSpace`, `ssl`, … |

The probes are open to anonymous callers because the kubelet has no
credentials and cannot be given any. An anonymous caller gets a status only,
so it cannot find out which database this is.

Component detail follows the role. `application.yml` sets
`show-details: when-authorized` with `roles: OPS`, so only the `ops` user sees
the components, `prod` included. The `api` user gets the same status and group
names as an anonymous caller. That keeps the absolute path `diskSpace` reports
away from the credential every client holds.

The two probes ask different questions, and mixing them up is a classic
outage. A readiness failure takes one pod out of the load balancer, and a
liveness failure restarts it. Liveness checks only `livenessState`, and
readiness checks `readinessState` and `db`. Point liveness at the database and
a database blip restarts every replica at once.

## Metrics

`/actuator/prometheus` needs `ops` credentials. On top of everything
Micrometer provides, the service adds five counters and two gauges, from
`observability/BookingMetrics.java` and `observability/OutboxMetrics.java`.

| Metric | Type | Labels | Reading |
|---|---|---|---|
| `bookings_booked_total` | counter | `outcome=created\|replayed` | a replay and a new booking are both a 201 on the same URI, and only this label separates selling seats from a client stuck in a retry loop. A high `replayed` share means clients are retrying. That is fine, and useful to know |
| `bookings_cancelled_total` | counter | `outcome=cancelled\|already_cancelled` | `already_cancelled` is a repeated `DELETE`: it returns 200 and releases nothing. It is not an error. If it climbs while `cancelled` stays flat, a client thinks its cancellations are not sticking |
| `bookings_lock_timeout_total` | counter | | a write gave up after 3s waiting for the flight row lock: a booking or a cancellation, or a flight status change or flight cancellation queued behind one. **Non-zero means users are seeing 503s.** It is the earliest sign of the whole write path stalling |
| `outbox_pending` | gauge | | rows waiting to publish and still within the attempt ceiling, including rows the retry backoff is holding back. A rising line is publisher lag, and it shows an SQS outage before any consumer notices missing events. Each failing row stays here for as long as its ten attempts take (about 13.5 minutes with the defaults), and only then moves to `outbox_dead` |
| `outbox_dead` | gauge | | rows that exhausted `OUTBOX_MAX_ATTEMPTS`. **Should always be 0.** A dead row does not come back by itself: the claim never returns it, and its event is never sent until someone re-drives it ([the playbook](#outbox_dead--0-a-poison-row)) |
| `outbox_publish_total` | counter | `result=success\|failure\|exhausted` | the transport's health. A send that uses up a row's last attempt counts under `failure` as well as `exhausted`, so `failure` is the full error rate. That rate shows a partial outage that `outbox_pending` hides while the backlog still drains faster than it grows |
| `outbox_pruned_total` | counter | | published rows deleted by retention. Flat at zero while published rows older than `OUTBOX_RETENTION` pile up means the pruner has stopped, and no other series shows it |

The exporter prints one `# HELP` line per meter name, so both series of a
meter share one description. `BookingMetrics` holds each shared description in
a constant.

HTTP metrics cannot express any of these. `http_server_requests` counts a 201
for a new booking and a 201 for an idempotent replay the same way, because
both are the same status on the same route. The difference between them is
the behaviour I most want to watch.

Both gauges query the database on the scrape thread. If the query fails,
`observability/OutboxMetrics.java#count` logs the error at DEBUG and the gauge
reports `NaN` instead of throwing. `NaN` says the value is unknown, where a
stale last value would look like a healthy flat line. The rest of the response
is unaffected, and that would hold without the catch: in Micrometer 1.17.1,
the version Boot 4.1.1 manages, the Prometheus registry catches a gauge that
throws, reports `NaN` for it and logs a WARN with the stack trace the first
time. I checked this against those jars, with a throwing gauge scraped beside
a counter. So the catch swaps that one WARN and stack trace for a DEBUG line.
It is not what keeps the other series in the response.

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
# X-Request-Id: ticket-4471
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
attribute and the Lambda logs it, so one trace id links three processes, and
two searches find it:

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
forwarded to `/error` is a client mistake and is not logged.

At the default level a 401 logs nothing. A 403 logs a WARN that names the
method and the path, never the principal or a header:

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
| 5 | SQS `ApproximateAgeOfOldestMessage` on the main queue above 600 s for 5 minutes | the Lambda is behind at its concurrency cap of five, throttled by a dry account pool, or not polling; retries alone take about 540 s. Declared as `lambda/template.yaml#BookingEventBacklogAlarm`, never deployed, with no notification target |
| 6 | readiness failing on any pod for 5 min | usually the database |
| 7 | RDS `DatabaseConnections` above 50 | more than the service's own pools can open: 4 pods × 10, or 5 × 10 during a rollout surge. Something else is connecting, or `maxReplicas` went up without a bigger instance class (fewer than 112 connections; read the limit with `SHOW max_connections`). See [DEPLOYMENT.md §7](DEPLOYMENT.md#7-what-breaks-first) |

## Playbooks

None of these playbooks has been followed on a cluster, because nothing here
has run in AWS. The log excerpts come from local runs, and
`OutboxPoisonRowTest` runs the re-drive `UPDATE` verbatim.

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
`OPS_PASSWORD` must be present:

```bash
kubectl get secret flight-ops-secret -n flight-ops -o jsonpath='{.data}' | tr ',' '\n'
```

### Pods crash-loop at startup, and the log names `app.security.api-password`

The same applies to `app.security.ops-password`. Either startup check stops
the pod before it becomes Ready. The crashing pod's
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
fails the same check with a `NoClassDefFoundError`. Both encoders need
BouncyCastle, and the build does not include it. Use `{bcrypt}` or `{pbkdf2}`.

Neither message prints the password or the hash, so a failed start leaves no
secret in the pod log.

Both checks exist because Spring's delegating encoder throws on a value it
cannot read, where a wrong password would simply fail to match. Without them
the pod would go Ready and fail on the first login. I checked this: an
unprefixed bcrypt string raises `IllegalArgumentException` on the first login.

### Every login gets 401, and the log warns `Encoded password does not look like BCrypt`

The value has the `{bcrypt}` prefix, but what follows is not a bcrypt hash.
The usual cause is `{bcrypt}REPLACE_ME`, copied from
`deploy/k8s/secret.example.yaml` without the real hash. Both startup checks pass
it. `BCryptPasswordEncoder` logs a WARN and returns false in place of throwing,
so the pod goes Ready and every login as that user gets a 401.

The WARN comes from `o.s.s.c.bcrypt.BCryptPasswordEncoder`. The self-check
logs it once at startup for each such value, and every login logs it again. A
WARN at startup therefore means a stored value is not a bcrypt hash, before
anyone has tried to log in. Set a real hash, as in
[Rotating the API or ops password](#rotating-the-api-or-ops-password).
[SECURITY.md](../SECURITY.md#known-limitations) lists this as a known limit.

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

Read the body and fix the handler or the data. Then redrive with the console's
"Start DLQ redrive", or re-send the messages to the main queue. Delete a
message that is permanently malformed, and leave a note saying why. Left
alone, it expires after 14 days with no record.

### 503s with `Retry-After`: a lock timeout storm

Concurrent bookings for the *same flight* queue behind `SELECT … FOR UPDATE`.
That queueing is the oversell guarantee, and it is working as designed.
Cancellations, flight status changes and flight cancellations need the same
row lock, so they queue too, and their timeouts count in
`bookings_lock_timeout_total`. Past `lock_timeout = 3s` the request gets a 503
with `Retry-After`. That is correct
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

- **Nothing scrapes `/actuator/prometheus`.** Nothing is deployed, and the
  cluster that `deploy/aws/up.sh` would build has no Prometheus, no Grafana
  and no Alertmanager. The metrics are correct and exported, and the PromQL
  above is what you would write once something scrapes them. Adding
  kube-prometheus-stack costs roughly $1/day of extra node capacity.

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
  console and tell no one. Rows 1 to 3 are Prometheus conditions, and nothing
  scrapes `/actuator/prometheus`. Row 6 would need a Kubernetes monitor and
  row 7 an RDS alarm, and neither exists.

That is fine for a two-week demo on a $7.72/day cluster, and not for
production.
