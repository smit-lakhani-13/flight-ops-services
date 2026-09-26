# Changelog

What changed in each release and, where it matters, why.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the versions follow [semantic versioning](https://semver.org/spec/v2.0.0.html),
with the qualifications below.

**On 1.1.0 not being 2.0.0.** This release removes a field from an API response
(`idempotencyKey`, from `BookingDto`). Under strict semantic versioning a
removed response field is a breaking change to consumers, which would make this
a major release. It is 1.1.0 because 1.0.0 was never tagged, never
published and never consumed, so no client anywhere could break. From this
release on, a change to what the documented contract returns for a request it
accepts is a major, whether it is a response field, a status or an error code.
Starting to reject input the contract never allowed, such as a fractional seat
count or a flight number with a slash, is a fix.

**On the dates.** 1.0.0 was never tagged, so its entry was written afterwards
from the history and dated by its last commit. All dates are IST. 1.0.0 and
1.1.0 are split by theme (the service, then the hardening pass that followed)
and not at a single commit. So 1.1.0 lists some fixes committed
before 2026-09-22, and its Boot 4.1.1 upgrade came before some of the work in
1.0.0.

**What "released" means here.** A version number in `pom.xml` and a git tag. It
does not mean deployed. Nothing in this repository has ever run in AWS, and
[doc/DEPLOYMENT.md](doc/DEPLOYMENT.md) records that in a dated line that is
still blank.

## Unreleased

### Added

- **The AWS SDK code runs against emulators in CI.**
  `SqsEventPublisherElasticMqTest` publishes through `SqsEventPublisher` to
  ElasticMQ and reads the message back, body and attributes unchanged, with
  the SDK checking both MD5 digests. The Lambda's
  `BookingEventHandlerDynamoDbLocalTest` runs the handler against DynamoDB
  Local on a table keyed as `lambda/template.yaml` keys it: the fixture lands
  as one item per booking, a redelivered batch is refused by the condition
  and is not a failure, and a malformed body fails only its own message.
  Before, both halves were tested only against mocked clients, which cannot
  see a request the server would refuse. Both classes skip without Docker,
  and the step "The emulator tests ran" fails CI if either skipped. Neither
  talks to AWS. `scripts/numbers.sh` now counts the Lambda's skipped tests.
  Dependabot leaves the Lambda's Testcontainers version alone, as it does
  JUnit, because `lambda/pom.xml` copies the one Boot manages for the service.

### Changed

- **Version.** Both poms say `1.3.0-SNAPSHOT` until the next tag, so a build
  from `main` no longer reports itself as 1.2.0 in `/actuator/info` and the
  OpenAPI document.

### Fixed

- **Tests that proved less than their names said.**
  `BearerTokenChallengeTest#aSignedTokensScopeMapsOntoTheRules` signs an RS256
  token with the `flights:read` scope and checks that it can read a flight but
  cannot book or read metrics. Before, no test decoded a valid token, so a
  change to how the resource server turns scopes into authorities still passed
  the build. The no-session test now asserts that the request has no session:
  MockMvc never writes the session cookie, so the old `Set-Cookie` check could
  not catch a session. The PostgreSQL replay race checks that every caller
  gets the same booking back, as its name says.

- **Tests with an expiry date.** `ErrorContractTest` created flights departing
  in 2030 and 2031 against the real clock, so six of its tests would have
  started failing then. They now depart in 2099, like the suite's other fixed
  departure dates.

- **A field that broke two rules got a different message from call to call.**
  Hibernate Validator returns violations in no fixed order, and the handler
  kept whichever came first, so an empty idempotency key or airport code got
  `must not be blank` on one call and a size or character message on the next.
  `GlobalExceptionHandler#handleValidation` now keeps the most basic one: null,
  then blank, then size or range, then pattern. The idempotency key's pattern
  accepts an empty string, like the others, so an empty key is only blank.

- **Names that broke the idempotency check.** A `passengerName` with an
  unpaired UTF-16 surrogate, such as U+D800, was stored, and the request
  fingerprint turned it into `?`, so a name that differed only there, sent on
  the same key, was answered as a replay instead of a 409. It now gets
  `must not contain unpaired surrogates`. A name made only of no-break or
  zero-width spaces passed `@NotBlank` and now gets `must not be blank`.

- **The 400 descriptions in the OpenAPI document.** Both writes said a field
  with the wrong JSON type got `MALFORMED_REQUEST`, but Jackson turns a
  number or `true` sent for a text field into text, which then goes through
  the same validation as any other. The descriptions now name the seat count,
  and a text field sent as an array or an object, which Jackson still refuses.

- **The OpenAPI document asked for paging as one required object.** Neither
  `Pageable` parameter carried `@ParameterObject`, so springdoc published one
  required query parameter named `pageable` with none of the defaults, and
  Swagger UI's "Try it out" offered a sample with `sort=string`, which is
  `400 UNKNOWN_SORT_PROPERTY`. `FlightController#search` and
  `BookingController#byFlight` now carry it, and the document lists `page`,
  `size` and `sort` with their defaults. It does not show the cap of 100.

- **The OpenAPI document left out the 503 every operation can return.**
  `GlobalExceptionHandler#handleDatabaseUnavailable` answers any operation
  that cannot get a database connection, reads included, with
  `503 DATABASE_UNAVAILABLE`. Five operations declared no 503, and the other
  four named only `LOCK_TIMEOUT`. `OpenApiConfig#sharedResponses` now adds the
  code to every operation under `/api/`, `Retry-After` as a header on every
  503, and `X-Request-Id` as a header on every documented response, since
  `RequestIdFilter` sets it on every response the application handles.

- **Two response schemas disagreed with the JSON.** An active booking is sent
  with `"cancelledAt": null`, and the schema's plain `string` type refused the
  null; it is now `string` or `null`. `FlightDto.status` was an unconstrained
  string, and it now refers to a `FlightStatus` enum component, the one
  `StatusUpdate` reads. Neither the Java types nor the JSON changed.

- **A NUL inside a query filter was a 409 on PostgreSQL.** `?origin=J%00K`,
  `?destination=J%00K` and `?flightNumber=A%00B` reached the query, PostgreSQL
  refused the NUL with SQLState 22021, and the client got
  `409 DUPLICATE_REQUEST` telling it to retry a read that fails every time.
  H2 answered an empty page. `QueryParams#withoutControlCharacters` now
  answers a filter that holds a control character once trimmed with
  `400 MALFORMED_REQUEST`, before any query. Nothing else is checked, so
  `?origin=J-K` is still an empty page. As a backstop,
  `GlobalExceptionHandler#handleDataIntegrity` answers any SQLState class 22
  data error with `400 MALFORMED_REQUEST` rather than `DUPLICATE_REQUEST`.

- **The SBOMs described two applications as libraries.** The CycloneDX plugin
  types a module `library` unless told otherwise, and neither pom told it, so
  both `target/bom.json` files did. Both are typed `application` now. The
  service module also stops attaching its SBOM over the one the Spring Boot
  parent embeds in the jar, which logged a replace warning on every build.

- **infra-lint no longer floats with the SAM CLI.** `sam validate --lint` runs
  the cfn-lint bundled with the SAM CLI, and setup-sam installed the latest
  release on every run, so a new release could turn a required check red with
  no template changed. The workflow pins it at 1.166.2.

- **Two CI comments that misstated behaviour.** The CodeQL category never kept
  Trivy's results apart, because code scanning keeps each tool's results
  separately. Dependabot can move the build stage's JDK within Maven 3, and it
  is the enforcer that stops such a bump, by failing the image job.

- **The memory budget gave half of what the JVM uses outside the heap.** The
  deployment's comment allowed 128Mi for everything outside the heap. Native
  memory tracking on a laptop JDK 21, default profile, measured about 260 MiB
  committed there before thread stacks. At `MaxRAMPercentage=75.0` a heap near
  its 576Mi ceiling would push the container past its 768Mi limit, and the
  kernel would kill it with no `OutOfMemoryError` to log. The `Dockerfile` now
  gives the heap half the limit, and the comment shows the measurement. Its
  check was wrong too: native memory tracking is off in the image, so the
  comment now points at `kubectl top pod` and the actuator's non-heap figure.

- **The teardown's stack sweep missed a stack still deleting.** When the
  waiter gave up, `down.sh` said step 9 would list the stack, but the sweep
  asked only for five finished states. It now lists every state but
  `DELETE_COMPLETE`, and `deploy/aws/selftest.sh` has a case for it.

- **The up.sh hand-off, and the connection limit the documents gave.** The
  `up.sh` hand-off now says to rename the deploy job before `DEPLOY_ENABLED`
  is set, as the runbooks do. `deploy/k8s/base/hpa.yaml`, `doc/DEPLOYMENT.md`
  and `doc/OPERATIONS.md` said db.t4g.micro allows about 112 connections.
  It allows fewer than that, because `DBInstanceClassMemory` leaves out what
  the OS and RDS reserve, and they now say to read the limit with
  `SHOW max_connections`.

- **The SAM recipe in `lambda/template.yaml` is corrected.** It sets the
  region to `ap-south-1`, the one its teardown names, before the deploy. Its
  `sam local invoke` sets `DDB_TABLE=flight-status-events`, because SAM resolves
  `!Ref FlightEventsTable` to the logical ID locally, and it says to run the
  invoke after a deploy, since no table exists before one, as ADR 0009 now
  notes. The template, `up.sh` and `doc/DEPLOYMENT.md` said `sam deploy` reads
  `.aws-sam`; with `--template-file` it never does. The template drops its step
  that deleted it, and `up.sh` and `doc/DEPLOYMENT.md` keep the removal only so
  that a later bare `sam deploy` cannot pick up a stale build. The recipe has
  still never been run.

- **Documents that said more than the code does.** `SECURITY.md` called the
  idempotency key a client's private token, but the service logs it on a
  replay; it is now a client-chosen key that must hold nothing private. The
  enforcer's upper JDK bound had no stated reason, and `CONTRIBUTING.md`,
  ADR 0007 and both poms now say it keeps builds on the 21 that CI, the image
  and the Lambda runtime use. The outbox drain and the pruner share one
  scheduler thread, and `application.yml` now says so and why that is
  acceptable. The ADR index now marks 0016, the Oracle port, as a proposal
  from documentation, never built or run.

- **Wrong counts and stale references in the documents.** `doc/OPERATIONS.md`
  promised one `grep` and showed two searches. The defect log gave the poisoned
  sort key as 29 characters, the length of its timestamp half, and said CI's UTC
  zone lets a zone bug ship green without noting that both Surefire runs now pin
  `Asia/Kolkata`. `scripts/demo.sh` cited defect numbers the log does not have,
  and now names its entries; its Act 8 now counts `/error` among the anonymous
  paths. The ADR index gives the dates its records were written in one sentence
  instead of timing each one, and the binding row in `doc/ARCHITECTURE.md` names
  the passenger name checks above. Markdown prose lines over 80 columns are
  rewrapped where they can break, except in `README.md` and the released
  sections here.

## 1.2.0 — 2026-09-26

The [fourth review pass](doc/DEFECT-LOG.md#fourth-review-pass), a full audit
of the service, the scripts and every document. Most of the fixes deal with
input the service misread or answered with a 500, a script that could fail
without saying so, or a sentence that disagreed with the code.

Two changes under Changed refuse requests that 1.1.0 served. Request fields
are stricter: a flight number with anything but letters and digits (padding is
still trimmed), an airport code with anything but letters, or a passenger name
with a control character now gets a 400. The 1.1.0 schema checked only their
length, but no real flight number, airport code or name needs those
characters, so this is a fix under the rule above. And the API is JSON only: a
YAML `Accept` gets a 406 where it used to get YAML, and a `POST` or `PATCH`
whose `Content-Type` is not `application/json` gets a 415. The 1.1.0 OpenAPI
document listed only `application/json` for request and response bodies, so
this too refuses only requests that contract never allowed. Some fixes change
a response too.
Misread input, such as a fractional or quoted seat count, now gets a 400, and
a request that finds no database gets a 503 `DATABASE_UNAVAILABLE` where it
used to get a 500. Each replaces a wrong answer or an unplanned 500, so each
is a fix.

The deep documents moved under `doc/`, beside a new `doc/api.md` and a new
defect log, `doc/DEFECT-LOG.md`, which now holds the bug stories the 1.1.0
README told. The README was rewritten to point at the file that owns each
subject.

### Added

- **Password self-check.** `SecurityConfig.assertVerifiable` stops startup on a
  hash it cannot verify, where logins got a 500 (`PasswordVerifiabilityTest`).

- **Bearer challenge.** `JsonAuthenticationEntryPoint` answers a bad token with
  `WWW-Authenticate: Bearer`, other 401s `Basic` (`BearerTokenChallengeTest`).

- **Unhandled 500s logged.** `ApiErrorController` logs a 5xx with an exception
  at ERROR, under the id in the 500's `X-Request-Id` header.

- **New CI gates.** Steps fail unless all three PostgreSQL test classes ran
  with nothing skipped, and fail if the SAM template misses the Lambda jar or
  the image starts without a database. The infra-lint job runs
  `deploy/aws/selftest.sh` against stubbed tools.

- **Race tests.** `BookingIdempotencyTest#oneKeyRacedAcrossTwoFlightsBooksOnce`
  races one key across two flights, and every race test in that class starts
  through the `startTogether` latch. `OutboxPrunePostgresTest` races two
  outbox claims on PostgreSQL.

- **Other tests.** `FlightControllerTest#existingFlightNumberReturns409` and
  `#staleFlightWriteReturns409` are the first to assert a response carrying
  `DUPLICATE_FLIGHT` and `CONCURRENT_MODIFICATION`, and
  `#flightNumberRaceReturns409` replaces the misnamed
  `concurrentDuplicateKeyReturns409`, deleted from `BookingControllerTest`.
  `SecurityRulesTest#readScopeCannotWrite` also sends a PATCH.

- **The image is built in CI.** A new `image` job builds the `Dockerfile` on
  every push or pull request to `main`, fails unless it stops for want of a
  database, and logs the image size. It has no cloud or registry credentials
  and never pushes. The deploy job now needs it.

- **The image is scanned in CI.** The `image` job's Trivy scan fails on a
  fixable CRITICAL vulnerability; only the never-run deploy job scanned an
  image before.

- **The README's ADR count is checked.** The docs-check job runs
  `scripts/numbers.sh --check-readme`, which fails when the README or
  `adr/README.md` disagrees with the files in `adr/`.

- **PostgreSQL's lock timeout has a test.** `LockTimeoutPostgresTest` waits out
  the 3 s `lock_timeout` on PostgreSQL 17 and checks `55P03` and the 503.

- **Event transport decision.** [ADR 0015](adr/0015-event-transport.md) says why
  events go to an SQS standard queue and what a JMS broker would cost; the
  broker side is from documentation and was never built or run.

- **An Oracle port from documentation, never built or run.**
  [ADR 0016](adr/0016-oracle-port.md) is a proposal from Oracle's and
  Hibernate's documentation, never built or run: two outbox queries, the
  lock-wait bound and the migration spellings.

- **Two SQS alarms.** `BookingEventDLQAlarm` and `BookingEventBacklogAlarm` in
  `lambda/template.yaml` watch the dead-letter queue and the backlog. Neither
  has a notification target; both are linted in CI and have never been
  deployed.

- **The cancellation's 503 has a test.** `LockTimeoutTest` gets 503
  `LOCK_TIMEOUT` with `Retry-After` for a cancellation on a held row.

- **Outbox rollback tested.** `OutboxTest#aRolledBackBookingLeavesNoEvent` finds
  no booking or event row after a rollback.

### Changed

- **Layout.** The SAM template and its SQS fixtures moved into `lambda/`, the
  eksctl file into `deploy/aws/` and the demo into `scripts/demo.sh`, and CI's
  SAM checks name the new path. The empty `.trivyignore` is gone, with both
  `trivyignores` inputs in CI.

- **Runner images pinned.** Jobs run on `ubuntu-24.04`, since `ubuntu-latest`
  moves to Ubuntu 26.04 between 19 October and 19 November 2026.

- **The deploy job's name.** It reads `deploy (gated off)`, not a bare `deploy`.

- **CodeQL can be started by hand.** `codeql.yml` gains `workflow_dispatch`.

- **Stricter request fields.** Flight numbers take only letters and digits
  (`CreateFlightRequest.FLIGHT_NUMBER`, trimmed), airport codes only letters
  and passenger names no control characters, else 400 `VALIDATION_FAILED`.

- **JSON only.** `FlightController` and `BookingController` produce only
  `application/json`: an XML or YAML `Accept` gets 406 `REQUEST_REJECTED`. The
  three writes with a body also read only JSON (see Fixed).

- **OpenAPI failure responses.** Every operation documents 401 and 403, each
  write with a body 415 and each write to an existing flight row 503
  (`OpenApiTest#theDocumentCoversTheApiAndItsFailures`).

- **Password prefixes.** `ApiSecurityProperties` accepts any `{id}` without
  braces or spaces, such as `{pbkdf2@SpringSecurity_v5_8}`.

- **Health details for ops.** `management.endpoint.health.roles: OPS` shows the
  components to ops on every profile and to no one else.

- **Log lines.** `logging.include-application-name: false` prints the service
  name once; the `JsonAccessDeniedHandler` WARN is reworded.

- **Configuration trimmed.** `spring.jpa.properties.hibernate.order_inserts`,
  useless with IDENTITY ids, is gone, as is the `/v3/api-docs` entry in
  `SecurityConfig.DOC_PATHS`, which `/v3/api-docs/**` already covers.

- **Seat counts required in the schema.** `seats` and `totalSeats` are
  `required` in the served document (`OpenApiTest#seatCountsAreRequired`), and
  its 400, 415 and `/error` descriptions name more of the cases the code
  answers.

- **Wall-clock rule.** `time_comes_from_the_clock` in `ArchitectureTest` exempts
  nothing, as `Booking` now gets `createdAt` from `BookingWriter`'s `Clock`,
  and flags any `java.time` `now()` without a `Clock`, `new Date()` and
  `Calendar.getInstance()`. It replaces 1.1.0's
  `the_wall_clock_is_read_only_by_entities`.

- **Test builds.** Surefire runs with `-Duser.timezone=Asia/Kolkata`, so a test
  leaning on the system zone fails on a UTC runner too. The PostgreSQL tests use
  `org.testcontainers.postgresql.PostgreSQLContainer`, and
  `BookingIntegrationTest#migrationRanAndSchemaValidates` checks V1 to V8.

- **Lambda concurrency.** `ScalingConfig.MaximumConcurrency: 5` replaces
  `ReservedConcurrentExecutions: 10`; it caps the poller and reserves nothing.

- **`up.sh` preflight.** Step 1 checks for JDK 21 and eksctl 0.184.0 or later
  and no longer needs `envsubst`. The checks are `deploy/aws/lib.sh` functions
  that `deploy/aws/selftest.sh` tests in CI against stubbed tools.

- **`down.sh` catch-all.** It leaves out the OIDC provider the foundation keeps.
  Run in ap-south-1, it lists no IAM resource, which AWS reports from
  us-east-1, so a leftover IAM role passes.

- **Sweep patterns from a secret.** `scripts/sweeps.sh` no longer carries its
  own privacy patterns and adds a built-in check for absolute home-directory
  paths. The privacy patterns come from `SWEEP_PATTERNS`, which CI fills from a
  secret: a push or manual run without it fails; a pull request from a fork or
  Dependabot, and a local run without the variable, skip that part.

- **`demo.sh`.** It books flights 30 days ahead with BSD or GNU `date`, where a
  fixed date would have expired. Acts 2 and 4 book as `Test Passenger`, Act 8
  also names the OpenAPI document and Swagger UI as anonymous, and the H2 reset
  line prints only against localhost.

- **Why one service.** `doc/ARCHITECTURE.md` says why bookings and flights
  share a service and the Lambda does not, and the README and the pom say
  service, not microservice.

- **Idempotency keys never expire, and the README now says so.** The trade-offs
  table gives the reason and what a retention window would change.

- **ArchUnit in the test group.** Dependabot's root `test` group now includes
  `com.tngtech.archunit:*`.

- **Linguist rule removed.** `.gitattributes` no longer sets
  `*.java linguist-language=Java`, which changed nothing.

- **The deep documents moved under `doc/`.** The bug stories the 1.1.0 README
  held are now in `doc/DEFECT-LOG.md`; README, CHANGELOG, CONTRIBUTING and
  SECURITY stay put.

- **The kustomize tree moved under `deploy/`.** `k8s/` is now `deploy/k8s/`; the
  real Secret's `.gitignore` rule is `**/secret.yaml`, safe from moves.

- **The README is rewritten.** Under 200 lines, it leads with what is hard in
  the design and what has run; detail moved to the file that owns it.

### Removed

- **Queries only tests called.** `FlightRepository` loses five queries, among
  them `findBookable` and `findNextTen`, and `FlightStatus` loses
  `bookableStatuses`: a method only a test calls is a test of nothing. Three of
  their tests go, and the fourth now checks the paged search.

### Fixed

- **Fractional numbers were truncated.** `"seats": 2.7` booked two seats;
  `accept-float-as-int: false` in `application.yml` makes it a 400.

- **YAML bodies skipped the settings.** `spring.jackson` never reached YAML, so
  a `POST` or `PATCH` sent as anything but `application/json` is now a 415
  (`BookingControllerTest#yamlBodyReturns415`).

- **Quoted numbers, numbered enums.** `"seats": "2"` and `"status": 4`, which
  cancelled flights, are 400 (`FlightControllerTest#statusAsANumberReturns400`).

- **Epoch departure times.** Numbers, once read as epoch seconds, are refused
  (`FlightControllerTest#departureTimeMustBeAnIsoString`).

- **Departure times past the microsecond.** A 201 and a later GET disagreed;
  `Flight` now truncates (`FlightTest#departureTimeIsTruncatedToMicroseconds`).

- **A missing `Content-Type` was named `'null'`.** The 415 now says there is
  none (`BookingControllerTest#missingContentTypeReturns415`).

- **A multipart `Content-Type` with no boundary was a 500.**
  `DispatcherServlet` parsed it on every path, so the parser is off
  (`ErrorContractTest#multipartParsingIsOff`).

- **`@Future` read the JVM clock.** `TimeConfig.validationClock` hands Hibernate
  Validator the `Clock` bean (`ValidationClockTest`).

- **Unencoded `Location` headers.** `FlightController#create` and
  `BookingController#book` build them with `UriComponentsBuilder`.

- **Page overflow was a 500.** `SortPolicy.stable` makes it a 400
  (`ErrorContractTest#pagePastTheLastAddressableRowIsABadRequest`).

- **`ignorecase` on a number or a time was a 500 on the bookings list.**
  `SortPolicy.stable` drops it on non-text sorts
  (`ErrorContractTest#ignoreCaseOnANonTextPropertyIsDropped`).

- **Departure years past 9999.** A year past 294276 AD, which PostgreSQL
  cannot store, drew a 409 that said retry. Any year past 9999 is now a 400
  (`FlightControllerTest#departureTimeAfterYear9999IsRejected`).

- **No database was a 500.** It is now 503 `DATABASE_UNAVAILABLE` with
  `Retry-After: 1` (`FlightControllerTest#noDatabaseConnectionReturns503`).

- **A 405 without `Allow`, a 415 without `Accept`.** `GlobalExceptionHandler`
  copies the framework's headers in `handleSpringWebError`.

- **Errors without a JSON type.** Each error response is now `application/json`,
  and `/error` no longer answers an XML `Accept` with an empty 406.

- **Unknown publisher, unclear failure.** `OutboxPublisher` now takes
  `EventProperties` first, so an unknown `app.events.publisher` stops startup
  on that record's message, which names the property, not on a missing
  `EventPublisher` bean
  (`EventPropertiesTest#applicationStartupNamesTheProperty`). It also logs
  `Outbox publisher started with event transport '<mode>'` at INFO.

- **Lost-race recovery hid errors.** A violation with no winner is rethrown
  (`BookingServiceTest#aViolationWithNoWinnerIsRethrown`).

- **Lambda seat counts were checked only for presence.** The `BookingEvent`
  constructor and the mapper now refuse `"2"`, `2.9`, `0` and `-3`, which reach
  the DLQ.

- **The contract test used its own mapper.** The Lambda's
  `BookingEventContractTest` reads through `BookingEventHandler.MAPPER`.

- **`sam build` could not build the Lambda.** Its scratch copy lacks
  `../contracts`, so `CodeUri` names the shaded jar that `up.sh` builds first.

- **The ECR lookup failed open.** Any error read as `exists=false`;
  `deploy/aws/ecr-image-exists.sh` says so only for `ImageNotFoundException`,
  and `deploy/aws/foundation.yaml` grants the CI role `ecr:DescribeImages`.

- **`up.sh` gave up early.** Step 10 waits up to 30 minutes for CI to create
  `deployment/flight-ops` before it waits for the rollout.

- **`up.sh` took an unusable data stack.** Step 6 stops on `ROLLBACK_COMPLETE`
  and other bad states, `stack_status` fails closed on a read error, and a
  missing `JdbcUrl` is no longer read as `None`.

- **The access policy was assumed.** `grant_namespace_access` in
  `deploy/aws/lib.sh` reads the association back before it reports ok.

- **`down.sh` used the caller's kubeconfig.** It writes a temporary one, removes
  it on exit, and skips the controller and namespace steps when the cluster is
  unreachable.

- **Unconfirmed deletes in `down.sh`.** Each stack and the cluster are reported
  deleted only after the wait succeeds, and a kept foundation no longer fails
  the sweep.

- **`cost-check.sh` stopped early.** Its first Cost Explorer pipeline ends in
  `|| true`, so the later sections still run.

- **`down.sh` found dead credentials late.** It ran every delete step and
  failed only at the sweep. Now it stops at once with
  `AWS credentials are not usable`, as `up.sh` and `cost-check.sh`, which run
  under `set -e`, already did.

- **An interrupted `up.sh` run.** A re-run would have gone on without the
  missing addons, OIDC provider or node group; step 4 creates them. Step 9
  prints the commands to set a new database password when the run that created
  the data stack stopped before writing it.

- **The `sts` module was missing.** The credential chain needs it for IRSA's
  token; `pom.xml` adds it (`AwsConfigTest#stsModuleIsOnTheClasspath`).

- **A failed stack read ended `up.sh` with no message.** `stack_output` prints
  the CLI's error and stops, and returns nothing for a missing stack.

- **A failed metrics-server create read as installed.** `ensure_metrics_server`
  looks the addon up first, creates it only when EKS reports it missing, and
  stops on any other failure.

- **The daily budget could not fire.** `DailyBudgetUsd` in
  `deploy/aws/foundation.yaml` defaulted to $12, which alerted at $9.60, above
  the $7.72 a day the stack would cost; at $8 it alerts at $6.40.

- **What automount turns off.** `deploy/k8s/base/serviceaccount.yaml` said that
  setting `automountServiceAccountToken` to false removes the token volume IRSA
  reads, and the 1.1.0 Security notes gave that as the reason to leave it on.
  Setting it to false removes only the Kubernetes API token; the EKS pod
  identity webhook adds its own volume. The comment now says so.

- **Tests that claimed more than they checked.** Oversell tests whose display
  names spoke of a rollback now say the oversell is refused before anything is
  written, and the `OutboxTest` one is renamed `anOversellWritesNoEvent`.
  `ErrorContractTest#cancellationReturnsSeatsExactlyOnce` now keeps a seat
  sold, and `FlightServiceTest#searchChoosesTheRightQuery` runs the
  destination-only search.

- **Replays were said to repeat the first response.** The OpenAPI description
  of `POST /api/v1/bookings` and ADR 0004 now say a replay returns the booking
  as it is now, `cancelledAt` included
  (`ErrorContractTest#replayAfterCancellationDoesNotRebook`).

- **`.dockerignore` missed nested Markdown.** It lists `**/*.md` and leaves out
  `deploy/`; the image is unchanged.

- **The link check dropped every underscore.** `scripts/linkcheck.py` built
  anchors without them, so it reported a link to a heading such as
  `outbox_dead > 0` in `doc/OPERATIONS.md` as broken where GitHub resolves it.
  It now drops only the underscores that mark emphasis.

- **Documents that disagreed with the code.** The README, SECURITY.md,
  CONTRIBUTING.md, `deploy/aws/README.md`, `doc/`, several ADRs and the SAM
  template's `Description` no longer miscount, overstate the code or speak of
  a deployment that does not exist. The OpenAPI description of the flight
  status change now names `DELAYED` and a departure without `BOARDING`, which
  the 1.1.0 text left out. No behaviour changes.

- **Comments that disagreed with the code.** Comments and Javadoc in the code,
  the workflows, the `Dockerfile`, `compose.yaml` and the manifests now match
  the code, bar the V2 and V7 migrations, which Flyway checksums freeze.

### Security

- **Tomcat 11.0.26.** `pom.xml` sets `<tomcat.version>` over the 11.0.24 that
  Boot 4.1.1 manages. Trivy rates three advisories fixed in 11.0.25 critical:
  CVE-2026-65182, CVE-2026-65905 and CVE-2026-68525. They sit in servlet
  security constraints and Tomcat's DIGEST and FORM authenticators, which the
  service does not use, since Spring Security authenticates. The current patch
  release, 11.0.26, fixes twelve more.

- **Forgeable 403 log line.** `JsonAccessDeniedHandler.printable` masks
  anything outside visible ASCII (`JsonAccessDeniedHandlerTest`).

- **Log lines from rejected input.** `GlobalExceptionHandler.printable` masks
  control, format and line-separator characters
  (`FlightControllerTest#rejectedValueCannotForgeALogLine`) and cuts a value
  at 1,000 characters plus `...`.

- **Lambda log lines.** `BookingEventHandler.printable` does the same for the
  `bookingId` and the `FAILED` line's message.

- **LB controller policy file.** Step 8 of `up.sh` downloads the IAM policy to
  a private `mktemp` file, not a fixed `/tmp` path another user could plant.

- **No password in the log.** `ApiSecurityProperties` checks the prefix in its
  constructor, so Boot's failure report no longer echoes the value.

- **Narrower publish policy.** `SqsPublishPolicy` in
  `deploy/aws/foundation.yaml` grants `sqs:SendMessage` only.

- **The image fails closed.** `Dockerfile` sets `SPRING_PROFILES_ACTIVE=prod`,
  so the image needs a database, where the old one would have served H2.

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
  in the MDC and returns it as `X-Request-Id` on every response the application
  handles, including 401 and 403.
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

- **Log-safe idempotency keys.** `idempotencyKey` now accepts only
  `[A-Za-z0-9._:-]`, the class `RequestIdFilter` enforces on `X-Request-Id`.
  The key is echoed into log lines, and a newline in it could forge an entry.

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
