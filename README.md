# flight-ops-service

[![build & tests](https://img.shields.io/github/actions/workflow/status/smit-lakhani-13/flight-ops-services/build-and-deploy.yml?branch=main&label=build%20%26%20tests)](https://github.com/smit-lakhani-13/flight-ops-services/actions/workflows/build-and-deploy.yml)
[![codeql](https://img.shields.io/github/actions/workflow/status/smit-lakhani-13/flight-ops-services/codeql.yml?branch=main&label=codeql)](https://github.com/smit-lakhani-13/flight-ops-services/actions/workflows/codeql.yml)
[![licence: MIT](https://img.shields.io/badge/licence-MIT-blue)](LICENSE)

Flight inventory and booking microservice: a Spring Boot REST API over PostgreSQL that publishes booking events to SQS, where an AWS Lambda consumer projects them into DynamoDB.

**Java 21 · Spring Boot 4.1.1 · Spring Security 7 · Spring Data JPA · PostgreSQL / H2 · Flyway**

**AWS SQS + Lambda + DynamoDB · Docker · Kubernetes / EKS · SAM · GitHub Actions**

I picked an airline because seat inventory is a real consistency problem: many clients want the same few seats, clients retry, and a mistake sells one seat twice.

**Scope.** This is a demo that has never served production traffic. CI builds both modules and runs every test, including the PostgreSQL integration tests, on every push to `main` and every pull request. CI also builds the image from the `Dockerfile` on every push to `main` and every pull request, starts it with no database, and never pushes it. `k8s/`, `template.yaml`, `deploy/aws/` and the deploy job are written, linted and validated offline, but I have never applied them. Their prices and runbook are in [DEPLOYMENT.md](DEPLOYMENT.md), and [Project status](#project-status) shows what has actually run.

**Contents:** [Documentation](#documentation) · [Run it](#run-it-in-30-seconds) · [Status](#project-status) · [Architecture](#architecture) · [Layout](#repository-layout) · [Security](#security) · [API](#api) · [Metrics](#metrics) · [Tests](#tests) · [Deployment](#deployment-and-cost) · [Trade-offs](#trade-offs-and-known-limitations) · [Review](#what-i-found-in-review) · [Versions](#versions)

## Documentation

On every CI run, the `docs-check` job runs three scripts. `scripts/refcheck.py` resolves every file and symbol these documents cite. `scripts/linkcheck.py` checks every link and heading anchor. `scripts/sweeps.sh` reads the working tree and the commit history, and fails on a co-author trailer, a tool's signature, an absolute home-directory path in a tracked file, or a match for the patterns in the `SWEEP_PATTERNS` repository secret. I run `scripts/numbers.sh` by hand before I edit a count. It recomputes every count the documents claim, and it is not a gate.

| Document | What it answers |
|---|---|
| [NOTES.md](NOTES.md) | The bugs I found in this service, why each happened, and the test and commit that pin each fix |
| [ARCHITECTURE.md](ARCHITECTURE.md) | How the pieces fit: the booking sequence naming every method it passes through, the idempotency decision table, the lock order, the outbox, the status state machine, the ER diagram, and the module boundaries the build enforces |
| [adr/](adr/README.md) | Why each decision went the way it did, and what I rejected: 15 records covering the outbox, pessimistic locking, ids, the request fingerprint, the security model, Boot 4, the Lambda, the IaC choice, the region, observability, OpenAPI, the outbox bounds, the quality gates and the event transport |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Three ways to run it, the runbook for each, what each costs for 7, 10 and 15 days, what breaks first under load, and how to tear it all down with proof |
| [OPERATIONS.md](OPERATIONS.md) | Every environment variable, the metrics and what they mean, how to follow one booking across the queue, what to alert on, the playbooks, and what is not wired up |
| [SECURITY.md](SECURITY.md) | The auth model, what is exposed and what is not, how secrets are handled, and the known limitations |
| [CONTRIBUTING.md](CONTRIBUTING.md) | The JDK trap, both build commands, what `Skipped: 9` means, and what CI enforces |
| [CHANGELOG.md](CHANGELOG.md) | What changed in each release, the response field 1.1.0 removed, and why it is 1.1.0 and not 2.0.0 |
| [contracts/README.md](contracts/README.md) | The event contract between the two modules, how both sides enforce it, and how to change it without breaking a deployed consumer |
| [deploy/aws/README.md](deploy/aws/README.md) | What each script and template creates, who owns what between the scripts and CI, and the failure table |

## Run it in 30 seconds

You need a JDK 21 and nothing else: no database, no AWS account, no Docker and no local Maven install.

```bash
java -version          # must report 21
./mvnw spring-boot:run
```

It runs on in-memory H2 with a schema Hibernate creates, and seeds three demo flights: `UA123` EWR→LHR 180 seats, `UA456` ORD→SFO 150 and `UA789` EWR→SFO 200. It boots in under 4s (2.83s, 2.57s and 3.63s across three local runs), with the security filter chain and the outbox scheduler both starting.

Every call under `/api/` needs credentials. The default profile ships two throwaway accounts: `api` / `dev-secret` for the API and `ops` / `dev-ops` for the actuator endpoints other than health. They are stored as `{noop}dev-secret` and `{noop}dev-ops`, and `{noop}` marks a value as unhashed and so not secret. The `prod` profile has no defaults, and `ApiSecurityProperties` rejects any value without an `{id}` prefix, so a deployment that forgets `API_PASSWORD` fails at startup. `SecurityConfig` then asks the encoder to verify each value once, so an id it does not know, such as `{foo}`, also stops startup, and so does `{argon2}`, because the build has no BouncyCastle. Neither check prints the value. Without them, `@ConfigurationProperties` would bind the literal string `${API_PASSWORD}` as the password, and the pod would go Ready and then fail every authenticated request.

> **macOS: set JAVA_HOME yourself.** `/usr/libexec/java_home -v 21` only finds JDKs registered with macOS, and Homebrew's are not. With an Oracle JDK 17 also installed, it exits 0 and returns 17. The enforcer then stops the build at once with `This build needs JDK 21`, where the compiler would have failed later on `release version 21 not supported`. Use `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on Apple Silicon or `/usr/local/opt/openjdk@21` on Intel.

```bash
curl localhost:8080/actuator/health                       # open: the kubelet has no credentials
curl -u api:dev-secret localhost:8080/api/v1/flights/UA123
curl -u api:dev-secret "localhost:8080/api/v1/flights?origin=EWR&page=0&size=5"
curl -u ops:dev-ops localhost:8080/actuator/metrics       # ops, not api
```

You can also browse <http://localhost:8080/swagger-ui.html>. The document is public and every operation it lists needs credentials, for the reasons in [adr/0012](adr/0012-openapi-public-read.md).

Without `-u` you get `401 {"code":"UNAUTHENTICATED"}`. With `api` on an `ops` endpoint you get `403 {"code":"FORBIDDEN"}`. A 401 means the caller is unknown, and a 403 means it is known but not allowed.

### Or run the whole tour at once

With the app running, in a second terminal:

```bash
./demo.sh          # pauses between acts, so you can talk over it
./demo.sh --fast   # no pauses
```

Eight acts run over HTTP: the paged API and the normalised `Location` header, idempotent replay, and the error codes with the cancelled-flight refusal. Ten callers then race on one key. With one payload all ten get the same booking, and with ten payloads nine are told the key is taken. The last acts show `BOARDING → ARRIVED` refused, the 401/403 split and the actuator surface Kubernetes probes. The script checks the app and the credentials first, and uses `python3 -m json.tool` if `jq` is absent. Restart the app to reset its in-memory state.

### Retries and cancelled flights

A retry does not double-book:

```bash
curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Smit","seats":3,"idempotencyKey":"demo-1"}'
curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA123          # availableSeats: 177

for i in 1 2 3 4 5; do                                    # the client retried after a timeout
  curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
    -H 'Content-Type: application/json' \
    -d '{"flightNumber":"UA123","passengerName":"Smit","seats":3,"idempotencyKey":"demo-1"}' >/dev/null
done

curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA123          # STILL 177, not 162
curl -s -u api:dev-secret "localhost:8080/api/v1/bookings?flightNumber=UA123"   # ONE booking
```

I spelled out `-u` on every line, because the obvious tidy-up, `A='-u api:dev-secret'; curl $A ...`, fails on macOS. In zsh an unquoted parameter is not word-split, so curl receives `-u api:dev-secret` as one argument, ignores it, and every call returns 401. Use an array instead: `A=(-u api:dev-secret); curl "${A[@]}" ...`

Change the payload and keep the key, and the answer is `409 IDEMPOTENCY_KEY_REUSED`. A retry is the same request arriving twice. A different request on the same key is a client bug, and returning someone else's booking would hide it.

A cancelled flight refuses bookings:

```bash
curl -s -u api:dev-secret -X DELETE localhost:8080/api/v1/flights/UA456   # 204 -> CANCELLED

curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA456","passengerName":"Smit","seats":1,"idempotencyKey":"demo-2"}'
# 409 {"code":"FLIGHT_NOT_BOOKABLE","message":"Flight UA456 is CANCELLED and cannot be booked"}

curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA456           # availableSeats unchanged
```

`PATCH /api/v1/flights/{n}/status` with body `{"status":"DEPARTED"}` moves a flight through the state machine. `SCHEDULED`, `BOARDING` and `DELAYED` sell seats, and `DEPARTED`, `ARRIVED` and `CANCELLED` return 409. `BOARDING → ARRIVED` is `409 ILLEGAL_STATUS_TRANSITION`, because an aircraft cannot land without departing, and `CANCELLED` and `ARRIVED` are terminal. I added the cancelled-flight refusal after a probe of a running instance found it missing ([NOTES.md](NOTES.md#seats-sold-on-a-cancelled-flight)).

### Against real PostgreSQL

```bash
docker run --name pg -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=flightops \
  -p 5432:5432 -d postgres:17-alpine
DB_PASSWORD=pass ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

`DB_PASSWORD` has no default: in the `postgres` profile, `spring.datasource.password` is plain `${DB_PASSWORD}`, because a scanner is right to flag a working password in a public repository. Leave it out and Hikari sends the literal `${DB_PASSWORD}`, and the boot fails with `FATAL: password authentication failed`, which names the wrong cause. `docker compose up --build` needs none of this, because `compose.yaml` sets it.

The `postgres` profile switches the schema owner. Flyway applies the migrations in `db/migration` (`V1__init.sql` through `V8__drop_unused_active_booking_index.sql`), and Hibernate runs `ddl-auto: validate`, so an entity that no longer matches the tables, columns or column types fails the boot instead of altering them. It does not compare check constraints or indexes.

## Project status

This table bounds every claim in this README.

| Status | What |
|---|---|
| **Built, tested, and exercised over HTTP** | The whole app module. Every endpoint hit with `curl` against a running instance. Every status code in the tables below observed over HTTP or in a test, including the 401 and 403 bodies, except two 503s that no test covers: the one on a booking cancellation, and the health 503 during a database outage. `LockTimeoutTest` holds the flight row and gets the booking's 503 end to end. The flight status change and cancellation get theirs only in a slice test with a mocked service (`FlightControllerTest#flightWriteBehindARowLockReturns503`), and `DATABASE_UNAVAILABLE` likewise, on a flight read (`FlightControllerTest#noDatabaseConnectionReturns503`). The Lambda module, via 25 tests: 19 for the handler and 6 for the consumer side of the event contract. |
| **Verified against real PostgreSQL in CI** | The 9 Testcontainers integration tests: 5 in `BookingIntegrationTest`, 3 in `service/OutboxPrunePostgresTest` and 1 in `LockTimeoutPostgresTest`. CI runs all 285 tests, and these 9 are the only ones on PostgreSQL. The 9 apply the Flyway migrations to an empty database and check `ddl-auto: validate` against the schema those migrations produced. They run `SELECT … FOR UPDATE` under 20 threads competing for 5 seats, replay the same idempotency key from 20 threads at once, and hold a flight row until PostgreSQL's own 3 s `lock_timeout` fires with SQLSTATE `55P03`. The runners have Docker, so these execute there and skip on a laptop without one, and the `build` job fails if any of the three classes is skipped or missing. |
| **Built and started in CI, never pushed** | The container image. The `image` job builds it from the `Dockerfile` on every push to `main` and every pull request, starts it with no environment, and fails unless it stops at startup for want of a database. It has never been pushed to a registry, never run against a database and never served a request. |
| **Authored and reviewed, never executed** | `sam deploy` and `sam local invoke`; there is no `sam build`, because Maven builds the jar that `template.yaml` names. Every `kubectl` and `eksctl` step. The deploy half of the GitHub Actions workflow, which is gated off (see below). |
| **Not implemented** | A Solace binding. Trace **export** from the service: ids are generated and logged, but there is no collector to send spans to. The Lambda's `Tracing: Active` has X-Ray record a sample of its invocations, and those traces do not carry the service's trace id. Rate limiting. |

**Warning.** Nothing here has ever been deployed, and merging to `main` does not deploy it.

The deploy job is gated on a `DEPLOY_ENABLED` repository variable that has never been set. A gate on the branch alone would make the first push to a fresh clone assume an IAM role built from an unset `AWS_ACCOUNT_ID` secret, and go red for a reason unrelated to the code. So `build`, `infra-lint`, `trivy-fs`, `docs-check` and `image` run on every push to `main` and every pull request, with `dependency-review` on pull requests only. The deploy job reports as skipped until someone provisions the role and sets the variable. Read the green badge as "it builds and the tests pass".

**Why the gap:** I wrote and reviewed the infrastructure on a machine with no container runtime and no cluster. CI runs what it can reach, including the integration tests that need a real database. The rest of the "never executed" row needs a registry, a cluster or a funded AWS account, and the project has none of the three.

What the repository does not claim:

- I wrote `events/*.json` by hand, and none of it is captured queue traffic. Their `md5OfBody` values are placeholders, and nothing in the code reads that field.

## Architecture

```
                       ┌──────────────────────────────────────────────┐
   HTTP ──────────────►│ flight-ops-service     Java 21 / Boot 4.1.1  │
   Basic or Bearer     │                                              │
                       │  SecurityFilterChain   401 vs 403, before    │
                       │      │                 the DispatcherServlet │
                       │      ▼                                       │
                       │  Controller   HTTP only: bind, validate,     │
                       │      │        map to DTO, choose status      │
                       │      ▼                                       │
                       │  Service      orchestration, idempotency     │
                       │      │                                       │
                       │      ▼                                       │
                       │  BookingWriter  @Transactional boundary      │
                       │      │                                       │
                       │      ▼                                       │
                       │  Entity       the invariants live here       │
                       │      │        (Flight.reserveSeats)          │
                       │      ▼                                       │
                       │  Repository   Spring Data JPA                │
                       │      │                                       │
                       │      ▼                                       │
                       │  PostgreSQL (Flyway) / H2   flights,         │
                       │      ▲                      bookings,        │
                       │      │                      outbox_events    │
                       │      │   one transaction writes the booking  │
                       │      │   and the event together              │
                       │      │                                       │
                       │  OutboxPublisher  @Scheduled, FOR UPDATE     │
                       │      │            SKIP LOCKED, every 1s      │
                       │      ▼                                       │
                       │  EventPublisher ──► LoggingEventPublisher    │
                       │       (interface)   SqsEventPublisher        │
                       └───────────────────┬──────────────────────────┘
                                           │ BookingCreatedEvent
                                           ▼
                                    ┌──────────────┐
                                    │  SQS queue   │──► DLQ after 3 receives
                                    └──────┬───────┘
                                           ▼
                       ┌──────────────────────────────────────────────┐
                       │ Lambda  java21 / arm64  (separate module)    │
                       │  batch of 10 · partial batch response ·      │
                       │  conditional write ──► DynamoDB              │
                       └──────────────────────────────────────────────┘
```

The same picture with method names, plus the booking sequence, the idempotency decision table, the lock order, the outbox drain, the `FlightStatus` state machine and the ER diagram, is in [ARCHITECTURE.md](ARCHITECTURE.md). Each decision has its own file in [adr/](adr/README.md).

`app.events.publisher: log | sqs` picks the implementation with `@ConditionalOnProperty`, and `log` is the default, so nothing tries to reach AWS on a laptop. `EventProperties` refuses any other value at startup and names the property. `@Primary` and `@Qualifier` only choose which bean is injected, and still build every candidate, including an SQS client on a machine with no credentials. `@ConditionalOnProperty` decides whether the bean exists at all. There is no Solace implementation. One would need no change to `EventPublisher`, which takes a serialised payload and headers, but it would need a publisher class, a `ConnectionFactory` bean and the vendor's client library, a mode, a test and a new consumer, because the Lambda reads `SQSEvent`. [ADR 0015](adr/0015-event-transport.md) sets out that cost from Solace's documentation, not from a run here.

## Repository layout

```
├── src/main/java/com/smit/flightops/       57 files, 3,939 lines
│   ├── controller/     HTTP only: bind, validate, map to DTO, choose the status code
│   ├── service/        orchestration, transaction boundaries, the outbox drain and pruner
│   ├── entity/         Flight, Booking, FlightStatus, OutboxEvent: the invariants
│   ├── repository/     Spring Data JPA, the FOR UPDATE query, the SKIP LOCKED claim
│   ├── dto/            8 records, including BookingCreatedEvent (the wire contract)
│   ├── exception/      9 domain exceptions, the @RestControllerAdvice, ApiErrorController
│   ├── security/       the JSON 401 and 403 writers
│   ├── observability/  RequestIdFilter, BookingMetrics, OutboxMetrics
│   ├── validation/     @DistinctEndpoints, a class-level Bean Validation constraint,
│   │                   and IsoInstantDeserializer, which takes only an ISO-8601 instant
│   └── config/         SecurityConfig, OpenApiConfig, AwsConfig, four
│                       @ConfigurationProperties records, TimeConfig, DataSeeder
├── src/main/resources/
│   ├── application.yml            profiles: default (H2), postgres, prod
│   └── db/migration/              Flyway V1–V8, which owns the PostgreSQL schema
├── src/test/java/                 33 test classes (35 with the Lambda's)
├── NOTES.md                       the bugs I found and fixed
├── ARCHITECTURE.md                the diagrams and the method-by-method request path
├── DEPLOYMENT.md                  three shapes, the runbook, the cost of each, the teardown
├── CHANGELOG.md                   1.0.0, 1.1.0, the unreleased work, and the response field 1.1.0 removed
├── adr/                           15 decision records, 0001–0015
├── scripts/                       refcheck.py, linkcheck.py and sweeps.sh run in CI;
│                                  numbers.sh recomputes the counts
├── contracts/                     the event schema both modules test against
├── lambda/                        separate parentless Maven module: SQS → DynamoDB consumer
├── k8s/                           kustomize: base, aws overlay, Ingress component
│   ├── base/                      6 manifests true in any environment
│   ├── overlays/aws/              image, IRSA annotation, queue URL, database URL
│   └── components/ingress/        separate, because applying it provisions a billed ALB
├── deploy/aws/                    up / down / cost-check / render, a selftest against
│                                  stubbed tools, two helpers, two CloudFormation
│                                  templates, and the runbook that orders them
├── events/                        SQS fixtures for sam local invoke
├── cluster.yaml                   eksctl cluster definition, version pinned
├── template.yaml                  SAM template for the Lambda
├── compose.yaml                   PostgreSQL + the app, for the container path locally
├── Dockerfile                     multi-stage: JDK + Maven build → JRE runtime
└── .github/workflows/             build-and-deploy.yml (build, infra-lint, trivy-fs,
                                   docs-check and image on every trigger, dependency-review
                                   on pull requests, the gated deploy), codeql.yml
```

A DTO never reaches the repository, and an entity never reaches a controller. Nothing in `service/`, `entity/` or `repository/` knows about HTTP. That stays in the web edge (`controller/`, `exception/`, `security/`, `observability/RequestIdFilter`, `config/SecurityConfig`), and ArchUnit fails the build if it leaks inward.

## Security

Callers use HTTP Basic or a bearer token. The two in-memory accounts are `api`, holding `SCOPE_flights:read` and `SCOPE_flights:write`, and `ops`, holding `ROLE_OPS`. Bearer tokens are validated as an OAuth2 resource server once `spring.security.oauth2.resourceserver.jwt.issuer-uri` is set. With no issuer there is no `JwtDecoder` bean, and the app boots with Basic alone. A token's `scope` claim maps to the same `SCOPE_` strings the `api` user holds, so the rules do not know which mechanism authenticated a request.

| Path | Who gets in |
|---|---|
| `/actuator/health`, `/health/liveness`, `/health/readiness` | everyone: the kubelet has no credentials, and a probe that needed them would fail the pod on a password rotation. Only `ops` sees the components behind the status (`management.endpoint.health.roles: OPS`) |
| `/actuator`, `/actuator/info`, `/actuator/metrics/**`, `/actuator/prometheus` | `ROLE_OPS` |
| `GET`/`HEAD` `/api/**` | `SCOPE_flights:read` |
| `POST`, `PATCH`, `DELETE /api/**` | `SCOPE_flights:write` |
| `GET`/`HEAD` on `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html` and `/swagger-ui/**` | everyone (see [adr/0012](adr/0012-openapi-public-read.md)) |
| `/error` | everyone. The container forwards errors raised outside Spring MVC (a firewall-rejected URL, an exception in a filter) to `/error` after the chain has run, and denying it would turn each of those into a 401 about `/error`. MVC 404s never get there, because `GlobalExceptionHandler` answers them first. `exception/ApiErrorController` answers in the same `{code, message, timestamp}` envelope with a generic message, because the container's own error text can name an internal path or exception class |
| anything else | `denyAll()` |

The last rule is `anyRequest().denyAll()`. A controller added later is unreachable until I add its rule, which costs one line. With `permitAll()` it would be public on the day it ships, and with `authenticated()` any caller with credentials could reach it.

The in-memory users stand in for an identity provider, and [SECURITY.md](SECURITY.md) covers sessions, CSRF, secrets, transport and the known limitations.

## API

| Method | Path | Requires | Success | Failures |
|---|---|---|---|---|
| `GET` | `/api/v1/flights/{flightNumber}` | `flights:read` | 200 | 404 |
| `GET` | `/api/v1/flights?origin=&destination=&page=&size=&sort=` | `flights:read` | 200 (paginated) | 400 |
| `POST` | `/api/v1/flights` | `flights:write` | 201 + `Location` | 400, 409, 415 |
| `PATCH` | `/api/v1/flights/{flightNumber}/status` | `flights:write` | 200 | 400, 404, 409, 415, 503 |
| `DELETE` | `/api/v1/flights/{flightNumber}` | `flights:write` | 204 | 404, 409, 503 |
| `POST` | `/api/v1/bookings` | `flights:write` | 201 + `Location` | 400, 404, 409, 415, 503 |
| `GET` | `/api/v1/bookings/{bookingId}` | `flights:read` | 200 | 400, 404 |
| `GET` | `/api/v1/bookings?flightNumber=&page=&size=&sort=` | `flights:read` | 200 (paginated) | 400 |
| `DELETE` | `/api/v1/bookings/{bookingId}` | `flights:write` | 200 | 400, 404, 503 |
| `GET` | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | none | 200 | 401 on a wrong password; 503 while the status is `DOWN` or `OUT_OF_SERVICE`, so a database outage takes `/health` and `/readiness` to 503 and leaves liveness at 200 |
| `GET` | `/actuator/metrics`, `/actuator/prometheus` | `ROLE_OPS` | 200 | 401, 403 |

Every `/api/**` row also answers 401 without valid credentials, a wrong password included, and 403 to an authenticated caller who lacks the authority. Both controllers produce and read JSON only. An `Accept` header of `application/xml` gets 406. A `POST` or `PATCH` whose `Content-Type` is missing or is not `application/json` gets 415, YAML and multipart included, so `curl -d` needs `-H 'Content-Type: application/json'`. The header decides, so a YAML body sent as `application/json` is `400 MALFORMED_REQUEST`. Any `/api/**` row can also answer `503 DATABASE_UNAVAILABLE` when the service cannot reach its database. The health rows need no credentials.

Every error the application produces has one JSON shape, `{code, message, timestamp}` or `{code, fieldErrors, timestamp}`, from `GlobalExceptionHandler`, `ErrorResponseWriter` (401 and 403) and `ApiErrorController` (`/error`). Each sets `Content-Type: application/json` itself, whatever the `Accept` header asked for. No controller contains a `try`/`catch`. Tomcat refuses some requests before Spring sees them: `%2F`, `%5C`, `%00` or `%zz` in the path, a raw `|`, or a 20KB header. Those get Tomcat's own HTML 400 page with no `X-Request-Id`, and an unknown path under an exposed actuator endpoint, such as `/actuator/metrics/nope`, returns an empty 404.

Jackson and Hibernate exception text names internal classes, tables and columns, so the client gets a fixed string and the detail goes to the log at WARN. I wrote the project's own exception messages for clients, and those pass through: `FlightNotFoundException`, `BookingNotFoundException`, `InsufficientSeatsException`, `FlightNotBookableException`, `DuplicateFlightException`, `IllegalFlightTransitionException`, `IdempotencyKeyConflictException` and `UnknownSortPropertyException`. So does Spring MVC's own `ErrorResponse` detail, such as `Method 'POST' is not supported.`, which names only the request. A `POST` or `PATCH` with no `Content-Type` is the exception: Spring would say `Content-Type 'null' is not supported.`, so it gets `The request has no Content-Type. Send application/json.` A stray `IllegalArgumentException` gets the fixed `The request contained an invalid value.`

### Error codes

| Code | Status | Meaning |
|---|---|---|
| `FLIGHT_NOT_FOUND` | 404 | no such flight number |
| `BOOKING_NOT_FOUND` | 404 | no such booking id; its own code, so a booking 404 does not claim the flight is missing |
| `INSUFFICIENT_SEATS` | 409 | fewer seats remain than requested; a retry with fewer seats can succeed |
| `FLIGHT_NOT_BOOKABLE` | 409 | the flight is `CANCELLED`, `DEPARTED` or `ARRIVED`; a retry can never succeed |
| `DUPLICATE_FLIGHT` | 409 | the flight number already exists |
| `CONCURRENT_MODIFICATION` | 409 | `@Version` rejected a stale write |
| `DUPLICATE_REQUEST` | 409 | two flight-creation requests raced on `flight_number` and the constraint chose one; a raced booking recovers instead |
| `IDEMPOTENCY_KEY_REUSED` | 409 | the key was first used for a different request. That is a client bug; a true replay returns the original booking |
| `ILLEGAL_STATUS_TRANSITION` | 409 | the flight cannot go from its status to the requested one (`BOARDING → ARRIVED`, anything out of `ARRIVED`) |
| `LOCK_TIMEOUT` | 503 + `Retry-After` | a write waited out the 3s `lock_timeout` on the flight row: a booking or a booking cancellation on its `SELECT … FOR UPDATE`, or a status change or flight cancellation queued behind one. The request was valid and the row was busy, so retry after `Retry-After` |
| `DATABASE_UNAVAILABLE` | 503 + `Retry-After` | no database connection: the pool stayed empty for its whole connection timeout, or the database did not answer. The request was valid and can succeed later, so it is a 503 and not a 500, and it is logged at WARN with no stack trace |
| `UNKNOWN_SORT_PROPERTY` | 400 | the sort property is not on the endpoint's published list (`SortPolicy`) |
| `UNAUTHENTICATED` | 401 | no credentials, or credentials that do not verify; written by `JsonAuthenticationEntryPoint` |
| `FORBIDDEN` | 403 | authenticated, without the authority this path needs; written by `JsonAccessDeniedHandler` |
| `VALIDATION_FAILED` | 400 | Bean Validation, per field, including `@DistinctEndpoints`, which refuses a flight from EWR to EWR. A flight number with a space, `/` or `%` inside gets `must contain only letters and digits`. An airport code with a digit, symbol or padding gets `must contain only letters`. A passenger name with a control character gets `must not contain control characters`. A missing or null `departureTime` gets `must not be null` |
| `MALFORMED_REQUEST` | 400 | unreadable body, an unknown enum constant or one sent as a number, a `seats` or `totalSeats` that is missing, null, quoted, or written with a decimal point or an exponent (`2.0` included), a `departureTime` that is not an ISO-8601 instant with `Z` or an offset (a missing or null one is `VALIDATION_FAILED`), bad path variable, missing query parameter, or `page * size` above 2147483647 on either list endpoint |
| `RESOURCE_NOT_FOUND` | 404 | unmapped path |
| `METHOD_NOT_ALLOWED` | 405 | a verb the security rules allow on a path that does not map it, such as `POST` on `/api/v1/flights/UA123`; the `Allow` header lists the mapped verbs. Tomcat refuses `TRACE` before any filter runs, so its 405 comes from `ApiErrorController`, with the servlet's full `Allow` list and no `X-Request-Id`. `PUT` and `OPTIONS` get 403 from `anyRequest().denyAll()`, or 401 without credentials |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | a `Content-Type` that is missing or is not `application/json`, YAML included; the `Accept` header names JSON |
| `REQUEST_REJECTED` | 4xx | any other Spring MVC client error, such as the 406 for a non-JSON `Accept` |
| `BAD_REQUEST` | 4xx | any other client error the container forwards to `/error`; written by `ApiErrorController` in the same envelope |
| `INTERNAL_ERROR` | 500 | last resort; the stack trace is logged and never returned |

## Metrics

Every log line carries the service name, `traceId`, `spanId` and `requestId`. `RequestIdFilter` returns the request id as `X-Request-Id` on every response the application handles, 401 and 403 included, so a support call can start from an id on the user's screen. See [OPERATIONS.md](OPERATIONS.md) to follow one booking across the queue.

`/actuator/prometheus` needs `ops` credentials. Beside the Micrometer defaults it carries seven meters the HTTP metrics cannot express, from `observability/BookingMetrics` and `observability/OutboxMetrics`:

| Series | What it answers |
|---|---|
| `bookings_booked_total{outcome="created"\|"replayed"}` | A replay and a real booking are both 201 on the same URI. This separates selling seats from a client stuck in a retry loop. |
| `bookings_cancelled_total{outcome="cancelled"\|"already_cancelled"}` | A repeated `DELETE` returns 200 and releases nothing. `already_cancelled` climbing alone means a client thinks its cancellations are not sticking. |
| `bookings_lock_timeout_total` | 503s caused by a flight row held past `lock_timeout`. It is the leading indicator for the whole write path stalling. |
| `outbox_pending` | Unpublished and still retryable. A rising line is publisher lag, and it catches an SQS outage before a consumer notices missing events. |
| `outbox_dead` | Unpublished and out of attempts. Page on `> 0`: it does not recover by itself, and the event is never sent until someone re-drives the row. |
| `outbox_publish_total{result="success"\|"failure"\|"exhausted"}` | The transport's health. The failure rate shows a partial outage that `pending` hides while the backlog still drains faster than it grows. |
| `outbox_pruned_total` | Rows deleted by the retention job. Flat at zero while the table grows means the job has stopped, and nothing else would show it. |

Both gauges query the database on the scrape thread and return `NaN` instead of throwing. A gauge that throws takes the whole `/actuator/prometheus` response with it, so a database blip would hide every other metric when they are most needed. Nothing scrapes these meters in any deployment yet, so they drive no alerts.

A row out of attempts stays out of the drain until an operator re-drives it with this statement, which `OutboxPoisonRowTest.resettingAttemptsRedrivesTheRow` runs:

```sql
UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?
```

## Tests

```bash
./mvnw clean verify                       # 260 tests: 251 run, 9 skipped, 0 failures
./mvnw -f lambda/pom.xml clean verify     # 25 tests, 0 failures
```

| Layer | Tests | Tooling |
|---|---|---|
| Domain entity | 13 | plain JUnit, with no Spring and no database |
| Service | 36 | `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `@Captor`, split across `BookingServiceTest` (orchestration, including a failed insert with no winning booking to recover), `BookingWriterTest` (the write path), `FlightServiceTest` and `SqsEventPublisherTest` (what goes on the wire) |
| Web slice | 63 | `@WebMvcTest` + `@MockitoBean` in the two controller tests: status codes, `Location` headers, error JSON, `Allow` on a 405 and `Accept` on a 415, the 503 for a database that cannot be reached, a YAML body or a missing `Content-Type` refused on each `POST` and `PATCH`, and the rules for flight numbers, airport codes, passenger names, seat counts, status values and departure times. The other 2 are `exception/ApiErrorControllerTest`, with no Spring context. One calls `ApiErrorController` directly and one drives it through a standalone MockMvc, because a full MockMvc never forwards to `/error` |
| Repository slice | 10 | `@DataJpaTest` + `TestEntityManager`: derived queries, JPQL, `JOIN FETCH`, constraints |
| Full context (H2) | 84 | `@SpringBootTest`. The idempotency guarantee end to end, with four 10-caller races on one key: same request, different payloads, the last seat, and one key across two flights. The authorisation rules against the real filter chain, with the Basic and Bearer challenges and who sees health components. The outbox with its trace capture, the attempt ceiling and the retention pruner against an embedded database. The OpenAPI document's status codes per operation and its comparison with a real response. The lock timeout, the error contract with the 406 and `ignorecase` on a sort property that is not text, the page overflow and multipart parsing turned off, and a lazy-loading regression with no mocking anywhere in the chain |
| Event contract | 11 | one producer-side class and one consumer-side class, both asserting against `contracts/booking-created-v1.json`; the consumer side parses through the handler's own mapper |
| Lambda handler | 19 | separate module: batch parsing, partial batch failure and the conditional write. `seats` is refused with no coercion when it is missing, below 1, a string or fractional. Body values are logged on one line and capped at 1,000 characters, and the producer's trace context survives the queue |
| Configuration and startup checks | 23 | Boot's `Binder` over plain maps: an unresolved `${...}` placeholder is rejected at startup, every outbox bound is enforced and every default is wired. `EventPropertiesTest` also starts the whole application to see a bad `app.events.publisher` named, and `PasswordVerifiabilityTest` runs `SecurityConfig` in a `WebApplicationContextRunner` to see an unverifiable password stop startup. `ValidationClockTest` checks that `@Future` reads the `Clock` bean |
| Architecture | 9 | ArchUnit over `target/classes`: the layering, no field injection, no `@Transactional` outside `service/`, and in main code no `java.time` `now()` without a `Clock`, no `System.currentTimeMillis()`, no `new Date()` and no `Calendar.getInstance()`. I checked each rule against a planted violation before committing it |
| Observability | 8 | the request-id filter against a hostile inbound header, and the meters scraped through a real `PrometheusMeterRegistry`, since a `SimpleMeterRegistry` would accept any name |
| Run | 276 | 0 failures (13 + 36 + 63 + 10 + 84 + 11 + 19 + 23 + 9 + 8) |
| PostgreSQL integration | 9 | `@Testcontainers(disabledWithoutDocker = true)`: 5 in `BookingIntegrationTest`, 3 in `service/OutboxPrunePostgresTest` and 1 in `LockTimeoutPostgresTest`, skipped without a container runtime |

285 tests exist across the two modules: 276 run without Docker and 9 skip. CI runs all 285 on runners with Docker, and it is the only place the PostgreSQL paths run. Those are Flyway with `ddl-auto=validate`, `SELECT FOR UPDATE` under 20-way contention, a 20-thread key race, the native `DELETE … FOR UPDATE SKIP LOCKED` under two pruners, the outbox claim under two competing pollers, and PostgreSQL's own `lock_timeout` firing on a held flight row. The `build` job's "The PostgreSQL tests ran" step fails the run if any of the three classes skipped a test or left no report. See [CONTRIBUTING.md](CONTRIBUTING.md) for what the skipped count means.

## Deployment and cost

Nothing in this section has been executed, except building the image and starting it with no database, which the `image` job does in CI.

```bash
docker compose up --build                       # the whole stack, locally
kubectl kustomize k8s/overlays/aws               # the EKS manifests, before render-aws.sh fills the ${…} values
```

The image is built on an amd64 CI runner for amd64 nodes, so the pipeline needs no `--platform` flag. A plain `docker build` on Apple Silicon produces arm64, and the pod crash-loops with `exec /bin/sh: exec format error`, so build locally with `--platform linux/amd64`. The Lambda runs on arm64, so this applies to the service image only.

The `Dockerfile` sets `SPRING_PROFILES_ACTIVE=prod`, so a bare `docker run` with no database stops at startup with `'url' must start with "jdbc"`. Without that default it would serve in-memory H2 with the `{noop}` dev passwords. `compose.yaml` selects `postgres`, and the ConfigMap selects `prod`.

The image measured 299.5 MB (299477691 bytes) in CI run 36032424801 on 2026-09-24, as `docker image inspect` reports it on the runner. It has never been pushed, so no registry has reported a size for it. The runtime base is a tag rather than a digest, so the figure moves when `eclipse-temurin:21-jre-alpine` is rebuilt or a dependency changes.

The Lambda's SQS event in `template.yaml` caps the consumer at five concurrent invocations with `ScalingConfig.MaximumConcurrency: 5` (valid from 2 to 1000). It reserves nothing from the account's concurrency pool and limits only the poller.

The EKS control plane bills about $0.10 an hour (about $73 a month) with no worker nodes, until it is deleted. On demand in `ap-south-1` the whole stack costs $7.72 a day: $54 for 7 days, $116 for 15 and $232 for 30, or $64, $137 and $273 with the 18% GST AWS India invoices. See [DEPLOYMENT.md](DEPLOYMENT.md) for the breakdown, three cheaper shapes (one is a single EC2 instance at $0.80 a day) and the runbook. `deploy/aws/down.sh` ends with fourteen checks and fails if any finds something or if a query itself fails.

## Trade-offs and known limitations

Each row is a choice I made, set against what a production system would do.

| Current | Production would be | Why it is this way |
|---|---|---|
| Two users in an `InMemoryUserDetailsManager` | Cognito, Okta or Entra behind `issuer-uri` and `audiences` | The rules are real and tested, and the user store is a stub. The resource-server half is wired and activates when an issuer is configured, so the swap is configuration: set both properties, as [SECURITY.md](SECURITY.md) shows. |
| Idempotent replay returns 201 | 200, arguably | It replays the original response, Stripe-style, so the body is identical. "201 Created" for something not created this time is a fair challenge. I documented it and left it. |
| A poller drains the outbox | Debezium reading the WAL | A 1-second poll costs one indexed query per replica per second and adds up to a second of latency. CDC removes both and adds Kafka Connect, a connector to operate and a replication slot that fills the disk if the consumer stops. |
| Retention is a batched `DELETE` on a schedule | a partitioned table, dropping old partitions | Detaching and dropping an old partition is O(1) and a delete is not, which matters from roughly the first hundred million rows. Below that, partitions add a maintenance job and an outage when that job fails. The pruner is 40 lines and bounded. |
| No circuit breaker | Resilience4j | There is one outbound dependency, and the outbox already absorbs its failure: a down SQS leaves rows unpublished and the next drain retries them. |
| Contract tests share a JSON file | Pact, with a broker and a `can-i-deploy` gate in CI | The file catches the change that breaks the consumer, which is the whole job at two modules in one repository. A broker pays off when the consumers are other teams' services. |
| The service's traces are generated and not exported; the trace id crosses into the Lambda as a log line | an OTLP collector on both sides, so the queue hop is one waterfall | The ids are on every log line and response, and `BookingEventHandler` logs the producer's `traceparent`, so two log greps follow one booking end to end. A waterfall needs a collector, and an exporter in a function whose whole point is a small package (10.3 MiB, with a 34 KB HTTP client) and a fast cold start. |
| H2 uses `create-drop` | Flyway + `validate`, as PostgreSQL already has | Migrations on a throwaway in-memory database buy nothing. |
| `events/*.json` `md5OfBody` values are placeholders | real captured messages | Nothing reads the field, but it is not real traffic. |

### Still open

| What happens | What should happen | The fix |
|---|---|---|
| There is no rate limiting. A single caller with valid credentials can saturate the pool. | A token bucket per principal at the gateway, or Bucket4j in front of the write endpoints. | Out of scope for the service. It belongs at the ingress, and I would sooner say so than add a half-measure here. |

## What I found in review

I reproduced most of these against a running instance before fixing them. The teardown bugs came from reading `deploy/aws/down.sh` the way an operator would, since there is no AWS account to run it against. Each story, with its test and commit, is in [NOTES.md](NOTES.md).

- [Seats sold on a cancelled flight](NOTES.md#seats-sold-on-a-cancelled-flight). `Flight.reserveSeats` checked the seat count and ignored the status, and every test passed.

- [A Location header that led to a 404](NOTES.md#a-location-header-that-led-to-a-404). The test checked the header's text and never followed it.

- [One idempotency key, two answers](NOTES.md#one-idempotency-key-two-answers). Ten racing replays of one booking got a mix of 201 and 409.

- [A booking lookup that failed on every call](NOTES.md#a-booking-lookup-that-failed-on-every-call). A lazy association was read after its session had closed.

- [The outbox and a slow queue](NOTES.md#the-outbox-and-a-slow-queue). An SQS call with no timeout ran inside the booking transaction, and the fix grew into an outbox.

- [Metrics under the wrong names](NOTES.md#metrics-under-the-wrong-names). `bookings.created` exported as `bookings_total`, with no warning.

- [A teardown that could pass on an error](NOTES.md#a-teardown-that-could-pass-on-an-error). An expired token made every check in `deploy/aws/down.sh` print PASS.

- [Three review passes](NOTES.md#first-review-pass) over my own code found more, from a readiness probe that ignored the database to `@Lob` on PostgreSQL.

- [A fourth review pass](NOTES.md#fourth-review-pass) audited every file. It found a fractional seat count accepted as a whole one, first in JSON and then again in YAML, and a status sent as a number read as the enum constant at that position. It also found a flight number with a slash that broke the `Location` header, and a page number that overflowed to a 500. Among the rest were a publisher setting whose check never ran and a Lambda template that `sam build` could not build.

## Versions

Platform: Java 21.0.12.1 · Jakarta EE 11 · Spring Boot 4.1.1 · Spring Framework 7.0.9 · Spring Security 7.1.1 · Tomcat 11.0.24.

Libraries: Hibernate 7.4.5 · Jackson 3.1.5 · Flyway 12.4.0 · springdoc-openapi 3.1.1 · AWS SDK for Java 2.55.2.

Build and test: Maven 3.9.16 · JUnit 6.0.3.

One Boot-managed version is overridden in `pom.xml`: `jackson-2-bom.version` is set to 2.22.2. Boot 4 runs on Jackson 3 and still manages the Jackson 2 coordinates at 2.21.5 for libraries that have not moved. The OpenAPI document is built by swagger-core, which is one of them and needs at least 2.22.1. Maven's nearest-wins would have handed it the older Jackson 2 with no error, and the enforcer's `requireUpperBoundDeps` rule refused the build instead.

Built and tested on macOS arm64 with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`. What the Boot 3.5 to 4.1 upgrade broke is in [NOTES.md](NOTES.md#the-boot-4-upgrade).

`./mvnw` pins Maven 3.9.16 and its SHA-256, so CI needs no Maven install step and a substituted archive fails the build. The wrapper is `distributionType=only-script`: two shell scripts and a properties file, with no `maven-wrapper.jar` committed.

[`.github/dependabot.yml`](.github/dependabot.yml) updates both Maven modules, the Actions workflow and the Dockerfile base images. The Lambda module has its own entry, because with no parent POM nothing else manages its versions. See [CONTRIBUTING.md](CONTRIBUTING.md#dependabot) for how I handle the pull requests.

## Licence

MIT, see [LICENSE](LICENSE).
