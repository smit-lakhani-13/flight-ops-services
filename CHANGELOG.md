# Changelog

What changed in each release and, where it matters, why.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the versions follow [semantic versioning](https://semver.org/spec/v2.0.0.html),
with the qualifications below.

**On 1.1.0 not being 2.0.0.** This release removes a field from an API response
(`idempotencyKey`, from `BookingDto`). Under strict semantic versioning a
removed response field is a breaking change to consumers, which would make this
a major release. I made it 1.1.0 because 1.0.0 was never tagged, never
published and never consumed, so no client anywhere could break. From this
release on, a change to what the documented contract returns for a request it
accepts is a major, whether it is a response field, a status or an error code.
Starting to reject input the contract never allowed, such as a fractional seat
count or a flight number with a slash, is a fix.

**On the dates.** 1.0.0 was never tagged, so I wrote its entry afterwards from
the history and dated it by its last commit. All dates are IST. I split the two
entries by theme (the service, then the hardening pass that followed) and not
at a single commit. So 1.1.0 lists some fixes committed before 2026-09-22, and
its Boot 4.1.1 upgrade came before some of the work in 1.0.0. The whole history
is eight days long, and the second half of it made the first half checkable.

**What "released" means here.** A version number in `pom.xml` and a git tag. It
does not mean deployed. Nothing in this repository has ever run in AWS, and
[DEPLOYMENT.md](DEPLOYMENT.md) records that in a dated line that is still blank.

## Unreleased

### Added

- **Password self-check.** At startup, `SecurityConfig.assertVerifiable` runs
  the encoder once on each configured hash and stops with, for example,
  `app.security.api-password cannot be verified by the configured DelegatingPasswordEncoder`.
  A misspelt id such as `{bcrpyt}`, or an `{argon2}` hash without BouncyCastle,
  used to pass startup and fail every login with a 500
  (`PasswordVerifiabilityTest`).

- **Bearer challenge.** `JsonAuthenticationEntryPoint` answers an
  `OAuth2AuthenticationException` with
  `WWW-Authenticate: Bearer realm="flight-ops-service", error="<code>"`. Every
  other 401 still gets `Basic realm="flight-ops-service"`
  (`BearerTokenChallengeTest`).

- **Unhandled 500s logged.** `ApiErrorController` logs a 5xx that carries an
  exception at ERROR, as `Unhandled failure on <method> <uri>`. It puts the
  request id back into the MDC first, so the id in the 500 body finds the line.

- **New CI gates.** The build job gains "The PostgreSQL tests ran", which fails
  unless `BookingIntegrationTest` and `OutboxPrunePostgresTest` ran with nothing
  skipped, and "The SAM template points at the Lambda jar". The deploy job gains
  "The image will not start without a database", and infra-lint runs
  `deploy/aws/selftest.sh` against stubbed tools.

- **Race tests.** `BookingIdempotencyTest#oneKeyRacedAcrossTwoFlightsBooksOnce`
  races one key across two flights, and every race test releases its callers
  through the `startTogether` latch.
  `OutboxPrunePostgresTest#concurrentClaimsAreDisjointAndSkipRowsNotYetDue`
  runs two outbox claims at once on PostgreSQL.

- **Other tests.** `FlightControllerTest#flightNumberRaceReturns409` replaces
  the misnamed `BookingControllerTest#concurrentDuplicateKeyReturns409`.
  `SecurityRulesTest#readScopeCannotWrite` now also sends a PATCH with the read
  scope.

### Changed

- **Stricter request fields.** A value that breaks one of these rules gets a
  400 `VALIDATION_FAILED` whose body maps the field to the message shown.
  `flightNumber` on `CreateFlightRequest` and `BookingRequest` matches
  `CreateFlightRequest.FLIGHT_NUMBER` (`^\s*[A-Za-z0-9]*\s*$`, padding the
  service trims): "must contain only letters and digits". `origin` and
  `destination` match `^[A-Za-z]*$`: "must contain only letters".
  `BookingRequest.passengerName` matches `^[^\p{Cntrl}]*$`: "must not contain
  control characters". Together with the flight number pattern, this keeps the
  fingerprint separator out of every hashed field.

- **JSON only.** `FlightController` and `BookingController` declare
  `produces = application/json`, so an XML or YAML `Accept` gets 406
  `REQUEST_REJECTED` in JSON. The three writes also declare
  `consumes = application/json` (see Fixed). `/v3/api-docs.yaml` still
  returns 200.

- **Complete OpenAPI responses.** Every operation has `@Operation` and
  `@ApiResponses`, 401 and 403 included, and the flight status change and
  cancel document 503 `LOCK_TIMEOUT`. Each write with a body documents 415.
  `OpenApiTest#theDocumentCoversTheApiAndItsFailures` pins each operation's
  documented codes.

- **Password prefixes.** `ApiSecurityProperties` accepts any `{id}` prefix
  without braces or spaces, so an id with `@`, `-` or `_`
  (`{pbkdf2@SpringSecurity_v5_8}`) now passes. The check moved from Bean
  Validation into the record's constructor.

- **Health details for ops.** `management.endpoint.health.roles: OPS` replaces
  the prod-only `show-details: never`. The components show to ops on every
  profile and to no one else.

- **Log lines.** `logging.include-application-name: false` prints the service
  name once in a plain-text line. The `JsonAccessDeniedHandler` WARN now reads
  `Denied {} {} for an authenticated caller: no rule grants this method and path to its authorities`.

- **Configuration trimmed.** `spring.jpa.properties.hibernate.order_inserts`
  is gone, because IDENTITY ids turn insert batching off.
  `SecurityConfig.DOC_PATHS` drops the literal `/v3/api-docs`, which
  `/v3/api-docs/**` already matches.

- **Wall-clock rule.** `the_wall_clock_is_read_only_by_entities` in
  `ArchitectureTest` now exempts only `com.smit.flightops.entity.Booking`, where
  1.1.0 exempted the whole `entity` package.

- **Test builds.** Both poms pin the Surefire JVM to
  `-Duser.timezone=Asia/Kolkata`, so a test that leans on the system zone fails
  on a UTC runner too. The PostgreSQL tests move to Testcontainers 2
  (`org.testcontainers.postgresql.PostgreSQLContainer`), and
  `migrationRanAndSchemaValidates` checks that Flyway applied every file from V1
  to V8.

- **Lambda concurrency.** `ScalingConfig.MaximumConcurrency: 5` on the SQS event
  in `template.yaml` replaces `ReservedConcurrentExecutions: 10`. It caps only
  the poller and reserves nothing from the account pool.

- **`up.sh` preflight.** Step 1 stops unless `./mvnw -v` reports JDK 21, and
  `envsubst` is no longer required. The step logic lives in `deploy/aws/lib.sh`
  functions, which `deploy/aws/selftest.sh` checks against stubbed tools.

- **`demo.sh` dates.** It books flights departing 30 days ahead, computed with
  BSD or GNU `date`, where a fixed date would have expired. Act 8 names every
  anonymous path, and the H2 reset line prints only against localhost.

### Fixed

- **Fractional numbers were truncated.** Jackson read `"seats": 2.7` as 2, so
  the booking was for two seats. In `src/main/resources/application.yml`,
  `accept-float-as-int: false` under `spring.jackson.deserialization` now
  rejects a fractional value for any whole-number field in a JSON body. The
  response is a 400 `MALFORMED_REQUEST`.

- **YAML bodies skipped the settings.** swagger-core puts a YAML reader on
  the classpath, and no `spring.jackson` setting reaches it, so `seats: 2.5` in
  an `application/yaml` body still booked two seats. The three writes declare
  `consumes = application/json` and answer any other body with 415
  `UNSUPPORTED_MEDIA_TYPE` (`BookingControllerTest#yamlBodyReturns415`,
  `FlightControllerTest#yamlBodyReturns415`).

- **Quoted numbers, numbered enums.** `allow-coercion-of-scalars: false`
  under `spring.jackson.mapper` rejects `"seats": "2"`, and
  `fail-on-numbers-for-enums: true` under `spring.jackson.datatype.enum`
  rejects `"status": 4`, which used to cancel the flight. Both are 400
  `MALFORMED_REQUEST` (`FlightControllerTest#statusAsANumberReturns400`).

- **Epoch departure times.** `CreateFlightRequest.departureTime` reads through
  `IsoInstantDeserializer`, which accepts only an ISO-8601 string. A number
  used to be read as epoch seconds, so a value in milliseconds landed in the
  year 58971 and passed `@Future`. An offset such as `+05:30` still works
  (`FlightControllerTest#departureTimeMustBeAnIsoString`).

- **Unencoded `Location` headers.** `FlightController#create` and
  `BookingController#book` now build `Location` with `UriComponentsBuilder`, so
  the value is always encoded.

- **Page overflow was a 500.** `SortPolicy.stable` rejects `page * size` past
  `Integer.MAX_VALUE` before any query, as 400 `MALFORMED_REQUEST` with
  `page * size must not exceed 2147483647.`
  (`ErrorContractTest#pagePastTheLastAddressableRowIsABadRequest`).

- **405 and 415 headers.** `GlobalExceptionHandler` copies the framework's
  headers in `handleSpringWebError`, so a 405 carries `Allow` and a 415 carries
  `Accept`.

- **Errors without a JSON type.** Every `GlobalExceptionHandler` and
  `ApiErrorController` response sets `Content-Type: application/json`. `/error`
  with an XML `Accept` used to answer an empty 406, and now returns its 404 in
  JSON.

- **Unknown publisher, unclear failure.** An unknown `app.events.publisher`
  stopped startup with `NoSuchBeanDefinitionException`. `EventProperties` now
  fails first with `app.events.publisher must be one of [log, sqs], not "<value>"`
  (`EventPropertiesTest#applicationStartupNamesTheProperty`).

- **Lost-race recovery hid errors.** When `BookingService.book` finds no
  booking holding the key after a failed insert, it now rethrows the original
  violation with the `IllegalStateException` attached as suppressed. It logs a
  WARN ending `not a lost race`
  (`BookingServiceTest#aViolationWithNoWinnerIsRethrown`).

- **Lambda seat counts.** The `BookingEvent` constructor rejects `seats` below 1
  with `booking event has <n> for 'seats'; expected at least 1`, and the mapper
  disables `ACCEPT_FLOAT_AS_INT` and `ALLOW_COERCION_OF_SCALARS`. A `"2"`,
  `2.9`, `0` or `-3` now becomes a batch item failure and reaches the DLQ.

- **Lambda log lines.** `BookingEventHandler.printable` replaces control, format
  and line-separator characters with `?`, and caps a value at 1,000 characters
  plus `...`. It guards the `bookingId` on the success line and the exception
  message on the `FAILED` line.

- **Contract test mapper.** The Lambda's `BookingEventContractTest` now reads
  through `BookingEventHandler.MAPPER`, so the contract file runs against the
  handler's own configuration.

- **`sam build` failed.** SAM builds in a scratch copy of `lambda/`, where the
  tests' `../events` and `../contracts` are missing. `template.yaml` now points
  `CodeUri` at `lambda/target/booking-event-handler.jar`, which `up.sh` builds
  with `./mvnw` before `sam deploy --template-file template.yaml`.

- **The ECR lookup failed open.** "Is this commit already in ECR?" read any
  error, a missing permission included, as `exists=false`. It now runs
  `deploy/aws/ecr-image-exists.sh`, which says `exists=false` only for
  `ImageNotFoundException`, and `deploy/aws/foundation.yaml` grants the CI role
  `ecr:DescribeImages`.

- **`up.sh` gave up early.** Step 10 ran `kubectl wait` at once, which fails
  while `deployment/flight-ops` does not exist. It now waits up to 30 minutes
  for CI to create it, then up to 20 for the rollout, and the timeout names
  `DEPLOY_ENABLED` and `main`.

- **`up.sh` stack checks.** Step 6 stops with the commands to run when the data
  stack is in `ROLLBACK_COMPLETE`, another unusable state or still in progress.
  `stack_status` fails closed on a read error, and the `JdbcUrl` output must
  start with `jdbc:postgresql://`, where a missing output used to come back as
  the string `None`.

- **The access policy was assumed.** `grant_namespace_access` in
  `deploy/aws/lib.sh` reads the association back with
  `list-associated-access-policies`, and stops unless `AmazonEKSEditPolicy` is
  scoped to the namespace. Before, the ok line followed a call whose failure the
  script tolerated.

- **`down.sh` used the caller's kubeconfig.** It now writes a temporary one for
  every `kubectl` and `helm` call, and removes it on exit. When the cluster is
  unreachable, it skips the controller and namespace steps.

- **Unconfirmed deletes in `down.sh`.** Each stack and the cluster print
  `✓ … deleted` only after the wait succeeds, and warn otherwise. With
  `--keep-foundation`, the stacks check and the tag catch-all leave out the
  foundation's own resources, so a kept foundation no longer fails the sweep.

- **`cost-check.sh` stopped early.** The first Cost Explorer pipeline ends in
  `|| true`, so the tagged, forecast and Budgets sections still run.

The other fixes are to documentation only, and change no behaviour.

- The README listed 20 error codes, and there are 21. It was missing
  `BAD_REQUEST`, which `ApiErrorController` returns for any other client error
  forwarded to `/error`. Its `UNKNOWN_SORT_PROPERTY` entry still described the
  behaviour from before `SortPolicy`.

- ADR 0002, ARCHITECTURE.md, the README and `application.yml` said the
  PostgreSQL dialect discards a JPA lock-timeout hint. Hibernate 7.4.5 applies
  it with `SET LOCAL lock_timeout`. The decision stands on a different reason,
  and ADR 0002 carries a dated correction.

- The outbox retry window was described as about twenty minutes, and once as
  ten seconds. Two seconds doubling to a five-minute ceiling over ten attempts
  is 810 seconds, about thirteen and a half minutes. The comment in
  `V7__outbox_next_attempt_at.sql` still says twenty, because an applied
  migration cannot be edited without changing its Flyway checksum.

- The comment in `V2__seat_and_route_invariants.sql` says `FlightService`
  rejects an origin equal to the destination. The check is `@DistinctEndpoints`
  on `CreateFlightRequest`, and the comment stays for the same checksum reason.

- The build-log note in the README called Hibernate's duplicate-key lines
  ERRORs. They are WARNs.

- The CI workflow comments said Flyway V1–V6 (it is V1–V8) and described branch
  protection as already enforced.

- Comments and Javadoc across the code were trimmed and checked against the
  behaviour above, and every Markdown document was rewritten in plain language.
  The bug stories moved to `NOTES.md`.

### Security

- **No password in the log.** `ApiSecurityProperties` checks the prefix in its
  constructor, so Boot's failure report no longer echoes a rejected value. The
  message names the property and ends with `<VAR> is not set` (`API_PASSWORD`
  or `OPS_PASSWORD`) or `the value is not shown`.

- **Narrower publish policy.** `SqsPublishPolicy` in
  `deploy/aws/foundation.yaml` grants `sqs:SendMessage` only, the one SQS call
  the service makes. It dropped `sqs:GetQueueUrl` and `sqs:GetQueueAttributes`.

- **The image fails closed.** A bare `docker run` used to serve H2 with the dev
  passwords. The `Dockerfile` now sets `SPRING_PROFILES_ACTIVE=prod`, so the
  image stops with `'url' must start with "jdbc"` until it gets a database.

## 1.1.0 — 2026-09-23

The hardening pass. Most of it makes a claim checkable: a gate that fails, a
document verified against the code it describes, a teardown script that proves
it left nothing behind. Apart from authentication, the page-size cap, the
idempotency-key pattern and the bug fixes under Fixed, little of it changes
what the API does. One change removes a field from a response (see Removed).

### Added

- **Authentication and authorisation.** HTTP Basic is always on, and JWT bearer
  tokens are accepted when an issuer is configured. Both feed one
  `authorizeHttpRequests` block, so a rule can only be wrong in one place
  ([adr/0005](adr/0005-one-rule-set-for-basic-and-jwt.md)).

  - Every `/api/**` call needs credentials: reads need `flights:read` and
    writes need `flights:write`.

  - `/actuator/**` needs `ROLE_OPS`, and the health probes stay open because
    the kubelet has no credentials.

- **A transactional outbox.** The booking transaction no longer makes a network
  call. It writes a row, and `OutboxPublisher` drains it afterwards with
  `SELECT … FOR UPDATE SKIP LOCKED` ([adr/0001](adr/0001-transactional-outbox.md),
  `src/main/resources/db/migration/V5__outbox.sql`).

- **Consumer-driven contract tests.** The service and the Lambda are separate
  Maven builds with no shared module, so nothing checked that they agreed about
  the event. Both sides now assert against `contracts/booking-created-v1.json`
  ([adr/0008](adr/0008-standalone-lambda-consumer.md)).

- **Correlation ids, metrics, structured logs.** `RequestIdFilter` puts an id
  in the MDC and on every response as `X-Request-Id`, including 401 and 403.
  `BookingMetrics` and `OutboxMetrics` publish counters and gauges that answer
  "is it working" without a log read, and the prod profile emits ECS JSON
  ([adr/0011](adr/0011-correlation-ids-and-metrics.md), `OPERATIONS.md`).

- **The trace survives the queue.** The booking's W3C `traceparent` is captured
  at booking time and stored on the outbox row
  (`src/main/resources/db/migration/V6__outbox_traceparent.sql`). It travels as
  an SQS message attribute and the Lambda logs it, so one id follows a booking
  from the HTTP request to the DynamoDB write (fixture:
  `events/sqs-with-trace.json`).

- **An OpenAPI document**, generated from the controllers so it cannot drift.
  `OpenApiConfig` adds what springdoc cannot infer. The document and the
  Swagger UI need no credentials, but every operation in them still does
  ([adr/0012](adr/0012-openapi-public-read.md)).

- **Build gates.** `maven-enforcer` requires JDK 21 and Maven 3.9+, and allows
  no duplicate or downgraded dependency versions. JaCoCo sets a bundle minimum of
  80% line and 50% branch, a CycloneDX SBOM lands at `target/bom.json`, and the
  nine ArchUnit rules in `ArchitectureTest` fail the build
  ([adr/0014](adr/0014-quality-gates.md)).

- **Documentation that is gated.** `ARCHITECTURE.md`, `OPERATIONS.md`,
  `SECURITY.md`, `CONTRIBUTING.md`, `DEPLOYMENT.md` and fourteen ADRs under
  `adr/`. Three checkers fail CI: `scripts/refcheck.py` (every backticked path
  and `path#symbol` must resolve), `scripts/linkcheck.py` (every relative link
  and heading anchor) and `scripts/sweeps.sh`. Counts come from
  `scripts/numbers.sh`, never from memory.

- **Reproducible AWS path, provable teardown.** `deploy/aws/` holds the
  CloudFormation for ECR, the GitHub OIDC role and the budgets
  (`deploy/aws/foundation.yaml`), the RDS stack (`deploy/aws/data.yaml`) and
  the twelve-step `deploy/aws/up.sh`. `deploy/aws/down.sh` ends with a
  PASS/FAIL sweep over every resource type and exits non-zero if anything
  survives ([adr/0009](adr/0009-eksctl-and-sam-over-terraform.md),
  [adr/0010](adr/0010-region-ap-south-1.md)).

  - `deploy/aws/render-aws.sh` is the one render path, shared by CI and by a
    person checking what is about to be applied.

- **This file**, and `.github/PULL_REQUEST_TEMPLATE.md`, which asks what breaks
  if the change is wrong.

- **`build-info`**, so `/actuator/info` and the OpenAPI document report the real
  `pom.xml` version instead of an empty object and a hard-coded string.

### Changed

- **Spring Boot 3.5 → 4.1.1**, which brings Spring Framework 7, Jackson 2 → 3
  (the group id changed), the technology-per-module split, Spring Security 7
  and the JUnit Platform 6 line ([adr/0007](adr/0007-spring-boot-4.md)).
  Dependabot opened this bump and its build failed, because bumping the parent
  alone does not work.

- **Kubernetes manifests are kustomize**: `k8s/base`, an `k8s/overlays/aws`
  overlay and an ingress component, replacing one flat file.
  `readOnlyRootFilesystem` is now true, with an explicit `emptyDir` mount for
  the only path the JVM writes to.

  - `k8s/namespace.yaml` sits outside the base. The deploy role's access entry
    is namespace-scoped, and CI must never apply a cluster-scoped object.

- **CI is six named jobs** (`build`, `infra-lint`, `trivy-fs`,
  `dependency-review`, `docs-check`, `deploy`) running in parallel. A red square
  names what broke before you open the log, and branch protection can require
  the gates one by one.

  - Every action is pinned to a commit SHA. CodeQL runs in its own workflow, on
    a schedule as well as on push, because new queries find old code.

- **Page size is capped** at 100 (`spring.data.web.pageable.max-page-size`). A
  caller asking for 5,000 rows used to get them.

- **Time comes from a `Clock` bean** everywhere except `Booking.createdAt`, the
  documented exception. The ArchUnit rule allows the wall clock only inside the
  `entity` package, and `Booking.createdAt` is the only place there that reads
  it.

### Removed

- **`idempotencyKey` from `BookingDto`.** The list endpoint returns every
  booking on a flight, so anyone holding `flights:read` could read the keys of
  bookings they did not make and replay against them. The caller chose the key
  and already has it, so returning it bought nothing. The column is unchanged;
  only the response shape is. This is the only field this release removes from
  a response.

### Fixed

- **Last-seat replay race.** Two requests with the same idempotency key raced
  for the last seat, and the loser got 409 `INSUFFICIENT_SEATS` for a booking
  that had been made. `BookingWriter.insertNewBooking` now re-reads the key
  under the flight row lock and reports a loser through
  `LostIdempotencyRaceException`, so `BookingService.book` returns the winner's
  201.

  - Pinned by
    `BookingIdempotencyTest.java#racingCallersOnTheLastSeatAllGetTheSameBooking`.

- **Unused partial index dropped.**
  `src/main/resources/db/migration/V8__drop_unused_active_booking_index.sql`
  drops `idx_bookings_active`. No query repeats its predicate, so PostgreSQL
  never used it and it was pure write cost.

- **Zero-seat bookings in the Lambda.** A message with no `seats` field was
  stored as a booking for nought seats. `FAIL_ON_NULL_FOR_PRIMITIVES` now makes
  it a batch item failure, so it lands in the DLQ.

- **Log-safe idempotency keys.** `idempotencyKey` now accepts only
  `[A-Za-z0-9._:-]`, the class `RequestIdFilter` enforces on `X-Request-Id`.
  The key is echoed into log lines, and a newline in it could forge an entry.

- **Unique-constraint loser got 409.** Two identical requests raced, and the
  loser caught the unique-constraint violation and reported a conflict. The
  correct answer is the winner's booking, with 201.

- **`LazyInitializationException` on `GET /api/v1/bookings/{id}`.** The entity
  was mapped outside the session.

- **`findBookable` missed DELAYED flights.** A delayed flight is still bookable,
  but the query excluded it. No endpoint calls it, so the API was unaffected;
  `FlightRepositoryTest` keeps it as an example query.

- **`MALFORMED_REQUEST` echoed exception text.** It sent raw exception text back
  to the caller, which leaks types and field names from the deserialiser.

- **Duplicate unique index on `flights.flight_number`.** It cost a write on
  every insert and guaranteed nothing the first index did not.

- **Poison row blocked the outbox.** The claim is `ORDER BY id`, so a row that
  always fails sits at the head of the queue forever. The claim query now
  carries `AND attempts < :maxAttempts`, and an operator re-drives a dead row by
  resetting `attempts` ([adr/0013](adr/0013-outbox-ceiling-and-retention.md)).

- **Outbox table grew without bound.** `OutboxPruner` deletes published rows
  past a retention window, in batches, so the delete cannot take a long lock.

- **Gates no commit could fix.** `shellcheck` is pinned to v0.11.0 instead of
  whatever the runner ships (0.10 and 0.11 disagree about
  `cmd && log … || true`). `dependency-review` probes for the repository's
  dependency graph, and warns instead of going red when the graph is off.

- **`linkcheck.py` missed badge targets.** A badge is a link whose label is an
  image link, and the old pattern stopped at the image's closing bracket. It
  checked the shields.io URL instead of the link, so every badge in the README
  had been unchecked since the day it was added.

- **Retry ceiling burned in seconds.** A transport error that fails fast (a bad
  queue URL, an expired credential) retried every row ten times in ten seconds.
  It dead-lettered the whole backlog before anyone could read an alert, and
  `outbox_pending` sat at zero throughout because the rows had already moved to
  dead.

  - `src/main/resources/db/migration/V7__outbox_next_attempt_at.sql` adds
    `next_attempt_at`, and the claim query skips a row that is waiting. A
    failure now backs off instead of racing the clock.

- **`demo.sh` failed its own preflight against a deployment**, and took `up.sh`
  down with it. The preflight required `GET /api/v1/flights/UA123` to return
  2xx, but the `prod` profile sets `app.seed.enabled: false`, so it got 404.
  `up.sh` runs the demo last under `set -euo pipefail`, so the run aborted at
  step 12 of 12, before printing the only plaintext copy of the generated API
  and ops passwords.

  - The demo now creates the flights it needs (201 or 409, both fine) and
    redacts credentials from the commands it echoes. A per-run suffix means a
    second run against the same database is not a pile of 409s.

  - `up.sh` prints the passwords at step 9, the moment the Secret exists, and
    treats a failing demo as a warning.

- **False passes in the teardown.** The final sweep of `deploy/aws/down.sh`
  reported `PASS` for any check whose AWS call *errored*, so an expired token
  looked like an empty account. The queries now fail closed and name the
  failure.

  - The teardown also deleted the account-wide `aws-sam-cli-managed-default`
    bucket, which belongs to every SAM project in the region. That is now
    opt-in behind `--delete-sam-bucket`.

  - It treated an unreachable cluster as "no ingress", and skipped, without an
    error, the step its own header calls the expensive mistake.

- **OIDC provider deleted on re-run.** A second run of `up.sh` deleted the
  GitHub OIDC provider the first run created, which breaks every later CI
  deploy. The provider is shared across the account and now carries
  `DeletionPolicy: Retain`.

- **Unstable paging.** Both list endpoints paged on a non-unique sort key, so
  two flights with the same departure time could appear twice or not at all
  across pages. `SortPolicy` appends `id` as a tie-breaker.

- **`/error` used Boot's default body.** It answered with Boot's error map
  instead of the documented `{code, message, timestamp}` envelope. Tomcat
  forwards to it *after* the security chain has finished, so every
  container-level 404 and 500 left the documented contract.

  - `exception/ApiErrorController` now serves it, with a generic message so the
    container's error text cannot leak an internal path or exception class. It
    is `@Hidden`, so it does not appear in the OpenAPI document.

- **`app.events.publisher` ignored its variable.** Outside `prod`, the base
  document hard-coded `log`. So the SAM-only recipe in DEPLOYMENT.md, which runs
  `APP_EVENTS_PUBLISHER=sqs ./mvnw spring-boot:run`, published nothing. It is
  now `${APP_EVENTS_PUBLISHER:log}`, and choosing `sqs` without a queue URL
  still fails at startup.

### Security

- The service no longer returns other callers' idempotency keys (see Removed).

- `readOnlyRootFilesystem: true`, `runAsNonRoot`, all capabilities dropped, and
  `automountServiceAccountToken` left to IRSA's projected token.

- Supply chain: Trivy scans the filesystem, and the image before it is pushed.
  CodeQL runs `security-extended`, every GitHub Action is SHA-pinned, and a
  CycloneDX SBOM is attached to every build.

- `SECURITY.md` documents the auth model, the 401/403 split, what data the API
  exposes, and what this service would get wrong if it had real users.

- **JWT audience validation, documented.** Bearer tokens are off, and when
  someone turns them on, `issuer-uri` alone is not enough. An issuer mints
  tokens for every application registered with it, so a token issued to another
  client of the same tenant arrives correctly signed and would be accepted.

  - `application.yml`, `config/SecurityConfig` and `SECURITY.md` now all name
    `spring.security.oauth2.resourceserver.jwt.audiences` beside `issuer-uri`,
    and so does the startup log line.

- **Checksums for CI's downloaded binaries.** `kubeconform` and `shellcheck` are
  fetched by tag from other people's repositories, the same mutability that
  SHA-pinning the actions avoids. CI now checks each download against a pinned
  sha256 before installing it.

## 1.0.0 — 2026-09-22

The service itself, and the two review passes that followed it.

### Added

- Flight inventory and bookings over HTTP: create, cancel, status transitions,
  paged reads.

- **The hard problem**: not overselling the last seat when two requests arrive
  at the same moment. A pessimistic row lock taken in a fixed order, plus a
  session `lock_timeout`, solves it ([adr/0002](adr/0002-pessimistic-locking.md)).
  A test puts twenty threads on five seats to prove it.

- **Idempotency** on booking creation: a unique key plus a SHA-256 fingerprint
  of the request body. The same key with a different body is a conflict, and
  never a replay of some other request
  ([adr/0004](adr/0004-request-fingerprint.md)).

- Flyway migrations V1–V4, JPA entities with `ddl-auto: validate` against them,
  and an H2 profile for a laptop, with the PostgreSQL path verified in CI.

- A standalone arm64 Lambda consumer, its SAM template, and `demo.sh`, which
  shows the behaviours worth seeing over real HTTP instead of as assertions.

- Dependabot, monthly and grouped, across both Maven modules, the Actions
  workflows and the Dockerfile base images.

### Fixed

- The findings of two review passes, recorded in the README at the time,
  including the ones that were not fixed. Several of them are the entries in
  1.1.0 above.
