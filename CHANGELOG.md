# Changelog

Every release, what changed in it, and — where it matters — why.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
the versions follow [semantic versioning](https://semver.org/spec/v2.0.0.html),
with two qualifications stated here rather than left to be discovered.

**On 1.1.0 not being 2.0.0.** This release removes a field from an API response
(`idempotencyKey`, from `BookingDto`), and under strict semantic versioning a
removed response field is a breaking change to consumers, which would make this
a major release. It is 1.1.0 because 1.0.0 was never tagged, never published and
never consumed: there is no client anywhere that could break. From this release
onwards the rule is the strict one — the next removal of a response field, or
any change to a status code or an error code, is a major.

**On the dates.** 1.0.0 was never tagged, so the entry for it is written
retrospectively from the history and dated by its last commit. That puts both
releases on the same day, which is honest rather than tidy: the whole history is
eight days long, and the second half of it is the part that made the first half
checkable.

**What "released" means here.** A version number in `pom.xml` and a git tag. It
does not mean deployed. Nothing in this repository has ever run in AWS, and
[DEPLOYMENT.md](DEPLOYMENT.md) records that in a dated line that is still blank.

## Unreleased

### Fixed (documentation only; no behaviour change)

- The README listed 20 error codes; there are 21. `BAD_REQUEST`, which
  `ApiErrorController` returns for any other client error forwarded to `/error`,
  was missing, and `UNKNOWN_SORT_PROPERTY` still described the pre-`SortPolicy`
  behaviour.
- ADR 0002, ARCHITECTURE.md, the README and `application.yml` said the
  PostgreSQL dialect discards a JPA lock-timeout hint. Hibernate 7.4.5 applies it
  with `SET LOCAL lock_timeout`. The decision stands on a different reason, and
  ADR 0002 carries a dated correction.
- The outbox retry window was described as about twenty minutes, and once as ten
  seconds. Two seconds doubling to a five-minute ceiling over ten attempts is
  810 seconds, about thirteen and a half. The comment in
  `V7__outbox_next_attempt_at.sql` still says twenty: an applied migration
  cannot be edited without changing its Flyway checksum.
- The build-log note in the README called Hibernate's duplicate-key lines
  ERRORs; they are WARNs.
- The CI workflow comments said Flyway V1–V6 (it is V1–V8) and described branch
  protection as already enforced.

## 1.1.0 — 2026-09-23

The release that turned a working service into one that can be handed to
somebody else. Very little here changes what the API does. Almost all of it is
about making a claim checkable: a gate that fails, a document verified against
the code it describes, a teardown script that proves it left nothing behind.

### Added

- **Authentication and authorisation.** HTTP Basic always on, JWT bearer tokens
  accepted when an issuer is configured, both funnelling into one
  `authorizeHttpRequests` block so there is exactly one place a rule can be
  wrong — [adr/0005](adr/0005-one-rule-set-for-basic-and-jwt.md). Reads need
  `flights:read`, writes need `flights:write`, `/actuator/**` needs `ROLE_OPS`,
  and the health probes stay open because the kubelet has no credentials.
- **A transactional outbox.** The booking transaction no longer makes a network
  call: it writes a row, and `OutboxPublisher` drains it afterwards with
  `SELECT … FOR UPDATE SKIP LOCKED` — [adr/0001](adr/0001-transactional-outbox.md),
  migration `src/main/resources/db/migration/V5__outbox.sql`.
- **Consumer-driven contract tests.** The service and the Lambda are separate
  Maven builds with no shared module, so nothing checked that they still agreed
  about the event. `contracts/booking-created-v1.json` is now the contract and
  both sides assert against it — [adr/0008](adr/0008-standalone-lambda-consumer.md).
- **Correlation ids, metrics and structured logs.** `RequestIdFilter` puts an id
  in the MDC and on every response as `X-Request-Id`, including on 401 and 403;
  `BookingMetrics` and `OutboxMetrics` publish the counters and gauges that
  answer "is it working" without a log read; the prod profile emits ECS JSON —
  [adr/0011](adr/0011-correlation-ids-and-metrics.md), `OPERATIONS.md`.
- **The trace survives the queue.** The booking's W3C `traceparent` is captured
  at booking time, stored on the outbox row (migration
  `src/main/resources/db/migration/V6__outbox_traceparent.sql`), sent as an SQS
  message attribute and logged by the Lambda, so one id follows a booking from
  the HTTP request to the DynamoDB write. Fixture:
  `events/sqs-with-trace.json`.
- **An OpenAPI document**, generated from the controllers so it cannot drift,
  with what springdoc cannot infer written in `OpenApiConfig`. The document and
  the Swagger UI are readable without credentials; every operation in them still
  is not — [adr/0012](adr/0012-openapi-public-read.md).
- **Build gates**: `maven-enforcer` (JDK 21, Maven 3.9+, no duplicate or
  downgraded dependency versions), JaCoCo with a bundle minimum of 80% line and
  50% branch, a CycloneDX SBOM at `target/bom.json`, and `ArchitectureTest` —
  nine ArchUnit rules that fail the build rather than produce a report —
  [adr/0014](adr/0014-quality-gates.md).
- **Documentation that is gated.** `ARCHITECTURE.md`, `OPERATIONS.md`,
  `SECURITY.md`, `CONTRIBUTING.md`, `DEPLOYMENT.md`, fourteen ADRs under
  `adr/`, and three checkers that fail CI: `scripts/refcheck.py` (every
  backticked path and `path#symbol` must resolve), `scripts/linkcheck.py` (every
  relative link and heading anchor), and `scripts/sweeps.sh`. Counts come from
  `scripts/numbers.sh`, never from memory.
- **A reproducible AWS path, and a provable teardown.** `deploy/aws/` holds the
  CloudFormation for ECR, the GitHub OIDC role and the budgets
  (`deploy/aws/foundation.yaml`), the RDS stack (`deploy/aws/data.yaml`), the
  twelve-step `deploy/aws/up.sh`, and `deploy/aws/down.sh`, which finishes with
  a PASS/FAIL sweep over every resource type and exits non-zero if anything
  survives — [adr/0009](adr/0009-eksctl-and-sam-over-terraform.md),
  [adr/0010](adr/0010-region-ap-south-1.md). `deploy/aws/render-aws.sh` is the
  one render path, shared by CI and by a human checking what is about to be
  applied.
- **This file**, and `.github/PULL_REQUEST_TEMPLATE.md`, which asks what breaks
  if the change is wrong.
- **`build-info`**, so `/actuator/info` and the OpenAPI document report the real
  `pom.xml` version instead of an empty object and a hard-coded string.

### Changed

- **Spring Boot 3.5 → 4.1.1**, which is Spring Framework 7 and four moves in
  one: Jackson 2 → 3 (the group id changed), the technology-per-module split,
  Spring Security 7, and the JUnit Platform 6 line —
  [adr/0007](adr/0007-spring-boot-4.md). Dependabot had been opening this bump
  since June and it was the only red run in the repository's history, because
  bumping the parent alone does not work.
- **Kubernetes manifests are kustomize**, not one flat file: `k8s/base` plus an
  `k8s/overlays/aws` overlay and an ingress component. `readOnlyRootFilesystem`
  is now true with an explicit `emptyDir` mount for the one path the JVM actually
  writes to. `k8s/namespace.yaml` deliberately sits outside the base, because
  the deploy role's access entry is namespace-scoped and CI must never apply a
  cluster-scoped object.
- **CI is six named jobs** — `build`, `infra-lint`, `trivy-fs`,
  `dependency-review`, `docs-check`, `deploy` — running in parallel, so a red
  square names what broke before you open the log, and branch protection can
  require the gates individually. Every action is pinned to a commit SHA. CodeQL
  runs in its own workflow, on a schedule as well as on push, because new
  queries find old code.
- **Page size is capped** at 100 (`spring.data.web.pageable.max-page-size`). A
  caller asking for 5,000 rows previously got them.
- **Time comes from a `Clock` bean** everywhere except `Booking.createdAt`,
  which is the one documented exception and is exempted by name in the ArchUnit
  rule rather than by a widened rule.
- **Dependabot runs monthly**, grouped, across both Maven modules, the Actions
  workflows and the Dockerfile base images.

### Removed

- **`idempotencyKey` from `BookingDto`.** The list endpoint returns every
  booking on a flight, so anyone holding `flights:read` could read the keys of
  bookings they did not make and replay against them. The caller chose the key
  and already has it, so returning it bought nothing. The column is unchanged;
  only the response shape is. **This is the one change in this release that can
  break a consumer.**

### Fixed

- **A losing idempotency replay got 409 instead of 201.** Two identical requests
  racing: the loser caught the unique-constraint violation and reported a
  conflict, when the correct answer is the winner's booking.
- **`LazyInitializationException` on `GET /api/v1/bookings/{id}`.** The entity
  was mapped outside the session.
- **`findBookable` could not see a DELAYED flight.** A delayed flight is still
  bookable; the query said otherwise, so a whole flight silently disappeared
  from the inventory the moment it was delayed.
- **`MALFORMED_REQUEST` echoed raw exception text** back to the caller, which
  leaks types and field names from the deserialiser.
- **A duplicate unique index on `flights.flight_number`**, which cost a write on
  every insert and guaranteed nothing the first index did not.
- **The outbox could be blocked by one poison row.** The claim is `ORDER BY id`,
  so a row that fails forever sits at the head of the queue forever. The claim
  query now carries `AND attempts < :maxAttempts`, and an operator re-drives a
  dead row by resetting `attempts` — [adr/0013](adr/0013-outbox-ceiling-and-retention.md).
- **The outbox table grew without bound.** `OutboxPruner` deletes published rows
  past a retention window, in batches, so the delete cannot take a long lock.
- **Two gates that failed over things no commit could fix**: `shellcheck` is
  pinned to v0.11.0 rather than whatever the runner ships (0.10 and 0.11
  disagree about `cmd && log … || true`), and `dependency-review` probes for the
  repository's dependency graph and warns instead of going red when it is
  switched off.
- **`linkcheck.py` could not see badge targets.** A badge is a link whose label
  is an image link, and the old pattern stopped at the image's closing bracket
  and checked the shields.io URL instead of the link. Every badge in the README
  had been unchecked since the day it was added.
- **The attempt ceiling was burned at the poll rate.** A transport error that
  fails fast — a bad queue URL, an expired credential — retried every row ten
  times in ten seconds and dead-lettered the entire backlog before anybody could
  read an alert, while `outbox_pending` sat at zero the whole time because the
  rows had already moved to dead. Migration
  `src/main/resources/db/migration/V7__outbox_next_attempt_at.sql` adds
  `next_attempt_at` and the claim query skips a row that is waiting, so a
  failure now backs off instead of racing the clock.
- **`demo.sh` could not pass its own preflight against a deployment**, and it
  took `up.sh` down with it. The preflight required `GET /api/v1/flights/UA123`
  to be a 2xx, and the `prod` profile sets `app.seed.enabled: false`, so against
  a cluster it was a 404. `up.sh` runs the demo as its last step under
  `set -euo pipefail`, which meant the run aborted at step 12 of 12 — **before
  printing the only plaintext copy of the generated API and ops passwords**. The
  demo now creates the flights it needs (201 or 409, both fine), redacts
  credentials from the commands it echoes, uses a per-run suffix so a second run
  against the same database is not a pile of 409s, and `up.sh` prints the
  passwords at step 9 the moment the Secret exists and treats a failing demo as
  a warning rather than a fatal error.
- **The teardown could report success while things were still billing.** Its
  final sweep reported `PASS` for any check whose AWS call *errored*, so an
  expired token read exactly like an empty account; the queries now fail closed
  and name the failure. It also deleted the account-wide
  `aws-sam-cli-managed-default` bucket, which belongs to every SAM project in
  the region rather than to this one — that is now opt-in behind
  `--delete-sam-bucket` — and it treated an unreachable cluster as "no ingress",
  silently skipping the one step its own header calls the expensive mistake.
- **Re-running `up.sh` deleted the GitHub OIDC provider the first run created**,
  which silently breaks every future CI deploy. The provider is a shared,
  account-wide resource and now carries `DeletionPolicy: Retain`.
- **Both list endpoints paged on a non-unique sort key**, so two flights sharing
  a departure time could appear twice or not at all across pages. `SortPolicy`
  appends `id` as a tie-breaker.
- **`/error` answered with Boot's default error map**, not the documented
  `{code, message, timestamp}` envelope — and Tomcat forwards to it *after* the
  security chain has finished, so every container-level 404 and 500 left the
  documented contract. `exception/ApiErrorController` now serves it, with a
  deliberately generic message so the container's error text cannot leak the
  internal path or the exception class. It is `@Hidden`, so it does not appear
  in the OpenAPI document.
- **`app.events.publisher` ignored its environment variable outside `prod`.**
  The base document hard-coded `log`, which made the SAM-only recipe in
  DEPLOYMENT.md impossible to follow: it tells you to run
  `APP_EVENTS_PUBLISHER=sqs ./mvnw spring-boot:run` and watch messages arrive,
  and nothing arrived. It is now `${APP_EVENTS_PUBLISHER:log}`; choosing `sqs`
  without a queue URL still fails at startup.

### Security

- The service no longer returns other callers' idempotency keys (above).
- `readOnlyRootFilesystem: true`, `runAsNonRoot`, all capabilities dropped, and
  `automountServiceAccountToken` left to IRSA's projected token.
- Supply chain: Trivy scans the filesystem and the image before it is pushed,
  CodeQL runs `security-extended`, every GitHub Action is SHA-pinned, and a
  CycloneDX SBOM is attached to every build.
- `SECURITY.md` documents the auth model, the 401/403 split, what data the API
  exposes, and — deliberately — what this service would get wrong if it had real
  users.
- **JWT audience validation is documented where it will be read.** Bearer tokens
  are off, and the day somebody turns them on, `issuer-uri` alone is not enough:
  an issuer mints tokens for every application registered with it, so a token
  issued to a different client of the same tenant arrives correctly signed and
  would be accepted. `application.yml`, `config/SecurityConfig` and `SECURITY.md`
  now all name `spring.security.oauth2.resourceserver.jwt.audiences` beside
  `issuer-uri`, and the startup log line does too.
- **CI's two downloaded binaries carry checksums.** `kubeconform` and
  `shellcheck` are fetched by tag from someone else's repository, which is the
  same mutability every SHA-pinned action in the workflow exists to avoid; the
  published sha256 is now verified before either is unpacked as root.

## 1.0.0 — 2026-09-22

The service itself, and the two review passes that followed it.

### Added

- Flight inventory and bookings over HTTP: create, cancel, status transitions,
  paged reads.
- **The one hard problem**: not overselling the last seat when two requests
  arrive at the same moment. Solved with a pessimistic row lock taken in a fixed
  order plus a session `lock_timeout` —
  [adr/0002](adr/0002-pessimistic-locking.md) — and proved by a test that puts
  twenty threads on five seats.
- **Idempotency** on booking creation: a unique key plus a SHA-256 fingerprint
  of the request body, so the same key with a different body is a conflict
  rather than a silent replay of something else —
  [adr/0004](adr/0004-request-fingerprint.md).
- Flyway migrations V1–V4, JPA entities with `ddl-auto: validate` against them,
  and an H2 profile for a laptop with the PostgreSQL path verified in CI.
- A standalone arm64 Lambda consumer, its SAM template, and `demo.sh` — the
  behaviours worth showing, over real HTTP rather than as assertions.

### Fixed

- The findings of two review passes, recorded in the README at the time
  including the ones that were not fixed. Several of them are the entries in
  1.1.0 above.
