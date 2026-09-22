# flight-ops-service

[![build & tests](https://img.shields.io/github/actions/workflow/status/smit-lakhani-13/flight-ops-services/build-and-deploy.yml?branch=main&label=build%20%26%20tests)](https://github.com/smit-lakhani-13/flight-ops-services/actions/workflows/build-and-deploy.yml)

Flight inventory and booking microservice: a Spring Boot REST API over PostgreSQL that publishes booking events to SQS, where an AWS Lambda consumer projects them into DynamoDB.

**Java 21 · Spring Boot 4.1.1 · Spring Security 7 · Spring Data JPA · PostgreSQL / H2 · Flyway · AWS SQS + Lambda + DynamoDB · Docker · Kubernetes / EKS · SAM · GitHub Actions**

The airline domain is deliberate. Seat inventory is a genuinely hard consistency problem: multiple clients compete for the same finite resource, retries are unavoidable, and getting it wrong means selling the same seat twice. That gives every concurrency and idempotency decision in this repository a concrete reason to exist rather than a theoretical one.

**Scope.** This is a demonstration service, not a deployed system. It has never served production traffic. CI builds both modules and runs the full test suite — including the PostgreSQL integration tests — on every push. The infrastructure that needs a registry, a cluster or an AWS account (`Dockerfile`, `k8s/`, `template.yaml`, and the deploy half of the workflow) is authored and reviewed but has not been applied. [Project status](#project-status) records exactly which parts have been executed and which have not, and every claim below is bounded by that table.

**Contents** — [Run it](#run-it-in-30-seconds) · [Project status](#project-status) · [Architecture](#architecture) · [Repository layout](#repository-layout) · [Security](#security) · [API](#api) · [OpenAPI](#openapi) · [Concurrency](#the-hard-problem-not-overselling-the-last-seat) · [The outbox](#the-outbox-why-the-event-is-a-database-row-first) · [Observability](#observability) · [Tests](#tests) · [Lambda](#lambda-module) · [Container and Kubernetes](#container-and-kubernetes) · [Cost safety](#cost-safety--read-this-before-touching-aws) · [Trade-offs](#trade-offs-and-known-limitations)

---

## Run it in 30 seconds

**Prerequisite: a JDK 21.** Nothing else — no database, no AWS account, no Docker, no local Maven install.

```bash
java -version          # must report 21
./mvnw spring-boot:run
```

In-memory H2, schema created by Hibernate, three demo flights seeded on boot (`UA123` EWR→LHR 180 seats, `UA456` ORD→SFO 150, `UA789` EWR→SFO 200). Boots in **under 4s** — 2.83s / 2.57s / 3.63s across three local runs, with the security filter chain and the outbox scheduler both starting.

**Every `/api/**` call needs credentials.** The default profile ships two throwaway accounts so nothing needs setting up: `api` / `dev-secret` for the API, `ops` / `dev-ops` for the actuator endpoints that are not health. They are stored as `{noop}dev-secret` — the `{noop}` prefix says out loud that the value is not hashed and is therefore not a secret. The `prod` profile removes the defaults entirely **and** `ApiSecurityProperties` rejects a value without a `{id}` prefix, so a deployment that forgets to set `API_PASSWORD` fails at startup instead of booting with a password that is in this README. Removing the default is not sufficient on its own — `@ConfigurationProperties` binding ignores unresolvable placeholders, so the literal string `${API_PASSWORD}` binds happily and the pod goes Ready before failing every request; the constraint is what makes the sentence true. See [Security](#security).

> **On macOS, a JDK-selection trap worth knowing.** `/usr/libexec/java_home -v 21` only resolves JDKs registered with macOS, and Homebrew's are not. On a machine that also has an Oracle JDK 17 installed it therefore exits 0 and hands back *17* — and the build dies several steps later on `release version 21 not supported`, a long way from the actual cause. Set `JAVA_HOME` explicitly: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on Apple Silicon, `/usr/local/opt/openjdk@21` on Intel.

```bash
curl localhost:8080/actuator/health                       # open — the kubelet has no credentials
curl -u api:dev-secret localhost:8080/api/v1/flights/UA123
curl -u api:dev-secret "localhost:8080/api/v1/flights?origin=EWR&page=0&size=5"
curl -u ops:dev-ops localhost:8080/actuator/metrics       # ops, not api
```

Or open **<http://localhost:8080/swagger-ui.html>** and click through it: the document is public,
every operation it lists is not. See [OpenAPI](#openapi).

Drop the `-u` and you get `401 {"code":"UNAUTHENTICATED"}`. Use `api` where `ops` is wanted and you get `403 {"code":"FORBIDDEN"}` — a different answer to a different question, and the distinction is the point.

### Or run the whole tour at once

With the app running, in a second terminal:

```bash
./demo.sh          # pauses between acts, so you can talk over it
./demo.sh --fast   # no pauses
```

Eight acts over real HTTP: the paged API and the normalised `Location` header; idempotent
replay; the deliberate error codes including the cancelled-flight refusal; **ten concurrent
callers racing on one idempotency key**, twice — once with the same payload, where all ten
get the same booking, and once with ten different payloads, where nine are told the key is
taken; the flight-status state machine refusing `BOARDING → ARRIVED`; cancel-and-refund;
the 401/403 split; and the actuator surface Kubernetes probes. It preflights that the app is
up *and* that the credentials work, and falls back to `python3 -m json.tool` if `jq` is
absent. State is in-memory — restart the app to reset it.

### The two behaviours worth demonstrating

**1. A retry doesn't double-book.**

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
curl -s -u api:dev-secret "localhost:8080/api/v1/bookings?flightNumber=UA123"   # exactly ONE booking
```

> Spelled out on every line on purpose. `A='-u api:dev-secret'; curl $A ...` is the obvious tidy-up and it **fails on macOS**: zsh does not word-split unquoted parameters the way bash does, so curl receives `-u api:dev-secret` as a single argument, ignores it, and every call comes back 401. Use an array if you want the shorthand: `A=(-u api:dev-secret); curl "${A[@]}" ...`

Change the payload and keep the key, and it is a different answer: `409 IDEMPOTENCY_KEY_REUSED`. A retry is the same request arriving twice; a *different* request on the same key is a client bug, and returning somebody else's booking would hide it.

**2. A cancelled flight refuses bookings.**

```bash
curl -s -u api:dev-secret -X DELETE localhost:8080/api/v1/flights/UA456   # 204 -> CANCELLED

curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA456","passengerName":"Smit","seats":1,"idempotencyKey":"demo-2"}'
# 409 {"code":"FLIGHT_NOT_BOOKABLE","message":"Flight UA456 is CANCELLED and cannot be booked"}

curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA456           # availableSeats unchanged
```

`PATCH /api/v1/flights/{n}/status` with body `{"status":"DEPARTED"}` moves a flight through the state machine. `SCHEDULED`, `BOARDING` and `DELAYED` sell seats; `DEPARTED`, `ARRIVED` and `CANCELLED` return 409. The transitions themselves are constrained too — `BOARDING → ARRIVED` is `409 ILLEGAL_STATUS_TRANSITION`, because an aircraft cannot land without departing, and `CANCELLED` and `ARRIVED` are terminal. The second behaviour is the interesting one — it did not exist until a probe against a running instance found it missing. See [the bug a passing suite did not catch](#the-bug-that-a-passing-test-suite-did-not-catch).

### Against real PostgreSQL

```bash
docker run --name pg -e POSTGRES_PASSWORD=pass -e POSTGRES_DB=flightops \
  -p 5432:5432 -d postgres:16-alpine
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

The `postgres` profile switches the schema owner: **Flyway** applies `V1__init.sql` and Hibernate runs `ddl-auto: validate`, so entity drift fails the boot instead of silently altering tables. H2 keeps `create-drop`, because for a throwaway in-memory database migrations buy nothing.

---

## Project status

What has been executed, and what has not. This table is the contract for every claim in this README.

| | What |
|---|---|
| ✅ **Built, tested, and exercised over HTTP** | The whole app module. Every endpoint hit with `curl` against a running instance; every status code in the tables below observed, not inferred, including the 401 and 403 bodies. The Lambda handler's logic, via 18 unit tests. |
| ✅ **Verified against real PostgreSQL in CI** | All 197 tests, including the 5 Testcontainers integration tests: the Flyway migrations applied to an empty database, `ddl-auto: validate` checked against the schema those migrations produced, `SELECT … FOR UPDATE` under 20 threads competing for 5 seats, and the same idempotency key replayed by 20 threads at once. The runners have Docker, so these execute there and skip on a laptop without one. |
| ⚠️ **Authored and reviewed, never executed** | The container image. `sam build`, `sam local invoke`, `sam deploy`. Every `kubectl` and `eksctl` step. The deploy half of the GitHub Actions workflow — gated off deliberately, see below. |
| ❌ **Not implemented** | A Solace binding. Trace **export** — ids are generated and logged, but there is no collector to send spans to. Rate limiting. |

**Nothing here has ever been deployed, and merging to `main` does not deploy it.** The deploy job is gated on a `DEPLOY_ENABLED` repository variable which has never been set, so it reports as skipped on every run. Read the green badge as "it builds and the tests pass", which is what it says.

**Why the gap:** the infrastructure was authored and reviewed on a machine with no container runtime and no cluster to apply it to. That makes it reviewed, not verified — a real distinction, and one worth recording here rather than glossing over. CI closes the part of the gap it can reach: the runners have Docker, so the integration tests that need a real database run there and nowhere else. What is left in the ⚠️ row needs a registry, a cluster, or a funded AWS account, and this project has none of the three.

The deploy half of that pipeline is **switched off by default**, gated on a `DEPLOY_ENABLED` repository variable rather than on the branch alone. Without the gate, the first push to a fresh clone would try to assume an IAM role built from an unset `AWS_ACCOUNT_ID` secret, and the job would fail on missing configuration rather than on anything about the code. A red build meaning "nobody provisioned the cluster" is worse than no build, because it teaches whoever reads the repository next to stop trusting the colour. So `build` and `verify` run on every push and the deploy steps report as skipped until someone provisions the role and flips the variable.

Two further things this repository does not claim:

- **`events/*.json` are hand-written fixtures, not captured queue traffic.** Their `md5OfBody` values are placeholders. Nothing in the code reads that field.
- **No image size is quoted.** The Dockerfile is multi-stage and does the right things; a figure would have to be invented, so there isn't one.

---

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

`app.events.publisher: log | sqs` picks the implementation via `@ConditionalOnProperty`, and `log` is the default so that nothing tries to reach AWS on a laptop. Note the mechanism: `@Primary` and `@Qualifier` decide which bean gets *injected* but still instantiate every candidate, which for an SQS client means building it on a machine with no credentials. `@ConditionalOnProperty` decides whether the bean is *defined at all*.

**There is no Solace implementation.** Solace is named in a comment on `EventPublisher` as one of the transports that interface exists to accommodate, and that is the whole extent of it.

---

## Repository layout

```
├── src/main/java/com/smit/flightops/       51 files, 4,415 lines
│   ├── controller/     HTTP only — bind, validate, map to DTO, choose status code
│   ├── service/        orchestration, the transaction boundaries, the outbox drain
│   │                   and its retention pruner, EventPublisher + 2 impls
│   ├── entity/         Flight, Booking, FlightStatus, OutboxEvent — the invariants live here
│   ├── repository/     Spring Data JPA: the SELECT … FOR UPDATE query and the
│   │                   FOR UPDATE SKIP LOCKED outbox claim
│   ├── dto/            8 records: request/response types + BookingCreatedEvent (the wire contract)
│   ├── exception/      7 domain exceptions + the single @RestControllerAdvice
│   ├── security/       the 401 and 403 writers — Spring Security rejects before the
│   │                   DispatcherServlet, so @RestControllerAdvice never sees those two
│   ├── observability/  RequestIdFilter (X-Request-Id on every response, MDC, ahead of
│   │                   Spring Security), BookingMetrics (the three counters HTTP
│   │                   metrics cannot express) and OutboxMetrics (backlog and dead rows)
│   ├── validation/     @DistinctEndpoints — a custom class-level Bean Validation constraint
│   └── config/         SecurityConfig, OpenApiConfig, AwsConfig, three
│                       @ConfigurationProperties records, TimeConfig (an injected
│                       Clock), DataSeeder
├── src/main/resources/
│   ├── application.yml            profiles: default (H2), postgres, prod
│   └── db/migration/              Flyway, V1–V6 — owns the PostgreSQL schema
├── src/test/java/                 25 test classes, layered — see Tests (27 with the Lambda's)
├── contracts/                     the event schema both modules test against
├── lambda/                        separate parentless Maven module: SQS → DynamoDB consumer
├── k8s/                           6 manifests + secret.example.yaml
│   └── optional/ingress.yaml      separated because applying it provisions a billed ALB
├── events/                        SQS fixtures for `sam local invoke`
├── cluster.yaml                   eksctl cluster definition
├── template.yaml                  SAM template for the Lambda
├── Dockerfile                     multi-stage: JDK + Maven build → JRE runtime
└── .github/workflows/             build → test → ECR → EKS rollout
```

The package boundaries are the point of the layout: a DTO never reaches the repository, an entity never reaches a controller, and the only code that knows about HTTP lives in `controller/` and `exception/GlobalExceptionHandler`.

---

## Security

Two kinds of caller, one set of authorisation rules, and a deliberate refusal to let the two drift apart.

**Human-ish callers use HTTP Basic.** Two in-memory accounts: `api`, holding the authorities `SCOPE_flights:read` and `SCOPE_flights:write`, and `ops`, holding `ROLE_OPS`. **Machine callers use a bearer token**, validated as an OAuth2 resource server — but only if `spring.security.oauth2.resourceserver.jwt.issuer-uri` is set. With no issuer configured there is no `JwtDecoder` bean, and `SecurityConfig` registers the JWT support conditionally on the bean being present, so the local profile boots with Basic alone and no dead configuration.

The two meet on purpose. Spring's default `JwtGrantedAuthoritiesConverter` maps a token's `scope` claim to authorities prefixed `SCOPE_`, so a token carrying `scope: "flights:read flights:write"` arrives holding exactly the strings the `api` user is granted. **There is one rule set, and it does not know which mechanism authenticated the request** — which is the only version of this that stays correct after somebody adds a third caller.

| Path | Who gets in |
|---|---|
| `/actuator/health`, `/health/liveness`, `/health/readiness` | everyone — the kubelet has no credentials, and a probe that needs them is a probe that fails the pod on a password rotation |
| every other `/actuator/**` | `ROLE_OPS` |
| `GET /api/**` | `SCOPE_flights:read` |
| `POST`, `PATCH`, `DELETE /api/**` | `SCOPE_flights:write` |
| `GET`/`HEAD` on `/v3/api-docs**` and `/swagger-ui/**` | everyone — see [OpenAPI](#openapi) |
| anything else | `denyAll()` |

`anyRequest().denyAll()` rather than `permitAll()` or `authenticated()` is the one line worth arguing about. It means a controller added later is *unreachable* until somebody writes a rule for it. That is annoying exactly once, and the alternative is a new endpoint that is silently public on the day it ships.

**401 and 403 are written by hand, and they have to be.** `GlobalExceptionHandler` cannot answer either: Spring Security rejects the request inside a servlet filter, before `DispatcherServlet` runs, so a `@RestControllerAdvice` never sees it. Left alone, the app answers 401 with an empty body and a `WWW-Authenticate` header, and 403 with Boot's default error map — two shapes no client of this API has ever seen. `JsonAuthenticationEntryPoint` and `JsonAccessDeniedHandler` write the same `{code, message, timestamp}` envelope as everything else, sharing the container's `ObjectMapper` so the JSON is configured identically.

The difference between them is not cosmetic. **401 means "I do not know who you are"** — retry with credentials. **403 means "I know exactly who you are, and the answer is still no"** — retrying is pointless. Spring's `ExceptionTranslationFilter` picks between them on whether the authentication is anonymous.

**Passwords are stored encoded, never plaintext**, through a `DelegatingPasswordEncoder`. Every stored value carries its algorithm as a `{prefix}`: `{noop}dev-secret` locally, `{bcrypt}$2a$…` in a deployment. Three things follow. The encoder reads the prefix, so the *same* build verifies both without a flag. Rotating from bcrypt to argon2 becomes a per-user data migration instead of a flag day. And `{noop}` in the committed default is a statement rather than an accident — it says in the config file that this value is not a secret.

`csrf.disable()` and `SessionCreationPolicy.STATELESS`, because there is no session and no cookie: CSRF is an attack on ambient credentials a browser attaches automatically, and there are none here. Leaving CSRF on for a stateless API makes every `POST` fail with a missing token, which is how it usually gets switched off — for the wrong reason.

**What this is not.** Two hardcoded users in memory is not an identity provider. A real deployment points `issuer-uri` at Cognito, Okta or Entra and deletes the `InMemoryUserDetailsManager`. The structure is there so that swap is a config change and not a rewrite; the user store is not the part to copy.

---

## API

| Method | Path | Requires | Success | Failures |
|---|---|---|---|---|
| `GET` | `/api/v1/flights/{flightNumber}` | `flights:read` | 200 | 404 |
| `GET` | `/api/v1/flights?origin=&destination=&page=&size=&sort=` | `flights:read` | 200 (paginated) | 400 |
| `POST` | `/api/v1/flights` | `flights:write` | 201 + `Location` | 400, 409 |
| `PATCH` | `/api/v1/flights/{flightNumber}/status` | `flights:write` | 200 | 400, 404, 409 |
| `DELETE` | `/api/v1/flights/{flightNumber}` | `flights:write` | 204 | 404 |
| `POST` | `/api/v1/bookings` | `flights:write` | 201 + `Location` | 400, 404, 409, 503 |
| `GET` | `/api/v1/bookings/{bookingId}` | `flights:read` | 200 | 400, 404 |
| `GET` | `/api/v1/bookings?flightNumber=&page=&size=` | `flights:read` | 200 (paginated) | 400 |
| `DELETE` | `/api/v1/bookings/{bookingId}` | `flights:write` | 200 | 404 |
| `GET` | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | — | 200 | — |
| `GET` | `/actuator/metrics`, `/actuator/prometheus` | `ROLE_OPS` | 200 | — |

Every row above also answers **401** without credentials and **403** with the wrong ones.

Every error has one JSON shape — `{code, message, timestamp}`, or `{code, fieldErrors, timestamp}` for validation — produced by a single `@RestControllerAdvice`. No controller contains a `try`/`catch`.

**Both `Location` headers are built from the returned DTO, not from the request**, because `FlightService` normalises flight numbers (`trim` + upper-case). `POST /api/v1/flights` with `{"flightNumber":" ua999 "}` answers `Location: /api/v1/flights/UA999` — the only form that resolves. Verified by following both headers with `curl`: 200 each. [Why that verification exists](#the-second-bug-a-passing-suite-did-not-catch).

### OpenAPI

The document is generated from the controllers and served at
**[`/v3/api-docs`](http://localhost:8080/v3/api-docs)**, with the Swagger UI at
**[`/swagger-ui.html`](http://localhost:8080/swagger-ui.html)**. Both are reachable without
credentials; every endpoint they describe still answers 401 without them. A description of an
endpoint is not a credential for it, and the alternative — a documented API that needs a shared
password to read about — is how an API ends up documented in a wiki instead.

```bash
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'      # no credentials needed
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/flights   # 401
```

Three decisions in it:

- **The permit is on `GET` and `HEAD` only.** A `POST` to a docs path is not a documented
  operation, so it falls to `denyAll()` rather than reaching a handler that does not exist.
- **The paths are listed, not wildcarded.** `/v3/**` would have made the next `/v3/anything`
  public by accident, and this is the one list in `SecurityConfig` where a mistake is not caught
  by a failing test.
- **The security requirement is declared once, on the document.** Per-operation annotations are
  the same annotation on every method, and the one somebody forgets is the endpoint that gets
  documented as public. `bearerAuth` is deliberately absent: that half of `SecurityConfig` only
  activates when an issuer is configured, and advertising an authentication method the running
  instance will reject is worse than advertising none.

`SWAGGER_UI_ENABLED=false` removes the browser console and leaves the machine-readable document,
because the two carry different risk: one is JSON a client generator reads, the other is a page
that sends live requests.

**`OpenApiTest` compares the document against a real response.** Generated documentation does not
fail by disappearing; it fails by drifting while still rendering perfectly. So the test that
matters asserts that the documented page schema — `{content, page}` — has the same top-level keys
as an actual `GET /api/v1/flights`, rather than the same keys as an expectation written by the
same hand that wrote the schema.

### The 20 error codes

| Code | Status | Meaning |
|---|---|---|
| `FLIGHT_NOT_FOUND` | 404 | no such flight number |
| `BOOKING_NOT_FOUND` | 404 | no such booking id — its own code, so a 404 from a booking URL does not claim the *flight* is missing |
| `INSUFFICIENT_SEATS` | 409 | fewer seats remain than requested — **retrying with fewer seats can succeed** |
| `FLIGHT_NOT_BOOKABLE` | 409 | flight is `CANCELLED`/`DEPARTED`/`ARRIVED` — **retrying can never succeed** |
| `DUPLICATE_FLIGHT` | 409 | flight number already exists |
| `CONCURRENT_MODIFICATION` | 409 | `@Version` rejected a stale write |
| `DUPLICATE_REQUEST` | 409 | two flight-creation requests raced on `flight_number`; the DB constraint arbitrated — a raced *booking* no longer lands here, see below |
| `IDEMPOTENCY_KEY_REUSED` | 409 | the key is known, and it was first used for a **different** request. Not a replay — a client bug, said out loud |
| `ILLEGAL_STATUS_TRANSITION` | 409 | the flight cannot go from its current status to the requested one (`BOARDING → ARRIVED`, anything out of `ARRIVED`) |
| `LOCK_TIMEOUT` | 503 + `Retry-After` | the `SELECT … FOR UPDATE` waited out `lock_timeout`. 503, not 500: the request was fine and the answer is "try again", which is a statement about load |
| `UNKNOWN_SORT_PROPERTY` | 400 | `?sort=nonsense` — Spring Data could not resolve the property. The client sent a bad parameter; the server did not break |
| `UNAUTHENTICATED` | 401 | no credentials, or credentials that do not verify. Written by `JsonAuthenticationEntryPoint`, not by the advice |
| `FORBIDDEN` | 403 | authenticated, and lacking the authority this path needs. Written by `JsonAccessDeniedHandler` |
| `VALIDATION_FAILED` | 400 | Bean Validation, reported per field — including the class-level `@DistinctEndpoints`, which refuses a flight from EWR to EWR |
| `MALFORMED_REQUEST` | 400 | unreadable body, unknown enum constant, bad path variable, missing query param |
| `RESOURCE_NOT_FOUND` | 404 | unmapped path |
| `METHOD_NOT_ALLOWED` | 405 | wrong verb |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | wrong `Content-Type` |
| `REQUEST_REJECTED` | 4xx | any other Spring MVC client error |
| `INTERNAL_ERROR` | 500 | last resort — stack trace logged, never returned |

Two deliberate details in there:

**Spring MVC's own errors are mapped, not swallowed.** A catch-all `@ExceptionHandler(Exception.class)` turns a wrong HTTP verb into a 500. Most Spring MVC exceptions implement `org.springframework.web.ErrorResponse` and already carry the status Spring decided on, so that status is read back off the exception rather than re-derived. (Handling it needs `@ExceptionHandler({ServletException.class, ErrorResponseException.class})` plus an `instanceof` pattern match — `ErrorResponse` is an interface, and `@ExceptionHandler(ErrorResponse.class)` does not compile.)

**Framework exception text is never echoed to the client.** Jackson's parse failures name internal classes and quote the payload back; Hibernate's name tables, columns and constraints. Both are free reconnaissance for anyone probing the API, so the response carries a fixed generic string and the detail goes to the log at WARN. Messages from the project's *own* exceptions (`FlightNotFoundException`, `InsufficientSeatsException`, `FlightNotBookableException`, and domain `IllegalArgumentException`s) *are* passed through — they were written to be read by a client and they leak nothing.

---

## The hard problem: not overselling the last seat

Two requests both read "3 seats available", both book 2, and you have sold 5. Four mechanisms stop that:

1. **`@Version` optimistic locking** on `Flight` — a conflicting concurrent commit throws `OptimisticLockingFailureException` (→ 409) instead of quietly overwriting.
2. **Pessimistic `SELECT … FOR UPDATE`** on the booking path only, via `findByFlightNumberForUpdate`. Last-seat contention is genuinely high there, so serialising is worth the cost; read paths stay lock-free.
3. **An idempotency key with a unique DB constraint.** The service checks for a replay first and returns the original booking. If two concurrent requests both pass that check — an application-level check can always be raced — the constraint decides who writes first, in `BookingWriter.insertNewBooking`. **The loser does not get 409.** `BookingService.book` catches the constraint violation and re-reads the winner's row in a fresh transaction (`BookingWriter.recoverReplay`), so both callers get the same 201 and the same booking. Found and fixed after a concurrency test showed the two Javadocs describing this path disagreed with each other — one claimed every replay gets 201, the other said a race loser gets 409 and called that "the same answer." It wasn't; now it is.

   **A replay is the same request twice, and the code now checks that.** Each booking stores a SHA-256 fingerprint of the request that created it. A key arriving with a payload that does not match gets `409 IDEMPOTENCY_KEY_REUSED` rather than somebody else's reservation. The check is in *both* places — the pre-flight read in `BookingService.book` and the post-constraint recovery in `BookingWriter.recoverReplay` — because under a genuine race none of the callers sees any of the others in the pre-flight read, so all of them proceed to the insert and the losers land in recovery. Putting the comparison in only the first place is the version of the fix that looks complete and is not.
4. **The invariants live on the entity, not the service.** `Flight.reserveSeats` is the only way seats move in production, so no caller can forget the rules — including a caller written later by someone else. `Flight.releaseSeats` is the other direction, and it now has a caller: `DELETE /api/v1/bookings/{id}` cancels a booking and returns its seats. Cancelling twice is a no-op that still answers 200, because a client retrying a cancel it already succeeded at should not be told it failed.

The fourth is the one worth dwelling on, because violating it is what produced the defect below.

### The bug that a passing test suite did not catch

`reserveSeats` guarded the seat *count*. It did not guard the flight *status*. Three `curl` calls against a running instance:

```
DELETE /api/v1/flights/UA123      → 204, status CANCELLED
POST   /api/v1/bookings (4 seats) → 201            ← should have been refused
GET    /api/v1/flights/UA123      → availableSeats 180 → 176
```

Seats sold on a cancelled flight — and each sale published a `BookingCreated` event, so under `app.events.publisher: sqs` the bad data would have propagated to a second store that has no way to know the flight was cancelled. Every test passed the entire time, because every test asserted a rule that had already been thought of.

The fix puts the missing invariant next to the existing one:

```java
public void reserveSeats(int count) {
    if (count <= 0) throw new IllegalArgumentException("count must be positive");
    if (!status.isBookable())                                    // ← the fix
        throw new FlightNotBookableException(flightNumber, status);
    if (availableSeats < count)
        throw new InsufficientSeatsException(flightNumber, count, availableSeats);
    this.availableSeats -= count;
}
```

Four deliberate choices in five lines:

- **Bookability is decided on the enum, not in `BookingService`.** Written as `status != CANCELLED` in the service it is one caller's opinion, and the second caller — a bulk import, an admin endpoint, a message consumer — forgets it. `FlightStatus.isBookable()` is an **exhaustive `switch` with no `default`**, which is the point: adding a constant to the enum stops the build until somebody classifies it. `default -> true` would let the next status silently inherit an answer nobody chose.

- **A new exception type, not a reuse of `InsufficientSeatsException`.** That one's message is "Flight X has N seat(s) available, M requested". On a cancelled flight with 176 free seats it would be actively false, and it would send the client into a retry-with-fewer-seats loop that can never terminate. Same 409 status, different code, different advice.

- **The status check runs *before* the seat check**, so a cancelled full flight says "CANCELLED" rather than "not enough seats". Ordering two guards is not usually a decision worth thinking about; here it changes what the client does next.

- **`releaseSeats` is deliberately NOT status-guarded, and `cancel()` stays idempotent.** Refunds happen precisely on the flights that were cancelled, so guarding the release path would break the case it exists for. And making `cancel()` throw on an already-cancelled flight would make `DELETE` non-idempotent, which is a worse bug than the one it prevents. Tests assert both, so neither gets "helpfully" symmetrised later.

**The transition graph is now enforced too.** `updateStatus` used to permit any status → any status, which meant a `PATCH` back to `SCHEDULED` would un-cancel a cancelled flight and put its seats back on sale. `FlightStatus.canTransitionTo` is the graph: `ARRIVED` and `CANCELLED` are terminal, `DEPARTED` can only become `ARRIVED`, and a status may always transition to itself so that a retried `PATCH` answers 200 rather than 409. Same exhaustive-`switch`-with-no-`default` shape as `isBookable`, for the same reason — a new constant must not inherit a policy nobody chose.

---

## The outbox: why the event is a database row first

The booking used to be written and the event published in the same method. That is the ordering problem every service with a database and a queue eventually meets, and **no ordering of the two is atomic**:

- **Publish, then commit** — the transaction rolls back and a `BookingCreated` event now exists for a booking that does not. The Lambda projects a phantom reservation into DynamoDB and nothing will ever correct it.
- **Commit, then publish** — the process is killed between the two and the booking exists with no event. Seats are gone from the primary store and the read model never hears about it.

Both are silent. Neither shows up in a test that does not kill the process at the exact wrong moment.

**The outbox makes the event part of the transaction.** `OutboxWriter.recordBookingCreated` serialises the event and does an `INSERT` into `outbox_events` — in the booking's transaction, on the same connection. If the booking commits, the event is committed with it. If it rolls back, the event goes with it. There is no window.

A separate `@Scheduled` drain does the network call afterwards:

```sql
SELECT * FROM outbox_events
 WHERE published_at IS NULL
   AND attempts < :maxAttempts
 ORDER BY id
 LIMIT :batchSize
   FOR UPDATE SKIP LOCKED
```

Five decisions in that query and the loop around it, each of which is a bug if taken the other way:

- **`FOR UPDATE SKIP LOCKED`** is what makes the drain safe on every replica at once with no leader election and no distributed lock. Each poller claims rows nobody else holds and steps over the rest; without `SKIP LOCKED` the replicas queue behind each other and the drain runs at single-writer speed.
- **Publish, *then* mark published** — at-least-once, not at-most-once. Marking first and crashing before the send loses the event permanently; sending first and crashing before the mark sends it twice. The consumer's conditional DynamoDB write already absorbs a duplicate, so the recoverable failure is the one to choose.
- **`@Transactional(propagation = MANDATORY)` on the writer.** Without it, calling `recordBookingCreated` outside a transaction would work perfectly — and silently discard the entire atomicity guarantee this exists for. `MANDATORY` turns that mistake into a startup-shaped failure instead of a correctness one nobody notices.
- **A `try`/`catch` per row, not around the loop.** Letting one failure propagate would roll back the `markPublished` on rows whose payloads had *already left the process*, turning one bad event into N duplicates on the next drain.
- **`AND attempts < :maxAttempts`** is an availability bound, not tidiness. The claim is `ORDER BY id`, so an event the transport structurally rejects is retried **first** on every tick and spends the whole batch failing while live events queue behind it: one malformed row is a total publishing outage that waiting never resolves. After ten attempts the row drops out of the claim, `outbox_dead` goes above zero, and a WARN names the id and the booking. Bringing it back is deliberate and manual — `UPDATE outbox_events SET attempts = 0 WHERE id = ?` — which is the statement `OutboxPoisonRowTest` runs, so the sentence an operator will paste is the one that is tested.

`fixedDelay`, not `fixedRate`: `fixedRate` measures from the previous *start*, so a drain slower than the interval queues invocations behind itself. `fixedDelay` measures from the previous finish.

**The trace context is captured by the writer, not by the poller.** `OutboxWriter` asks Micrometer's `Propagator` to inject the current span into the row as a `traceparent` (`V6__outbox_traceparent.sql`), and `OutboxPublisher` sends that stored value as an SQS message attribute. Reading the current span in the poller instead compiles and looks equivalent: it is not, because the poller runs on a scheduler thread minutes later with no relationship to the request, so every event drained in one tick would share one meaningless trace and the request a support engineer is actually looking for would appear nowhere. `OutboxTest.theDrainDoesNotOverwriteTheTrace` drains inside a *different* span to keep that regression red. The header is omitted entirely when there is no trace — a fabricated id is worse than a missing one, because a consumer cannot tell that it is fake. The sampled bit inside it follows `management.tracing.sampling.probability`, which Boot defaults to 0.1, so most traceparents on the queue are real ids marked not-sampled; that is the first thing to check before concluding a collector is dropping spans.

**Published rows are deleted on a schedule.** `OutboxPruner` removes rows published longer than `app.outbox.retention` ago (7 days), in batches of 1,000, each in its own transaction, at most 50 batches per run. Every bound there is about the first run rather than the steady state: `DELETE FROM outbox_events WHERE published_at < :cutoff` is one line shorter and, against a table that has been growing for a year, takes row locks over the whole range, writes a WAL burst large enough to stall replication and shuts the poller out of the table until it commits. Unpublished rows are never touched at any age — an undelivered event is a backlog, not rubbish.

**What this buys and what it costs.** Exactly-once *recording*, at-least-once *delivery*, and a bounded queue you can query — `SELECT count(*) FROM outbox_events WHERE published_at IS NULL` is a lag metric and an alert. What it costs is a table, a poller, up to one poll interval of latency, and a retention job to keep the table from growing forever. For a booking event that is worth it; for a fire-and-forget metric it would not be.


---

## Observability

Every log line carries four ids:

```
[flight-ops-service,71abb349fd0501cea2de8343a56c41aa,880c5a6a004ad1ab,smoke-test-1]
 service             traceId                          spanId           requestId
```

`traceId` and `spanId` come from OpenTelemetry via `spring-boot-starter-opentelemetry`. `requestId` comes from [`observability/RequestIdFilter`](src/main/java/com/smit/flightops/observability/RequestIdFilter.java), and it is the one that matters to a caller: it is returned on **every** response as `X-Request-Id`, so a support conversation can start with an id the user read off their own screen instead of "it failed around three o'clock".

```bash
curl -si -u api:dev-secret -H 'X-Request-Id: ticket-4471' \
  -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Smit","seats":2,"idempotencyKey":"k1"}' \
  | grep -i x-request-id
# X-Request-Id: ticket-4471
```

Three details in that filter are worth a sentence each.

- **It runs at `HIGHEST_PRECEDENCE`, ahead of Spring Security.** A 401 is produced by `ExceptionTranslationFilter` before `DispatcherServlet` is ever reached. A filter ordered after the security chain would leave exactly the responses people ring up about — "my credentials stopped working" — with no id on them. `SecurityRulesTest.everyResponseCarriesARequestId` asserts the header on a 401, on a 403 and on an echoed inbound value.
- **An inbound id is validated, not trusted.** It lands in a log line *and* in a response header, so an unchecked value is two injection sinks: a `\r\n` forges a second log entry or a second header, and a megabyte of text makes every line for that request a megabyte long. `^[A-Za-z0-9._:-]{1,128}$` passes UUIDs, ULIDs, W3C trace ids and `service-1234`; anything else is silently replaced, because a malformed diagnostic header is not a reason to fail somebody's booking.
- **It is not in the error body.** `ErrorResponse` is a published contract and the id is already in a header on the same response.

### Metrics

`/actuator/prometheus` (requires `ops` credentials) carries the Micrometer defaults plus seven meters that the HTTP metrics cannot express. Three are about bookings, in [`observability/BookingMetrics`](src/main/java/com/smit/flightops/observability/BookingMetrics.java):

| Series | Why it is not redundant with `http_server_requests` |
|---|---|
| `bookings_booked_total{outcome="created"\|"replayed"}` | A replay and a real booking are both **201 on the same URI**. This is the only thing that answers "are we selling seats, or is a client stuck in a retry loop?" |
| `bookings_cancelled_total{outcome="cancelled"\|"already_cancelled"}` | A repeated `DELETE` returns 200 and releases nothing, by design. `already_cancelled` climbing alone means a client thinks its cancellations are not sticking. |
| `bookings_lock_timeout_total` | 503s are in the HTTP metrics, mixed with every other cause. This one names the specific failure — somebody held the flight row past `lock_timeout` — and it is the leading indicator for the whole write path stalling. |

Four more are about the outbox, in [`observability/OutboxMetrics`](src/main/java/com/smit/flightops/observability/OutboxMetrics.java). The outbox's whole advantage over a direct send is that the backlog is a table you can query, which is only true if something queries it:

| Series | What it answers |
|---|---|
| `outbox_pending` | Unpublished and still retryable. A rising line is publisher lag, and it is the alert that catches an SQS outage before a consumer notices missing events. |
| `outbox_dead` | Unpublished and out of attempts. **This is the one to page on at `> 0`**: unlike pending it does not recover on its own, and it means a booking has an event that will never be sent until somebody re-drives the row. |
| `outbox_publish_total{result="success"\|"failure"\|"exhausted"}` | The transport's health. The absolute failure rate shows a partial outage that `pending` hides while the backlog still drains faster than it grows. |
| `outbox_pruned_total` | Rows deleted by the retention job. Flat at zero while the table grows is otherwise a completely silent failure. |

Both gauges query the database on the scrape thread, so both return `NaN` rather than throwing. A gauge function that throws takes the **whole** `/actuator/prometheus` response with it — so a database blip would remove every unrelated metric at exactly the moment they are most wanted, and the graph would show a hole where a problem should be.

Three things here were wrong when first written, and all three are the sort that ship green:

- The meter was called `bookings.created`. It exported as **`bookings_total`** — `_created` is a reserved suffix in OpenMetrics, so the Prometheus client strips it before appending `_total`. No warning, no error, a meter under a name no dashboard would query. `BookingMetricsTest.exportedNamesSurviveTheTripThroughPrometheus` scrapes a real `PrometheusMeterRegistry`, because a `SimpleMeterRegistry` stores the name verbatim and would have passed for any name at all.
- Every counter is registered in the constructor rather than on first increment. A series that does not exist yet returns *no data* rather than zero, and most alerting rules treat no-data as neither firing nor resolved — so the alert written to catch the first lock timeout would have been silent for exactly the first lock timeout.
- Two counters shared a meter name with different descriptions. The exporter prints one `# HELP` line per name, so whichever registered last described both series — the metric was right and the documentation attached to it was wrong, which is the harder of the two to notice. One description per meter name, as a constant.

### Tracing, and what is deliberately off

`spring-boot-starter-opentelemetry` has two halves with **opposite defaults**, which is worth knowing before deploying it.

- **Traces** are opt-in: the exporter starts only when `management.opentelemetry.tracing.export.otlp.endpoint` is set. Unset, the ids are still generated and still reach the logs, which is the whole benefit on a single-cluster deployment with no collector.
- **Metrics** are opt-out. The starter brings `micrometer-registry-otlp`, a *push* registry that defaults to `http://localhost:4318/v1/metrics` and begins publishing every 60 seconds with nothing listening. The first run after adding the starter logged exactly that. `application.yml` sets `management.otlp.metrics.export.enabled: ${OTLP_METRICS_ENABLED:false}`; this service is scraped, not pushed.

### Structured logs

The `prod` profile sets `logging.structured.format.console: ecs` — one JSON object per line in Elastic Common Schema, with every MDC entry as a real field:

```json
{"@timestamp":"2026-09-22T14:57:03.694709Z","log":{"level":"INFO","logger":"com.smit.flightops.service.BookingWriter"},
 "message":"Booked 2 seat(s) on UA123 (booking 1, 178 seats left)",
 "traceId":"b5395946255efbf8007a30ba380219d9","spanId":"216f49a1ee247a74","requestId":"ecs-check-1","ecs":{"version":"8.11"}}
```

`requestId` is a term query rather than a substring search. It is off by default because the default profile is somebody reading the output with their eyes; `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs ./mvnw spring-boot:run` turns it on anywhere.


---

## Tests

```bash
./mvnw clean verify                       # 179 tests: 174 run, 5 skipped, 0 failures
./mvnw -f lambda/pom.xml clean verify     # 18 tests, 0 failures
```

| Layer | Tests | Tooling |
|---|---|---|
| Domain entity | 12 | plain JUnit — no Spring, no database. A domain rule should be provable without either. |
| Service | 24 | `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `@Captor` — split across `BookingServiceTest` (orchestration), `BookingWriterTest` (the write path), `FlightServiceTest`, and `SqsEventPublisherTest` for what actually goes on the wire |
| Web slice | 25 | `@WebMvcTest` + `@MockitoBean` — status codes, `Location` headers, error JSON |
| Repository slice | 13 | `@DataJpaTest` + `TestEntityManager` — derived queries, JPQL, `JOIN FETCH`, constraints |
| Full context (H2) | 63 | `@SpringBootTest` — the idempotency guarantee end to end (two 10-thread races on one key), the authorisation rules against the real filter chain, the outbox including its trace capture, the attempt ceiling and the retention pruner against a real database, the published OpenAPI document compared against a real response, the lock timeout, the error contract, and a lazy-loading regression with no mocking anywhere in the chain |
| Event contract | 11 | one producer-side class and one consumer-side class, both asserting against `contracts/booking-created-v1.json` |
| Lambda handler | 12 | separate module — batch parsing, partial batch failure, conditional write |
| Configuration binding | 15 | plain JUnit driving a standalone Jakarta `Validator` and Boot's `Binder` — proves an unresolved `${...}` placeholder is rejected at startup rather than binding as a literal, and that every outbox bound is enforced and every default is actually wired |
| Architecture | 9 | ArchUnit over `target/classes` — the layering, no field injection, no `@Transactional` outside `service/`, no wall-clock reads outside `entity/`. Each rule was checked against a deliberate violation before being committed |
| Observability | 8 | the request-id filter against a hostile inbound header, and the meters scraped through a real `PrometheusMeterRegistry` rather than a `SimpleMeterRegistry` that would accept any name |
| **Run** | **192** | **0 failures** (12 + 24 + 25 + 13 + 63 + 11 + 12 + 15 + 9 + 8) |
| PostgreSQL integration | 5 | `@Testcontainers(disabledWithoutDocker = true)` — skipped without a container runtime |

197 tests exist across the two modules; 192 run without Docker, 5 skip. CI runs all 197 and they pass — the runner has Docker, so it is the only place the real PostgreSQL path (Flyway + `ddl-auto=validate` + `SELECT FOR UPDATE` under 20-way contention, and a 20-thread idempotency-key race) gets exercised. The surefire summary there reads `Tests run: 179, Failures: 0, Errors: 0, Skipped: 0` for this module and `Tests run: 18 … Skipped: 0` for the Lambda. `Skipped: 0` rather than `Skipped: 5` is the part worth reading: it is the difference between the integration tests passing and the integration tests quietly opting out, and a green build alone does not distinguish the two.

**The `@WebMvcTest` slices run with `addFilters = false`, and that is deliberate.** A slice does not load `SecurityConfig` — it is a `@Configuration` class, not a controller, so the slice filter excludes it — and what Boot substitutes is its *own* default chain. Leaving the filters on would therefore have every controller test authenticate against rules that are not this application's rules, and pass. That is worse than no coverage: it reads as though authorisation is tested. The real rules are tested once, properly, against the real `SecurityConfig` with real credentials and the real 401/403 bodies, in `SecurityRulesTest`.

**The contract tests do not share a jar, on purpose.** A shared module would make the producer and consumer deploy together, which is the coupling a queue exists to remove. Instead both modules read `contracts/booking-created-v1.json` and assert against it: the producer serialises a real event and asserts the field set matches *exactly* — set equality, so an **added** field fails too, not just a removed one — and the consumer deserialises the same file and asserts every field it depends on survives the round trip. The consumer also pins `FAIL_ON_UNKNOWN_PROPERTIES` as disabled, because that setting is the entire reason an additive producer change is safe. Both directions were falsified before being trusted: adding a field to the producer turns the producer build red with the exact field-set diff, and renaming a field turns the consumer build red.

`@MockitoBean`, not `@MockBean` — the latter is deprecated as of Boot 3.4 and removed in 4.0.

### The second bug a passing suite did not catch

`POST /api/v1/bookings` answered `201 Location: /api/v1/bookings/1` — and there was no `GET /api/v1/bookings/{id}`, so that URL returned 404. A green suite had covered it the whole time, because the test asserted the header's *string value* and stopped:

```java
.andExpect(header().string("Location", "/api/v1/bookings/1"))   // proves the text, nothing else
```

The replacement follows the header instead of trusting it, which is the only version that could have failed:

```java
String location = mockMvc.perform(post("/api/v1/bookings")...)
        .andReturn().getResponse().getHeader("Location");
mockMvc.perform(get(location)).andExpect(status().isOk());       // this would have 404'd
```

Same lesson as the cancelled-flight bug, in a different place: **a test that asserts the mechanism passes; a test that asserts the consequence catches things.** Both defects here were found by probing a running instance, not by reading code, and both are now pinned by tests that follow through to the outcome.

**The build logs alarming things that are tests passing.** H2 `SqlExceptionHelper` ERRORs about `CONSTRAINT_INDEX_A ON PUBLIC.BOOKINGS(IDEMPOTENCY_KEY)`, a `GlobalExceptionHandler` WARN naming `uk_bookings_idempotency_key`, and an enum parse failure for a status of `TELEPORTED` are the duplicate-key and malformed-request tests doing their job. Don't "fix" them.

### The third bug: a concurrency test found a contract two Javadocs disagreed about

`BookingController`'s Javadoc promised every replay of the same idempotency key gets `201`. `GlobalExceptionHandler`'s Javadoc promised a race loser gets `409`. Both read as confident, and they contradicted each other — no single request can tell you which one is true, because a single request never races itself. Ten threads hitting the same key did:

```
4 callers -> 201
6 callers -> 409
```

— for one logical booking. The controller's Javadoc was the one that was false.

The fix splits the write into two transactions on a second bean, `BookingWriter`, so the recovery step can run in a fresh transaction instead of one Postgres has already marked aborted:

```java
try {
    return bookingWriter.insertNewBooking(request);
} catch (DataIntegrityViolationException e) {
    return bookingWriter.recoverReplay(request.idempotencyKey());   // ← the fix: 201, not 409
}
```

`BookingService.book` is deliberately **not** `@Transactional` any more — both transaction boundaries live on `BookingWriter`, called through Spring's proxy rather than through `this.`, which is what makes the annotations apply at all. That split *is* the fix: while `book` was `@Transactional`, the recovery read ran inside the transaction PostgreSQL had already marked aborted and got `current transaction is aborted` instead of the winner's row. Now that `book` sits outside any transaction, Spring has rolled that one back before the catch block runs, so `REQUIRES_NEW` on `recoverReplay` is insurance for the day `book` becomes transactional again rather than the thing carrying the fix. `BookingIdempotencyTest.racingTenCallersOnTheSameKeyAllGetTheSameBooking` pins the fix: ten threads, one idempotency key, every caller gets the same booking id back, exactly one row exists, exactly one seat is debited.

Same lesson as the first two bugs, from a new angle: **some contracts are only false under concurrency**, so the test that catches them has to actually create the race, not restate the single-request behaviour twice.

### The fourth bug: the endpoint the second bug's fix pointed at was itself broken

Bug 2's fix made `GET /api/v1/bookings/{id}` something a real request would follow through to. Following it far enough exposed that the endpoint threw `LazyInitializationException` on every real lookup — sequential, no concurrency involved:

```
org.hibernate.LazyInitializationException: Could not initialize proxy [Flight#4] - no session
```

`Booking.flight` is `@ManyToOne(fetch = LAZY)`. `BookingDto.from` dereferences it. `BookingService.findById` had no `@Transactional` and called the plain inherited `JpaRepository.findById` — not the `JOIN FETCH` query `findByIdempotencyKey` and `findByFlightNumber` were given when the third bug was fixed — so the repository's own short-lived session had already closed by the time the DTO mapping ran. `BookingControllerTest.locationHeaderResolves()` mocks `bookingService.findById(...)` directly, which is exactly why a passing suite never caught it: that test proves the controller calls the method, not that the method works.

```java
@Transactional(readOnly = true)               // ← the fix
public BookingDto findById(Long bookingId) {
```

`BookingFindByIdLazyLoadingTest` pins it, and pins it properly: no mocking anywhere in the chain, and deliberately no `@Transactional` on the test class itself — a test-managed transaction would keep the session open for the whole test and let this exact bug pass silently even with the fix reverted.

---

## Lambda module

Separate Maven module, separate lifecycle, deployed by SAM. Consumes `booking-events` from SQS and writes to DynamoDB.

- **Partial batch response.** `FunctionResponseTypes: [ReportBatchItemFailures]` — the handler returns only the failed message IDs, so one poison message in a batch of 10 doesn't redeliver the 9 that succeeded.
- **Idempotent writes.** `conditionExpression("attribute_not_exists(bookingId)")`. SQS is at-least-once; the consumer has to be able to see the same message twice.
- **Composite sort key**: `timestamp#bookingId`, not `timestamp` alone. Two bookings on the same flight in the same instant would otherwise collide on the key and one would be lost. There is a fixture (`events/sqs-same-instant.json`) and a test for exactly that.
- **The timestamp in that key is fixed-width, and it has to be.** DynamoDB sorts range keys as bytes, so lexicographic order is only chronological order if every value is the same length. `Instant.toString()` is not: it prints 0, 3, 6 or 9 fractional digits depending on the value, and `…:00Z` sorts *after* `…:00.000001Z` because `Z` is `0x5A` and `.` is `0x2E`. Both sides format with `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` instead, and a test asserts the `#` lands at index 27 every time — which is the assertion that fails if anyone changes the pattern on one side only.
- **`DynamoDbClient` behind an initialization-on-demand holder**, so the SDK client is created once per execution environment and reused across warm invocations rather than per request.
- **No framework.** A plain `RequestHandler`, not Spring Cloud Function — the handler does one thing, and a container to start is a container to start on every cold invocation.
- **18 tests, 6 of them the consumer half of the contract.** The producer and consumer never share a jar — that would make them deploy together, which is the coupling a queue exists to remove — so both read `contracts/booking-created-v1.json` and assert against it independently. See [Tests](#tests).

### The deployment package

`software.amazon.awssdk:dynamodb` drags in **three** HTTP clients transitively — `apache-client`, `apache5-client` and `netty-nio-client` — each registering itself through `META-INF/services/software.amazon.awssdk.http.SdkHttpService`. All three are excluded and replaced with `url-connection-client`, which is what AWS documents for Lambda: 34 KB, zero transitive dependencies, no connection pool to keep warm.

| | Jar | Classes | Sync HTTP providers |
|---|---|---|---|
| Before | ~18 MB | — | 2 |
| After | **10.3 MB** | 5,788 | 1 (`UrlConnectionSdkHttpService`) |

Belt and braces: exactly one provider is on the classpath *and* the holder names `UrlConnectionHttpClient` explicitly, so the choice does not depend on `ServiceLoader` ordering. Trade-off: no HTTP/2, no tunable pool, synchronous only. All three are fine for a Lambda that makes one round trip per message.

**Do not exclude `joda-time`** — it looks like 247 dead classes and 644 KB of waste, and removing it turns the tests red with `ClassNotFoundException: org.joda.time.DateTime`. `EventLoader` builds its deserializer through `LambdaEventSerializers`, which registers a Jackson Joda module across the *entire* event model regardless of which event type is being loaded. There is a comment in `lambda/pom.xml` saying so; it is there because the exclusion was tried once.

**Cold start is not measured.** No figure for it appears anywhere in this repository, deliberately: measuring one requires a deployed version, and an unmeasured number in a README is a number somebody will check.

**SnapStart is deliberately off**, and `template.yaml` explains why rather than leaving it as an unexplained omission.

---

## Container and Kubernetes

> ⚠️ **Nothing in this section has been executed** — see [Project status](#project-status). The manifests and the Dockerfile are reviewed, not applied.

```bash
docker build -t flight-ops-service:1.0.0 .
kubectl apply -f k8s/          # note: -f, NOT -R
```

`k8s/` holds six manifests (`namespace`, `configmap`, `deployment`, `service`, `hpa`, `pdb`) plus `secret.example.yaml`, a placeholder template. `apply -f k8s/` **does** sweep that one up — it is in the directory like everything else — so copy it to `k8s/secret.yaml` (gitignored), put the real password there, and apply that file explicitly afterwards; the manifest's own header explains why the apply ordering makes the directory sweep harmless. **`k8s/optional/ingress.yaml` is in a subdirectory on purpose**: applying it provisions an AWS ALB that bills continuously, so it takes a deliberate second command rather than being swept up by `kubectl apply -f k8s/`.

What the manifests get right:

- **Three probes with distinct jobs.** `startupProbe` covers slow JVM start so the liveness probe's timeout can stay tight; `readinessProbe` → `/actuator/health/readiness` gates traffic; `livenessProbe` → `/actuator/health/liveness` restarts a wedged container. One probe doing all three jobs means either slow failure detection or a boot loop.
- **`maxUnavailable: 0`** on the rolling update, plus a `PodDisruptionBudget` for voluntary disruptions — a rollout and a node drain are different events and need separate answers.
- **`-XX:MaxRAMPercentage`, not `-Xmx`.** The JVM reads the cgroup limit, so the heap tracks the container's memory limit instead of drifting out of sync with it whenever that limit changes. This matters because the two limits fail differently: exceeding the CPU limit gets the container *throttled*, while exceeding the memory limit gets it *OOMKilled*, because memory is not a compressible resource.
- **`preStop` hook + `terminationGracePeriodSeconds: 45` + `server.shutdown: graceful`.** All three are needed and the numbers are related: 5s `preStop` (so Endpoints propagate to kube-proxy before the container stops accepting) + 30s `spring.lifecycle.timeout-per-shutdown-phase` (draining in-flight requests) = 35s, under the 45s grace period. Get that inequality backwards and the kubelet SIGKILLs mid-request. The 30s is pinned explicitly in `application.yml` rather than left as Spring's default, precisely because the manifest's comment does arithmetic on it.
- **The Dockerfile's `ENTRYPOINT` uses `exec`.** `sh -c "exec java …"` makes the JVM PID 1, so it receives SIGTERM directly. Without `exec`, the shell is PID 1, does not forward signals, and every graceful-shutdown setting above is silently dead — the pod just gets SIGKILLed at the grace period.
- **Non-root numeric UID, `capabilities: drop: ["ALL"]`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault`.** `runAsUser: 1001` is numeric and the Dockerfile says `USER 1001:1001` for the same reason: the kubelet verifies non-root by reading the UID out of the image config, and a *username* cannot be verified without resolving the image's `/etc/passwd`. Give it a name and the pod fails `CreateContainerConfigError`. Both halves have to agree.
- **`readOnlyRootFilesystem: true`, with a 64 MiB tmpfs at `/tmp`.** This was the one hardening box left unticked, on the grounds that the JVM writes to `/tmp` — Tomcat's work directory and `hsperfdata`, which is what lets `jcmd` and `jstack` see the process. Both want `/tmp` and neither wants anything else, so the fix is an `emptyDir` mounted there and nothing more. What it buys: code execution inside the container cannot write a binary to disk, cannot overwrite the application jar, and cannot leave anything behind that survives a restart. `medium: Memory` keeps it off the node's disk and `sizeLimit: 64Mi` keeps it inside the container's memory budget rather than defaulting to half the node's RAM.
- **`podAntiAffinity` is `preferred`, not `required`.** A PDB covers voluntary disruption; it does nothing about a node dying, and two replicas on one node make a single node loss a full outage. `required` would be stricter — and would wedge a pod in `Pending` forever on a single-node cluster.
- **The HPA scales on CPU, and CPU is the wrong signal — knowingly.** `metrics-server` is a prerequisite the HPA comment calls out, and CPU is what it can offer. For a service whose slow path is waiting on a `SELECT … FOR UPDATE`, the load that matters may not show up as CPU at all, so the honest scaling signal is request rate or queue depth through KEDA or the Prometheus adapter. `/actuator/prometheus` is wired up and serving (the `micrometer-registry-prometheus` dependency is there for it — naming the endpoint in `management.endpoints.web.exposure.include` with no registry on the classpath silently 404s, which is a config that reads as working and is not). The adapter is the missing piece, not the metrics.

A Kubernetes Secret is **base64, not encryption**. `secret.example.yaml` says so, and the real answer is Secrets Manager via the Secrets Store CSI driver.

**Image architecture.** The image is built on an amd64 CI runner and the nodes are amd64, so no `--platform` flag is needed in the pipeline. A plain `docker build` on an Apple Silicon machine produces arm64 and the pod crash-loops with `exec /bin/sh: exec format error`; build locally with `--platform linux/amd64`.

---

## Cost safety — read this before touching AWS

The EKS control plane bills **~$0.10/hour (~$73/month) with zero worker nodes running**. It does not stop costing money because you stopped using it.

1. **Set a billing alarm before creating anything.**
2. **One region.** Resources in a region you forget about are resources you keep paying for.
3. **Delete the Ingress before the cluster.** Deleting the cluster first orphans the ALB the Ingress created, and an orphaned ALB bills indefinitely with nothing in the console obviously pointing at it.
4. **Tag everything `Project=flight-ops`** so it can be found later:

   ```bash
   aws resourcegroupstaggingapi get-resources --tag-filters Key=Project,Values=flight-ops
   ```
5. **Check the Kubernetes version before `eksctl create cluster`.** A version in *extended* support bills **$0.60/cluster-hour** instead of $0.10 — 6× — and extended support is **enabled by default**, so an aged-out version does not fail, it just costs. Verified 15 Sep 2026: **standard = 1.36 / 1.35 / 1.34**, **extended = 1.33 / 1.32 / 1.31**. `cluster.yaml` deliberately pins no version rather than hardcoding one that ages out. Re-check with:

   ```bash
   aws eks describe-cluster-versions \
     --query 'clusterVersions[?status==`STANDARD_SUPPORT`].[clusterVersion,endOfStandardSupportDate]' \
     --output table
   ```

   The filter field is `status`, **not** `clusterVersionStatus` — the latter returns an empty list, which reads like "no supported versions" rather than "your query is wrong".
6. **Verify the teardown in the console, not in the CLI output.** "I ran `eksctl delete cluster`" is not verification — `eksctl` can report success while a load balancer, an EBS volume or a NAT gateway survives. Check, in this order: EC2 → Load Balancers is empty, EC2 → Volumes has no `available` volumes, VPC → NAT Gateways is empty, ECR → the repository is deleted or empty, and CloudWatch → Log groups has no `/aws/eks/...` group still retaining. Filtering the console by the `Project=flight-ops` tag finds anything created here.

**No AWS account ID is hardcoded anywhere in this repository.** `events/*.json` use the placeholder `123456789012`; the GitHub Actions workflow reads `${{ secrets.AWS_ACCOUNT_ID }}`.

**Credentials.** There are none in the repo and none needed for the default profile. In AWS, `DefaultCredentialsProvider` is the whole story: the same code picks up `~/.aws` locally and a projected service-account token under IRSA in-cluster, so there is no environment-specific credential branch to get wrong. The CI pipeline uses GitHub's OIDC provider and short-lived STS credentials rather than a stored access key — the workflow documents the trust-policy condition that has to pin the `sub` claim, because a wildcard there is an account compromise waiting to happen.

**Region.** Everything targets `ap-south-1` and agrees on it: `cluster.yaml`, `k8s/configmap.yaml`, `template.yaml`, the workflow, and `application.yml`. Cross-region drift surfaces as an IRSA authentication error or an ECR image-pull failure rather than as an obvious region mismatch, so set the CLI default to match instead of relying on whatever it happens to be:

```bash
aws configure set region ap-south-1
```

---

## Trade-offs and known limitations

Every row is a decision, not an oversight. Left column: what the code does. Right: what a production system would do instead.

| Current | Production would be | Why it is this way |
|---|---|---|
| Two users in an `InMemoryUserDetailsManager` | Cognito, Okta or Entra behind `issuer-uri` | The *rules* are real and tested; the user store is a stub. The resource-server half is already wired and activates the moment an issuer is configured, so the swap is configuration, not a rewrite. |
| Idempotent replay returns **201**, not 200 | arguable either way | It replays the original response Stripe-style, so the body is identical. "201 Created" for something not created this time is a fair challenge. Documented rather than silently changed. |
| The outbox is drained by a **poller**, not by logical replication | Debezium reading the WAL | A 1-second poll costs one indexed query per replica per second and adds up to a second of latency. CDC removes both and adds Kafka Connect, a connector to operate and a replication slot that will fill the disk if the consumer stops. Not free, and not obviously worth it at this size. |
| Retention is a batched `DELETE` on a schedule | a partitioned table, dropping yesterday's partition | `DROP PARTITION` is O(1) and a delete is not, which matters from roughly the first hundred million rows. Below that it buys a partitioning scheme, a maintenance job to create partitions ahead of time, and an outage when that job is the thing that fails. The pruner is 40 lines and bounded; it is the right size for this. |
| No circuit breaker | Resilience4j | One outbound dependency, and the outbox already absorbs the failure mode a breaker would protect against: a down SQS leaves rows unpublished and the next drain retries them. |
| Contract tests are a **shared JSON file**, not Pact | a broker, with versioned pacts and a `can-i-deploy` gate in CI | The file catches the change that breaks the consumer, which is the whole job at two modules in one repository. A broker earns its keep when the consumers are other people's services on other people's release trains. |
| Traces are generated but **not exported** | an OTLP collector, and the trace id carried through the SQS message attributes into the Lambda | The ids are on every log line and in every response header, which is what makes one booking followable inside this service. Crossing the process boundary into the Lambda needs a collector to send to, and there is no collector in this deployment. See [Observability](#observability). |
| H2 uses `create-drop` | already done for PostgreSQL: Flyway + `validate` | Migrations on a throwaway in-memory database buy nothing. |
| `events/*.json` `md5OfBody` values are placeholders | real captured messages | Nothing reads the field, but it is not real traffic. |

### Found in review, and closed

The table above is design. This one was not. Two review passes were run against this service,
and every defect below was reproduced against a running instance before it was fixed — not
reasoned about, not inferred from reading. Each fix has a test that fails without it. The rows
survive so the claim is checkable rather than merely asserted.

**First pass — twelve defects a careful reader would also find.**

| What used to happen | What happens now |
|---|---|
| `GET /api/v1/flights?sort=nonsense` returned **500 `INTERNAL_ERROR`** — `PropertyReferenceException` reached the catch-all | 400 `UNKNOWN_SORT_PROPERTY`, naming the property. The client sent a bad parameter; the server did not break |
| Re-using an idempotency key with a **different payload** returned the original booking with 201 — different passenger, different flight, all replayed the first booking | 409 `IDEMPOTENCY_KEY_REUSED`. Each booking stores a SHA-256 fingerprint of its request (`V3__booking_request_fingerprint.sql`), compared on both the pre-flight read and the post-constraint recovery |
| The readiness probe ignored the database: `/actuator/health` went 503 while `/actuator/health/readiness` stayed 200 and every API call returned 500 | `readiness.include: readinessState,db`. Liveness deliberately stays on `livenessState` alone — a database outage must not restart every pod |
| `SELECT … FOR UPDATE` had no lock timeout; a stuck holder blocked every other booker until the JDBC socket gave up | `SET lock_timeout = '3s'` as a Hikari `connection-init-sql`, surfacing as 503 `LOCK_TIMEOUT` with `Retry-After`. A session-level setting, because a `@QueryHint` is silently discarded by the PostgreSQL dialect |
| `SqsClient` was built with SDK defaults — no `apiCallTimeout` — so a slow endpoint held a row lock and a pool connection indefinitely | 5s `apiCallTimeout`, 2s `apiCallAttemptTimeout`. And the publish no longer happens inside the booking transaction at all — see [the outbox](#the-outbox-why-the-event-is-a-database-row-first) |
| The DynamoDB sort key used `Instant.toString()`, which prints 0, 3, 6 or 9 fractional digits, so `…:01Z` sorted **after** `…:01.000001Z` | A fixed-width `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'` formatter on both sides, pinned by a test that asserts the `#` separator is always at index 27 |
| `PATCH …/status` accepted any transition, including `CANCELLED → SCHEDULED`, after which the flight sold seats again | `FlightStatus.canTransitionTo` — an exhaustive switch. `ARRIVED` and `CANCELLED` are terminal; a self-transition is allowed so a retried PATCH is safe |
| `POST /api/v1/flights` accepted `origin` equal to `destination` | `@DistinctEndpoints`, a class-level Bean Validation constraint, reported through the same `fieldErrors` shape as everything else |
| The passenger name was logged at INFO on every booking | Removed. Bookings are logged by id and flight number; no personal data reaches the log line |
| `hibernate.jdbc.batch_size: 50` sat beside `GenerationType.IDENTITY`, which **disables** insert batching — a setting that read as a tuning decision and did nothing | The property is gone, and the YAML says why. Real batching would mean `SEQUENCE` with a pooled optimiser, which is a different trade-off and not one this workload needs |
| `Flight.releaseSeats` had no production caller and there was no cancel-booking endpoint | `DELETE /api/v1/bookings/{id}` (`V4__booking_cancellation.sql`). Cancelling twice is a 200 no-op |
| Time came from `Instant.now()` inside the domain, so nothing could test a clock-dependent path | An injected `Clock` (`config/TimeConfig.java`) |

**Second pass — seven more, run adversarially against the first.** These are the interesting
ones. Three of them take the service down in production while every probe, every test and every
local run stays green, which is precisely the class of defect a first pass does not find.

| What used to happen | What happens now |
|---|---|
| **A missing `API_PASSWORD` started the service anyway.** `@ConfigurationProperties` binding resolves placeholders with `ignoreUnresolvablePlaceholders=true` — unlike `@Value`, which fails — so an unset variable bound as the literal 15-character string `${API_PASSWORD}`. The context started, both probes passed, the pod went Ready, and *every authenticated request* then returned a bodyless 500 forever: `DelegatingPasswordEncoder` finds `{` at index 1 instead of 0 and throws `IllegalArgumentException`, which is not an `AuthenticationException`, so no filter catches it and `@RestControllerAdvice` never sees it — it is thrown before `DispatcherServlet` | `@Validated` on the record plus a `@Pattern` requiring a `{id}` algorithm prefix (`config/ApiSecurityProperties.java`). The context now fails to start, with a message naming the variable. A healthy-looking pod that answers nothing is strictly worse than one that refuses to boot |
| **`@Lob` on the outbox payload would have crash-looped every replica.** `@Lob` on a `String` resolves to `SqlTypes.CLOB`, and PostgreSQL's dialect maps CLOB to `oid` — a pointer into `pg_largeobject`, not inline text. `V5__outbox.sql` creates the column as `TEXT`, so `ddl-auto: validate` compared `text (Types#VARCHAR)` against `oid (Types#CLOB)`, refused, and the context failed to refresh. Invisible on a laptop, because H2 runs `create-drop` and generates the column itself | `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)` (`entity/OutboxEvent.java`) — the explicit spelling of what was meant. PostgreSQL renders it as `text`, H2 keeps its `clob`, and nothing goes through `setClob`, which would have orphaned a server-side large object on every `markPublished` |
| **`HEAD` was a 403 for a caller holding `flights:read`.** `requestMatchers(HttpMethod.GET, …)` matches the literal verb, but Spring MVC serves HEAD for every `@GetMapping`, so HEAD fell past the read rule into `anyRequest().denyAll()` | An explicit HEAD rule beside the GET one (`config/SecurityConfig.java`), and a test that asserts HEAD is a read for the reader and still forbidden for the ops principal |
| **A bad bearer token returned a bodyless 401.** `OAuth2ResourceServerConfigurer` installs its *own* `BearerTokenAuthenticationEntryPoint` directly on `BearerTokenAuthenticationFilter`, which handles the exception itself — so `ExceptionTranslationFilter` never runs, and the entry point configured under `exceptionHandling` was dead code on that path | The entry point and the access-denied handler are wired into the configurer as well. The JSON error contract now holds for bearer tokens, not only for Basic |
| **An expanded-year timestamp poisoned the DynamoDB sort key.** `Instant.parse` accepts `+12026-09-15T10:00:00Z`, and the `uuuu` pattern emits the sign — so the key became 29 characters starting with `+` (0x2B, below every ASCII digit), sorting ahead of the entire partition and invisible to `begins_with(eventTime, "2026-")` | The year is range-checked in `sortKey`, and a value outside it throws. The message is reported as a batch item failure, so it is retried and lands in the DLQ where it can be inspected — rather than written under a key no query will ever return |
| **The contract test checked the shape, not the values.** Changing the formatter's zone from UTC to the host's keeps 27 characters, six fractional digits, a trailing `Z` (a quoted literal in the pattern, not the offset field) and monotonic ordering — so every assertion stayed green while every event on the queue shifted by the host offset | The serialised event is compared against `contracts/booking-created-v1.json` as a whole document, plus a test that names the zone in its failure message. CI runs in UTC and a laptop does not, which is exactly the arrangement in which a zone bug ships green |
| **`k8s/secret.example.yaml` did not carry the two API passwords**, so anyone following the example deployed a pod with neither set — which is the first row of this table | Both keys are in the example, with the `htpasswd -bnBC 10 "" 'pw' \| tr -d ':\n'` recipe and a note that omitting them now fails startup |

**Third pass — the three gaps this README itself listed as open.** Not found by a reviewer:
written down here as known and unfixed, which is the easiest kind of debt to leave alone
forever. Each one now has a test that fails without the fix, like every row above.

| What used to happen | What happens now |
|---|---|
| **Nothing tied a log line to a request.** A 500 in a three-replica deployment meant grepping by timestamp and hoping. The README called this the largest operability gap and it had been open the longest | `RequestIdFilter` puts a request id in the MDC ahead of Spring Security and echoes it on *every* response including 401 and 403, `traceId`/`spanId` sit beside it, and the prod profile emits ECS JSON so all four are queryable fields rather than substrings. See [Observability](#observability) |
| **Published outbox rows were kept forever.** Nothing breaks for months, which is the problem: the partial index only covers unpublished rows, so the poller keeps performing perfectly while the heap underneath it grows, and the first symptom is a backup window or a disk alert on a Sunday | `OutboxPruner` deletes published rows past a 7-day retention, in bounded batches with a per-run ceiling. `OutboxPrunerTest` proves the statement against a real database — including that an unpublished row is never deleted however old it is, which is the failure that would be both silent and permanent |
| **A poisoned event was retried first, forever.** `ORDER BY id` puts the oldest failing row at the head of every batch, so one payload the transport structurally rejects consumes the batch on every tick while live events queue behind it — one bad row, total publishing outage, and waiting is what it is already doing | The claim carries `AND attempts < :maxAttempts`, the row drops out after ten failures, `outbox_dead` rises and a WARN names the id and the booking. The documented re-drive, `UPDATE outbox_events SET attempts = 0 WHERE id = ?`, is run verbatim by a test so the sentence an operator will paste is a tested one |

### Still open

| What happens | What should happen | The fix |
|---|---|---|
| There is no rate limiting. A single caller with valid credentials can saturate the pool. | A token bucket per principal at the gateway, or Bucket4j in front of the write endpoints. | Out of scope for the service itself — this belongs at the ingress, and saying so is the answer rather than adding a half-measure here. |

---

## Versions

Java **21.0.12.1** · Spring Boot **4.1.1** · Spring Framework **7.0.9** · Spring Security **7.1.1** · Hibernate **7.4.5** · Jackson **3.1.5** · Tomcat **11.0.24** · Flyway **12.4.0** · JUnit **6.0.3** · springdoc-openapi **3.1.1** · Jakarta EE 11 · Maven **3.9.16** · AWS SDK for Java **2.55.2**.

One version in `pom.xml` is not Boot's: `jackson-2-bom.version` is overridden to **2.22.1**. Boot 4 runs on Jackson 3 and still manages the Jackson 2 coordinates at 2.21.5 for libraries that have not moved; swagger-core, which builds the OpenAPI document, is one of those and needs 2.22.1. Maven's nearest-wins would have handed it the older Jackson 2 silently — the enforcer's `requireUpperBoundDeps` rule refused the build instead, which is the clearest case yet of that rule paying for itself.
Built and tested on macOS arm64 with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

`./mvnw` pins Maven 3.9.16 **and its SHA-256**, so a clone builds with the same Maven this was built with, CI needs no Maven install step, and a substituted archive fails the build instead of running. The wrapper is `distributionType=only-script`, so there is no `maven-wrapper.jar` committed — two shell scripts and a properties file.

### The Boot 4 upgrade, and what it actually cost

Spring Boot 3.5 left OSS support on 30 June 2026 and 3.5.16 was the last patch ever published. Dependabot opened the 3.5.16 → 4.1.1 bump against this repository and the build failed, which is the honest starting point: **a major is never a merge button.** What it took, in the order the compiler found it:

- **`spring-boot-starter-web` → `spring-boot-starter-webmvc`.** The old artifact still resolves, but Boot's own POM now describes it as deprecated. Same Tomcat, same MVC; the rename is part of splitting every technology into its own module.
- **The test starter split.** `@WebMvcTest` moved to `spring-boot-starter-webmvc-test` and `@DataJpaTest` plus `TestEntityManager` to `spring-boot-starter-data-jpa-test`. Without both, the slice tests do not compile — the annotations are *gone*, not deprecated.
- **`flyway-core` → `spring-boot-starter-flyway`.** The auto-configuration moved out of the core Boot jar into a per-technology starter, so a bare `flyway-core` leaves `FlywayAutoConfiguration` absent and the migrations silently never run.
- **Jackson 2 → Jackson 3.** The package root is `tools.jackson`, not `com.fasterxml.jackson`, and `ObjectMapper.writeValueAsString` now throws the unchecked `JacksonException` rather than the checked `JsonProcessingException`. Every `catch` on the old type stops compiling, which is the good outcome; the bad one would have been a silently unreachable handler.
- **Actuator package moves.** `EndpointRequest` is now in `org.springframework.boot.security.autoconfigure.actuate.web.servlet` and `HealthEndpoint` in a new `spring-boot-health` module. Both are needed by `SecurityConfig` to match actuator endpoints by *type* instead of by literal path.
- **Testcontainers 2.x renamed every module.** `org.testcontainers:postgresql` became `testcontainers-postgresql`; the old coordinates are simply not published any more.
- **`@MockBean` and `@SpyBean` are removed**, not deprecated. `@MockitoBean` and `@MockitoSpyBean` replace them.
- **JUnit 6.0.3** arrives with the BOM, which is why `lambda/pom.xml` moved to match — two JUnit majors in one repository is a trap when switching between the modules.

The JDK was never the blocker: Boot 4's floor is Java 17.

Dependency updates are automated: [`.github/dependabot.yml`](.github/dependabot.yml) covers both Maven modules, the Actions workflow and the Dockerfile base images. The Lambda module needs its own entry because it has no parent POM, so nothing else manages its versions.

Two details in that file are easy to get wrong and are worth knowing before editing it:

- **`open-pull-requests-limit` is set on every ecosystem**, because the default is 5 **per ecosystem**, not 5 overall. Four ecosystems left implicit can therefore open twenty pull requests the first time Dependabot runs on a new repository — which is exactly what happened here before the limits and the grouping went in.
- **The `ignore` rules are scoped to four artifacts and no more**, because an `ignore` condition suppresses Dependabot's *security* updates for that dependency as well as its version updates. That is a real cost, so it is only accepted where a major bump would contradict a pin this project has already made and documented: `eclipse-temurin` and `maven` in the two base images, `org.springframework.boot:spring-boot-starter-parent`, and `org.junit:junit-bom` under `lambda/`. The last one is not "stay on 5.x" — both modules are on JUnit 6.0.3 now. It is "do not let Dependabot move one module's major on its own", because whichever side moves first splits the repository across two JUnit majors.

**Every GitHub Action is pinned to a full commit SHA**, with the version in a trailing comment that Dependabot rewrites along with the SHA. A major tag is a mutable pointer in somebody else's repository: re-pointing `@v4` at a malicious commit is an attack that needs no access to *this* repository at all, which is what happened to `tj-actions/changed-files` in March 2025. The cost is a PR for every patch release, which is exactly why the `github-actions` ecosystem entry above matters more than it looks.

---

## License

MIT — see [LICENSE](LICENSE).
