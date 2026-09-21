# flight-ops-service

[![build & tests](https://img.shields.io/github/actions/workflow/status/smit-lakhani-13/flight-ops-services/build-and-deploy.yml?branch=main&label=build%20%26%20tests)](https://github.com/smit-lakhani-13/flight-ops-services/actions/workflows/build-and-deploy.yml)

Flight inventory and booking microservice: a Spring Boot REST API over PostgreSQL that publishes booking events to SQS, where an AWS Lambda consumer projects them into DynamoDB.

**Java 21 · Spring Boot 3.5.16 · Spring Data JPA · PostgreSQL / H2 · Flyway · AWS SQS + Lambda + DynamoDB · Docker · Kubernetes / EKS · SAM · GitHub Actions**

The airline domain is deliberate. Seat inventory is a genuinely hard consistency problem: multiple clients compete for the same finite resource, retries are unavoidable, and getting it wrong means selling the same seat twice. That gives every concurrency and idempotency decision in this repository a concrete reason to exist rather than a theoretical one.

**Scope.** This is a demonstration service, not a deployed system. It has never served production traffic. CI builds both modules and runs the full test suite — including the PostgreSQL integration tests — on every push. The infrastructure that needs a registry, a cluster or an AWS account (`Dockerfile`, `k8s/`, `template.yaml`, and the deploy half of the workflow) is authored and reviewed but has not been applied. [Project status](#project-status) records exactly which parts have been executed and which have not, and every claim below is bounded by that table.

**Contents** — [Run it](#run-it-in-30-seconds) · [Project status](#project-status) · [Architecture](#architecture) · [Repository layout](#repository-layout) · [API](#api) · [Concurrency](#the-hard-problem-not-overselling-the-last-seat) · [Tests](#tests) · [Lambda](#lambda-module) · [Container and Kubernetes](#container-and-kubernetes) · [Cost safety](#cost-safety--read-this-before-touching-aws) · [Trade-offs](#trade-offs-and-known-limitations)

---

## Run it in 30 seconds

**Prerequisite: a JDK 21.** Nothing else — no database, no AWS account, no Docker, no local Maven install.

```bash
java -version          # must report 21
./mvnw spring-boot:run
```

In-memory H2, schema created by Hibernate, three demo flights seeded on boot (`UA123` EWR→LHR 180 seats, `UA456` ORD→SFO 150, `UA789` EWR→SFO 200). Boots in **under 3s** — 2.59s / 2.67s / 2.79s across three local runs.

> **On macOS, a JDK-selection trap worth knowing.** `/usr/libexec/java_home -v 21` only resolves JDKs registered with macOS, and Homebrew's are not. On a machine that also has an Oracle JDK 17 installed it therefore exits 0 and hands back *17* — and the build dies several steps later on `release version 21 not supported`, a long way from the actual cause. Set `JAVA_HOME` explicitly: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` on Apple Silicon, `/usr/local/opt/openjdk@21` on Intel.

```bash
curl localhost:8080/actuator/health
curl localhost:8080/api/v1/flights/UA123
curl "localhost:8080/api/v1/flights?origin=EWR&page=0&size=5"
```

### Or run the whole tour at once

With the app running, in a second terminal:

```bash
./demo.sh          # pauses between acts, so you can talk over it
./demo.sh --fast   # no pauses
```

Five acts over real HTTP: the paged API and the normalised `Location` header; idempotent
replay; the deliberate error codes including the cancelled-flight refusal; **ten concurrent
callers racing on one idempotency key**; and the actuator surface Kubernetes probes. It
preflights that the app is up, and falls back to `python3 -m json.tool` if `jq` is absent.
State is in-memory — restart the app to reset it.

### The two behaviours worth demonstrating

**1. A retry doesn't double-book.**

```bash
curl -sX POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Smit","seats":3,"idempotencyKey":"demo-1"}'
curl -s localhost:8080/api/v1/flights/UA123          # availableSeats: 177

for i in 1 2 3 4 5; do                                # the client retried after a timeout
  curl -sX POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json' \
    -d '{"flightNumber":"UA123","passengerName":"Smit","seats":3,"idempotencyKey":"demo-1"}' >/dev/null
done

curl -s localhost:8080/api/v1/flights/UA123          # STILL 177, not 162
curl -s "localhost:8080/api/v1/bookings?flightNumber=UA123"   # exactly ONE booking
```

**2. A cancelled flight refuses bookings.**

```bash
curl -sX DELETE localhost:8080/api/v1/flights/UA456   # 204, status -> CANCELLED

curl -sX POST localhost:8080/api/v1/bookings -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA456","passengerName":"Smit","seats":1,"idempotencyKey":"demo-2"}'
# 409 {"code":"FLIGHT_NOT_BOOKABLE","message":"Flight UA456 is CANCELLED and cannot be booked"}

curl -s localhost:8080/api/v1/flights/UA456           # availableSeats unchanged
```

Any status can be set with `PATCH /api/v1/flights/{n}/status` and body `{"status":"DEPARTED"}`. `SCHEDULED`, `BOARDING` and `DELAYED` sell seats; `DEPARTED`, `ARRIVED` and `CANCELLED` return 409. The second behaviour is the interesting one — it did not exist until a probe against a running instance found it missing. See [the bug a passing suite did not catch](#the-bug-that-a-passing-test-suite-did-not-catch).

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
| ✅ **Built, tested, and exercised over HTTP** | The whole app module. Every endpoint hit with `curl` against a running instance; every status code in the tables below observed, not inferred. The Lambda handler's logic, via 12 unit tests. |
| ✅ **Verified against real PostgreSQL in CI** | All 92 tests, including the 5 Testcontainers integration tests: the Flyway migration applied to an empty database, `ddl-auto: validate` checked against the schema that migration produced, `SELECT … FOR UPDATE` under 20 threads competing for 5 seats, and the same idempotency key replayed by 20 threads at once. The runners have Docker, so these execute there and skip on a laptop without one. |
| ⚠️ **Authored and reviewed, never executed** | The container image. `sam build`, `sam local invoke`, `sam deploy`. Every `kubectl` and `eksctl` step. The deploy half of the GitHub Actions workflow — gated off deliberately, see below. |
| ❌ **Not implemented** | Authentication/authorisation. A Solace binding. A flight-status transition graph. Contract tests. |

**Why the gap:** the infrastructure was authored and reviewed on a machine with no container runtime and no cluster to apply it to. That makes it reviewed, not verified — a real distinction, and one worth recording here rather than glossing over. CI closes the part of the gap it can reach: the runners have Docker, so the integration tests that need a real database run there and nowhere else. What is left in the ⚠️ row needs a registry, a cluster, or a funded AWS account, and this project has none of the three.

The deploy half of that pipeline is **switched off by default**, gated on a `DEPLOY_ENABLED` repository variable rather than on the branch alone. Without the gate, the first push to a fresh clone would try to assume an IAM role built from an unset `AWS_ACCOUNT_ID` secret, and the job would fail on missing configuration rather than on anything about the code. A red build meaning "nobody provisioned the cluster" is worse than no build, because it teaches whoever reads the repository next to stop trusting the colour. So `build` and `verify` run on every push and the deploy steps report as skipped until someone provisions the role and flips the variable.

Two further things this repository does not claim:

- **`events/*.json` are hand-written fixtures, not captured queue traffic.** Their `md5OfBody` values are placeholders. Nothing in the code reads that field.
- **No image size is quoted.** The Dockerfile is multi-stage and does the right things; a figure would have to be invented, so there isn't one.

---

## Architecture

```
                       ┌─────────────────────────────────────────────┐
   HTTP ──────────────►│ flight-ops-service    Java 21 / Boot 3.5.16 │
                       │                                             │
                       │  Controller   HTTP only: bind, validate,    │
                       │      │        map to DTO, choose status     │
                       │      ▼                                      │
                       │  Service      @Transactional boundary,      │
                       │      │        idempotency, orchestration    │
                       │      ▼                                      │
                       │  Entity       the invariants live here      │
                       │      │        (Flight.reserveSeats)         │
                       │      ▼                                      │
                       │  Repository   Spring Data JPA               │
                       │      │                                      │
                       │      ▼                                      │
                       │  PostgreSQL (Flyway) / H2   flights,        │
                       │                             bookings        │
                       │                                             │
                       │  EventPublisher ──► LoggingEventPublisher   │
                       │       (interface)   SqsEventPublisher       │
                       └───────────────────┬─────────────────────────┘
                                           │ BookingCreatedEvent
                                           ▼
                                    ┌──────────────┐
                                    │  SQS queue   │──► DLQ after 3 receives
                                    └──────┬───────┘
                                           ▼
                       ┌─────────────────────────────────────────────┐
                       │ Lambda  java21 / arm64  (separate module)   │
                       │  batch of 10 · partial batch response ·     │
                       │  conditional write ──► DynamoDB             │
                       └─────────────────────────────────────────────┘
```

`app.events.publisher: log | sqs` picks the implementation via `@ConditionalOnProperty`, and `log` is the default so that nothing tries to reach AWS on a laptop. Note the mechanism: `@Primary` and `@Qualifier` decide which bean gets *injected* but still instantiate every candidate, which for an SQS client means building it on a machine with no credentials. `@ConditionalOnProperty` decides whether the bean is *defined at all*.

**There is no Solace implementation.** Solace is named in a comment on `EventPublisher` as one of the transports that interface exists to accommodate, and that is the whole extent of it.

---

## Repository layout

```
├── src/main/java/com/smit/flightops/
│   ├── controller/     HTTP only — bind, validate, map to DTO, choose status code
│   ├── service/        @Transactional boundary, idempotency, EventPublisher + 2 impls
│   ├── entity/         Flight, Booking, FlightStatus — the invariants live here
│   ├── repository/     Spring Data JPA, including the SELECT … FOR UPDATE query
│   ├── dto/            8 records: request/response types + BookingCreatedEvent (the wire contract)
│   ├── exception/      5 domain exceptions + the single @RestControllerAdvice
│   └── config/         AwsConfig, AwsProperties (@ConfigurationProperties), DataSeeder
├── src/main/resources/
│   ├── application.yml            profiles: default (H2), postgres, prod
│   └── db/migration/V1__init.sql  Flyway — owns the PostgreSQL schema
├── src/test/java/                 11 test classes, layered — see Tests
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

## API

| Method | Path | Success | Failures |
|---|---|---|---|
| `GET` | `/api/v1/flights/{flightNumber}` | 200 | 404 |
| `GET` | `/api/v1/flights?origin=&destination=&page=&size=` | 200 (paginated) | — |
| `POST` | `/api/v1/flights` | 201 + `Location` | 400, 409 |
| `PATCH` | `/api/v1/flights/{flightNumber}/status` | 200 | 400, 404 |
| `DELETE` | `/api/v1/flights/{flightNumber}` | 204 | 404 |
| `POST` | `/api/v1/bookings` | 201 + `Location` | 400, 404, 409 |
| `GET` | `/api/v1/bookings/{bookingId}` | 200 | 400, 404 |
| `GET` | `/api/v1/bookings?flightNumber=` | 200 | 400 |
| `GET` | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | 200 | — |
| `GET` | `/actuator/metrics`, `/actuator/prometheus` | 200 | — |

Every error has one JSON shape — `{code, message, timestamp}`, or `{code, fieldErrors, timestamp}` for validation — produced by a single `@RestControllerAdvice`. No controller contains a `try`/`catch`.

**Both `Location` headers are built from the returned DTO, not from the request**, because `FlightService` normalises flight numbers (`trim` + upper-case). `POST /api/v1/flights` with `{"flightNumber":" ua999 "}` answers `Location: /api/v1/flights/UA999` — the only form that resolves. Verified by following both headers with `curl`: 200 each. [Why that verification exists](#the-second-bug-a-passing-suite-did-not-catch).

### The 14 error codes

| Code | Status | Meaning |
|---|---|---|
| `FLIGHT_NOT_FOUND` | 404 | no such flight number |
| `BOOKING_NOT_FOUND` | 404 | no such booking id — its own code, so a 404 from a booking URL does not claim the *flight* is missing |
| `INSUFFICIENT_SEATS` | 409 | fewer seats remain than requested — **retrying with fewer seats can succeed** |
| `FLIGHT_NOT_BOOKABLE` | 409 | flight is `CANCELLED`/`DEPARTED`/`ARRIVED` — **retrying can never succeed** |
| `DUPLICATE_FLIGHT` | 409 | flight number already exists |
| `CONCURRENT_MODIFICATION` | 409 | `@Version` rejected a stale write |
| `DUPLICATE_REQUEST` | 409 | two flight-creation requests raced on `flight_number`; the DB constraint arbitrated — a raced *booking* no longer lands here, see below |
| `VALIDATION_FAILED` | 400 | Bean Validation, reported per field |
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
4. **The invariants live on the entity, not the service.** `Flight.reserveSeats` is the only way seats move, so no caller can forget the rules — including a caller written later by someone else.

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

**Known limitation, on purpose:** `updateStatus` still permits any status → any status. A real system enforces a transition graph (`ARRIVED` is terminal; `DEPARTED` cannot return to `SCHEDULED`). That is a state machine, and the half of it that loses money — "can this be booked?" — is the half that is implemented.

---

## Tests

```bash
./mvnw clean verify                       # 80 tests: 75 run, 5 skipped, 0 failures
./mvnw -f lambda/pom.xml clean verify     # 12 tests, 0 failures
```

| Layer | Tests | Tooling |
|---|---|---|
| Domain entity | 12 | plain JUnit — no Spring, no database. A domain rule should be provable without either. |
| Service | 21 | `@ExtendWith(MockitoExtension.class)`, `@Mock`, `@InjectMocks`, `@Captor` — split across `BookingServiceTest` (orchestration), `BookingWriterTest` (the write path), `FlightServiceTest` |
| Web slice | 21 | `@WebMvcTest` + `@MockitoBean` — status codes, `Location` headers, error JSON |
| Repository slice | 13 | `@DataJpaTest` + `TestEntityManager` — derived queries, JPQL, `JOIN FETCH`, constraints |
| Full context (H2) | 8 | `@SpringBootTest(webEnvironment = NONE)` — the idempotency guarantee end to end (a 10-thread race on one key) and a sequential lazy-loading regression with no mocking anywhere in the chain |
| Lambda handler | 12 | separate module — batch parsing, partial batch failure, conditional write |
| **Run** | **87** | **0 failures** (12 + 21 + 21 + 13 + 8 + 12) |
| PostgreSQL integration | 5 | `@Testcontainers(disabledWithoutDocker = true)` — skipped without a container runtime |

92 tests exist across the two modules; 87 run without Docker, 5 skip. CI runs all 92 and they pass — the runner has Docker, so it is the only place the real PostgreSQL path (Flyway + `ddl-auto=validate` + `SELECT FOR UPDATE` under 20-way contention, and a 20-thread idempotency-key race) gets exercised. The surefire summary there reads `Tests run: 80, Failures: 0, Errors: 0, Skipped: 0` for this module and `Tests run: 12 … Skipped: 0` for the Lambda. `Skipped: 0` rather than `Skipped: 5` is the part worth reading: it is the difference between the integration tests passing and the integration tests quietly opting out, and a green build alone does not distinguish the two.

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
- **`DynamoDbClient` behind an initialization-on-demand holder**, so the SDK client is created once per execution environment and reused across warm invocations rather than per request.
- **No framework.** A plain `RequestHandler`, not Spring Cloud Function — the handler does one thing, and a container to start is a container to start on every cold invocation.

### The deployment package

`software.amazon.awssdk:dynamodb` drags in **three** HTTP clients transitively — `apache-client`, `apache5-client` and `netty-nio-client` — each registering itself through `META-INF/services/software.amazon.awssdk.http.SdkHttpService`. All three are excluded and replaced with `url-connection-client`, which is what AWS documents for Lambda: 34 KB, zero transitive dependencies, no connection pool to keep warm.

| | Jar | Classes | Sync HTTP providers |
|---|---|---|---|
| Before | ~18 MB | — | 2 |
| After | **10.3 MB** | 5,787 | 1 (`UrlConnectionSdkHttpService`) |

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
- **`readOnlyRootFilesystem` is `false`, deliberately** — and this is the one hardening box left unticked. The JVM writes to `/tmp` (Tomcat's work dir, `hsperfdata`), so flipping it to `true` needs an `emptyDir` mounted at `/tmp`. The manifest says so in a comment. Recorded as the next change rather than claimed as done.
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
| Event published **inside** the transaction | `@TransactionalEventListener(AFTER_COMMIT)` or a **transactional outbox** | The real failure mode: a rollback after the publish leaves a `BookingCreated` event for a booking that does not exist. An outbox is the correct fix and it is a lot of machinery; the trade-off is documented in `SqsEventPublisher` rather than hidden. |
| Idempotent replay returns **201**, not 200 | arguable either way | It replays the original response Stripe-style, so the body is identical. "201 Created" for something not created this time is a fair challenge. Documented rather than silently changed. |
| `updateStatus` allows any transition | state-machine-enforced graph | The subset that loses money is enforced. See above. |
| No auth — every endpoint is open | Spring Security + OAuth2 resource server | Left out entirely rather than half-written. A `SecurityConfig` with `permitAll()` looks like security and is not. |
| No circuit breaker | Resilience4j | One outbound dependency and no retry storm to protect against yet. |
| No contract tests | Pact between the service and the Lambda consumer | The event shape is duplicated across two modules — a real coupling, currently held together by nothing but care. |
| H2 uses `create-drop` | already done for PostgreSQL: Flyway + `validate` | Migrations on a throwaway in-memory database buy nothing. |
| `events/*.json` `md5OfBody` values are placeholders | real captured messages | Nothing reads the field, but it is not real traffic. |

---

## Versions

Java **21.0.12.1** · Spring Boot **3.5.16** · Spring Framework 6.2 · Hibernate 6.6 · Jakarta EE 10 · Maven **3.9.16** · AWS SDK for Java **2.x**.
Built and tested on macOS arm64 with `JAVA_HOME=/opt/homebrew/opt/openjdk@21`.

`./mvnw` pins Maven 3.9.16 **and its SHA-256**, so a clone builds with the same Maven this was built with, CI needs no Maven install step, and a substituted archive fails the build instead of running. The wrapper is `distributionType=only-script`, so there is no `maven-wrapper.jar` committed — two shell scripts and a properties file.

**3.5.16 is the last 3.5.x release ever published** (verified against Maven Central), and Spring Boot 3.5 left OSS support on 30 June 2026. Running 3.5 is a defensible choice for an enterprise stack; not knowing it has left OSS support is not. Migrating to 4.x would touch the parent version, several starter renames (`-web` → `-webmvc`), the test-starter split, and the removal of `@MockBean`/`@SpyBean` — Boot 4's floor is Java 17, so the JDK is not the blocker. That is not a guess: Dependabot opened exactly that bump against this repository, 3.5.16 → 4.1.1, and the build failed. The pin is now explicit rather than incidental, so the upgrade stays a deliberate piece of work instead of arriving as a merge.

Dependency updates are automated: [`.github/dependabot.yml`](.github/dependabot.yml) covers both Maven modules, the Actions workflow and the Dockerfile base images. The Lambda module needs its own entry because it has no parent POM, so nothing else manages its versions.

Two details in that file are easy to get wrong and are worth knowing before editing it:

- **`open-pull-requests-limit` is set on every ecosystem**, because the default is 5 **per ecosystem**, not 5 overall. Four ecosystems left implicit can therefore open twenty pull requests the first time Dependabot runs on a new repository — which is exactly what happened here before the limits and the grouping went in.
- **The `ignore` rules are scoped to three artifacts and no more**, because an `ignore` condition suppresses Dependabot's *security* updates for that dependency as well as its version updates. That is a real cost, so it is only accepted where a major bump would contradict a pin this project has already made and documented: the JDK in the two base images, and the Spring Boot parent. Everything else — every minor, every patch, every dependency not named — still flows automatically.

---

## License

MIT — see [LICENSE](LICENSE).
