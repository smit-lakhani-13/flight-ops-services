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
The changelog in the 1.1.0 tag made any change to a status or an error code a
major, and this wording replaced that a few hours later.
Starting to reject input the contract never allowed, such as a fractional seat
count, is a fix, and so is refusing input the published schema admitted but no
real client needs, such as a flight number with a slash.

**On the dates.** 1.0.0 was never tagged, so its entry was written afterwards
from the history and dated by its last commit. All dates are IST. 1.0.0 and
1.1.0 are split by theme (the service, then the hardening pass that followed)
and not at a single commit. So 1.1.0 lists some fixes committed
before 2026-09-22, and its Boot 4.1.1 upgrade came before some of the work in
1.0.0.

**What "released" means here.** A version number in `pom.xml` and a git tag. It
does not mean deployed. Nothing in this repository has ever run in AWS, and
[doc/DEPLOYMENT.md](doc/DEPLOYMENT.md) records that in a dated line that still
says not yet.

## Unreleased

### Added

- **Tests for four guarantees the documents state.**
  `OutboxTest#aLongFailureIsTruncatedAndTheBatchCommits` sends a 2,000-character
  failure through the drain and reads back `last_error` cut to its 500-character
  column, with the rest of the batch published: an over-long value would fail
  the UPDATE and roll back the attempt counter. `LockTimeoutTest` now retries
  after each `503 LOCK_TIMEOUT`, as `Retry-After` asks: the timed-out booking
  left no row and took no seat, the timed-out cancellation left the booking
  active, and each retry succeeds.
  `ErrorContractTest#pagePastTheLastAddressableRowIsABadRequest` sends both
  sides of the `page * size` edge at two sizes. `ShutdownBudgetTest` reads the
  shutdown mode and drain the running context bound, the `preStop` sleep and
  grace period in `deploy/k8s/base/deployment.yaml` and the ALB's
  deregistration delay in the Ingress, and fails if the sleep and the drain, or
  the delay, no longer end inside the grace period.

### Changed

- **A preflight to the console is tested.** ADR 0017 and SECURITY.md rest on
  Next answering `OPTIONS` on the console's `/api/` paths itself, with `204`
  and no CORS headers, and nothing checked it. `web/e2e/ops.spec.ts` now sends
  a preflight from another origin to a forwarded path, a refused one and the
  race, checks that no `Access-Control-*` header comes back, and checks that a
  `GET` on the race gets Next's bare `405`. The route test fails if the `/api`
  module exports `OPTIONS`. `web/README.md` said the preflight's `Allow` header
  lists the methods the route exports; it lists `OPTIONS` as well.

- **The console's tests check more of what its README promises.** The e2e
  specs check that signing in, signing out and a Try again on a flight's or a
  booking's page each move the focus to the page's title, and that a change of
  account is announced; a booking page's Try again had no test at all. The
  airport search waits for the unfiltered list to go before it checks the
  result, so it can no longer pass on the old list, and the 44 px check covers
  the header's brand link, as `web/README.md` and ADR 0017 say it does. New
  unit tests cover a replay that comes back as a different booking, a race
  that made two bookings, a race call that times out, the race's 500 for an
  `API_BASE_URL` that is not an origin, the hints for 401, 403 and no answer,
  and the zone in a formatted time, and one fails if `lib/proxy.ts` or
  `lib/race.ts` sends a code the error classifier does not know. Vitest runs
  every `*.test.ts` and `*.test.tsx` file in `web/`, where it took one
  extension per directory, so a test can no longer sit unrun.

- **A flight's bookings keep the focus and say when they are out of date.**
  After Next or Previous in the bookings on a flight's page, the old page stayed
  on screen as if current until the new one came, and a page that failed to
  arrive took the pager away with the focus on it, which fell back to the page.
  The card now dims the last answer and marks it busy while another is out, as
  the flight list does, and a failed page hands the focus to the card's
  Refresh, which reads that page again. `web/e2e/flights.spec.ts` checks both.

- **The console's hint on a 503 fits a database outage.** It said the service
  was busy, but `DATABASE_UNAVAILABLE` sends the same `503` and `Retry-After`
  as `LOCK_TIMEOUT`, so while the database was down every page, and the api
  account's sign-in, pointed at load. It now says the service could not finish
  the request just now, and the API's message above it still says which it was.

- **A new sort on the flight list applies the airports the fields show.** The
  sort reads the list again at once, but it kept the airports last searched, so
  after typing `EWR` over a searched `ORD` and changing the sort, the list
  showed `ORD` flights under a field that said `EWR`. It now takes the fields
  as they stand, as Search does. `web/e2e/flights.spec.ts` checks the request.

- **`doc/api.md` says three more things the service does at its edges.** A
  `Content-Encoding` is not read, so a compressed body is never expanded. A
  malformed `Content-Length` gets Tomcat's HTML 400, and `CONNECT` or a
  transfer coding other than `chunked` its HTML 501, each outside the JSON
  envelope. A filter sent twice is joined with a comma, and matches nothing.
  Each was found by probing a running service; none is new behaviour.

- **The OpenAPI document writes every error code one way.** Its 503
  descriptions put a colon after the code and the others a dash, often both in
  one operation's list of responses. Every description in `BookingController`
  and `FlightController` now uses the colon, and `OpenApiTest` fails if an em
  dash comes back into the document.

- **The flight list no longer cuts a seat count at tablet widths.** Between
  640 and 790 px a long row made the table wider than its box, which scrolled
  with no sign that it could, so its edge cut `850/850` to `850/85`. The
  departure time now wraps first, so the table fits its box from 640 px up. A
  test in `web/e2e/flights.spec.ts` checks the widest row the API allows at
  five widths.

- **Version.** Both poms say `1.4.0-SNAPSHOT` until the next tag, so a build
  from `main` no longer reports itself as 1.3.0 in `/actuator/info` and the
  OpenAPI document.

- **A bearer token short of a scope is told which one.** With the resource
  server on, a 403 from a rule that asks for one scope carries
  `WWW-Authenticate: Bearer realm="flight-ops-service", error="insufficient_scope", scope="flights:write"`
  (or `flights:read`), RFC 6750's challenge for a token that lacks a scope.
  `JsonAccessDeniedHandler` had replaced Spring's bearer handler and sent no
  challenge at all. It reads the scope from the refusing rule's decision, so a
  403 no scope can lift, `denyAll()` or the ops role, still carries none, and
  so does a 403 to a Basic caller
  (`BearerTokenChallengeTest#aRefusalNoScopeLiftsHasNoChallenge`).

- **The README says more of what is built.** Its opening sentence names Java
  21 and Spring Boot 4.1. It links the latest release, says a release is a
  version and a tag and that each GitHub release since v1.2.0 carries an SBOM
  for each module, and counts the tests in both modules and the console. It
  adds a contents line, the lock order with `@Version`, what the API will read,
  how a booking's `traceparent` crosses the queue, what the SAM and
  CloudFormation templates hold, what the gated deploy job would do, how
  actions, downloads and images are pinned, and which merges the ruleset on
  `main` accepts.

- **Dependabot keeps `@types/node` on the console's Node.** The major of
  `@types/node` is the Node release it describes, and `web/.nvmrc` and
  `engines` pin Node 24, so Dependabot's offer of 26.x would have let the type
  check accept a call to an API that Node 24 does not have. The npm entry now
  ignores its majors, as it does for `next`, `eslint-config-next` and `eslint`.
  Its minors and patches still arrive in the tooling group, and its major moves
  by hand with `web/.nvmrc`. The ignore rules now name nine artifacts.

- **A failed row lock is logged on one line.** The WARN lines for a lock
  timeout or a deadlock, and for a write `@Version` rejected, now go through
  `GlobalExceptionHandler.printable`, as the line for an unreachable database
  already did. PgJDBC puts the server's Detail, Hint and Where on lines of
  their own, so on the plain-text console one deadlock took five lines, and a
  search for the WARN line missed the rest
  (`FixedErrorMessagesTest#aMultiLineLockMessageIsLoggedOnOneLine`). The ECS
  JSON under `prod` was already one object per line.

### Fixed

- **The flight-number pattern runs in linear time.** Every constraint on a
  field runs, so the pattern also saw values `@Size` had refused, up to the
  16 KiB body limit. On a run of spaces that ends in a symbol,
  `^\s*[A-Za-z0-9]*\s*$` tried every split between its two runs of padding:
  one request with 16,000 spaces took about 750 ms of CPU in a local run, and
  any caller with `flights:write` could send it to either write. The pattern
  is now `^\s*(?:[A-Za-z0-9]+\s*)?$`, which accepts the same strings and
  backs off one character at a time. `FlightNumberPatternTest` checks it
  against the rule over every string up to seven characters, and times it on
  100,000 spaces. The published OpenAPI schema shows the new text.

- **A typo in the sweep patterns fails instead of passing.** `scripts/sweeps.sh`
  guarded against a missing `SWEEP_PATTERNS` secret but not a corrupt one. grep
  reports a pattern it cannot compile as an error and matches nothing, and each
  supplied-pattern check read no match as a pass, so one unbalanced parenthesis
  in an edit turned all three into a silent pass. A pattern that starts with `-`
  was read as an option, with the same result, and so was a tracked file whose
  name starts with `-`. The script now checks the pattern once, fails every run
  if grep cannot compile it, in the caller's locale or in C, or if it matches an
  empty line, and never prints it. Each grep takes the pattern after `-e` and
  the file names after `--`. The sweeps also read what they skipped: the
  screenshots in `doc/assets/`, read byte by byte with only a matching file's
  name printed, and each annotated tag's message, which GitHub shows with the
  release. On GNU grep, which CI runs, a line with a byte that is not UTF-8 is
  no longer dropped: grep reported a match on such a line only as "binary file
  matches" on standard error, and every grep now reads its input as text.
  `scripts/sweeps-selftest.sh` plants each kind of finding in scratch
  repositories and fails if the script passes one; the `docs-check` job runs it
  before the real sweep.

- **Maven version in the README.** The `build` row said the enforcer requires
  Maven 3.9; both poms accept 3.9 or later (`requireMavenVersion` is
  `[3.9.0,)`).

- **Text fields take only JSON strings.** `"flightNumber": 123`,
  `"passengerName": 42` and `"idempotencyKey": true` were read as the text
  `"123"`, `"42"` and `"true"`, so a create or a booking got 201 for a body
  the OpenAPI document refuses. `allow-coercion-of-scalars: false` stops a
  string becoming a number, not a number becoming a string.
  `config/StrictTextModule` now refuses a number or a boolean for every
  `String` in a request body with `400 MALFORMED_REQUEST`, as it already
  refused an array or an object
  (`MalformedRequestTest#aNumberOrABooleanForATextFieldIsMalformed`,
  `ErrorContractTest#aFlightNumberSentAsANumberIsRefused`).

- **Every error envelope has one Content-Type.** The 401, the 403 and the
  413, which `security/ErrorResponseWriter` writes before Spring MVC runs, were
  sent as `application/json;charset=UTF-8`, while `GlobalExceptionHandler` and
  `ApiErrorController` send `application/json`, the value `doc/api.md` gives
  for all three. The writer no longer sets a character encoding, so all three
  send `application/json`. JSON on the wire is UTF-8 and its media type has no
  charset parameter (RFC 8259), and the body is the same UTF-8 bytes as before.
  Responses these three do not write are unchanged: Tomcat's HTML 400 and 501
  pages, the actuator's empty 404, and the health 503, which the actuator
  sends with the media type it negotiates. `SecurityRulesTest`,
  `JsonAccessDeniedHandlerTest` and the real-server tests for the 413 and for
  `/error` now compare the whole header.

- **Moving to Cognito is not configuration alone.** The README,
  `doc/ARCHITECTURE.md` and SECURITY.md said the bearer-token swap is two
  properties. That holds for an issuer whose tokens carry `aud` and the scopes
  `flights:read` and `flights:write`. Cognito prefixes each custom scope with
  its resource server's identifier, and its client-credentials tokens carry no
  `aud`, so it also needs a scope converter and an audience check on
  `client_id`. The documents now say so; neither is built.

- **The console's 409 demonstration made a booking on a fresh key.** On
  `/book`, "Same key, different body" builds its body from the key's first
  Book. With no Book on the key yet, it sent the form with one more seat, which
  was the key's first body: the API made a booking, took the seats, and the
  status line showed a green 201. A replay then got 409, because it sent the
  form and not what the key held. The button now sends nothing on such a key
  and says to Book first
  (`web/components/BookingForm.test.tsx`, "sends no different body on a key no
  Book has used").

- **Escape did not close the console's cancel question.** On a flight's page,
  Cancel flight opens an inline question, Keep it or Yes, cancel it, and moves
  the focus to Keep it. Escape did nothing there, while it closes the Requests
  drawer. It now answers the question as Keep it does: nothing is sent, and the
  focus goes back to Cancel flight. While the cancel is out, Escape does
  nothing, as Keep it is disabled then (`web/e2e/flights.spec.ts`, "Escape
  inside the cancel question keeps the flight and hands the focus back to
  Cancel flight").

- **Smaller claims that had drifted.** ADR 0014 now says both SBOMs are
  uploaded by CI and attached to every release since v1.2.0, and ADR 0009
  counts `cluster.yaml` right (88 lines, half of them comments). The
  workflow's header names the eight checks the ruleset requires, the pom's
  build-info comment says the OpenAPI version falls back to `unknown`, the
  data stack's description no longer points at a production section that
  `doc/DEPLOYMENT.md` does not have, and the defect log's superseded readiness
  entry says no test pinned it at the time.

- **More claims that had drifted.** ADR 0005 said a move to any identity
  provider is configuration alone; it now names the issuers that holds for and
  what Cognito would need, with a dated correction note. `doc/ARCHITECTURE.md`
  says SDK 2.55.x, as ADR 0008 does, where it named 2.55.3. The contracts
  README now says the one queue cannot carry v1 and v2 of an event while the
  consumer ignores `eventType`, and what has to ship first. The template's
  teardown comment names `down.sh` as the command that takes
  `--delete-sam-bucket`, and its memory comment says 1024 MB costs no more
  than 512 MB when the duration halves, not less. The Lambda pom's build line
  uses `./mvnw`, as its enforcer message asks, the Dependabot header counts
  its three points, the proxy's `localLocation` comment says the API sends a
  path, and the 1.3.0 section of this file ends with a blank line.

## 1.3.0 — 2026-09-28

A browser console for every operation, in `web/`, built and tested in CI and
never hosted, and a round of fixes to the service, its `postgres` and `prod`
settings, the deploy scripts and the documents. The AWS SDK code now also runs
in CI against ElasticMQ and DynamoDB Local, and neither test talks to AWS; 193
new tests pin behaviour that nothing checked. The deploy job is still gated off
and has never run.

Some changes refuse a request the contract accepted, which the versioning rule
above calls a major, and this release treats each as a fix. Cancelling an active
booking on a `DEPARTED` or `ARRIVED` flight gets `409 BOOKING_NOT_CANCELLABLE`
where it got 200, because that 200 rewrote the record of a flight that had
already flown, and relying on it meant relying on the defect. A field the API
does not have, which Jackson ignored, gets `400 MALFORMED_REQUEST` on the three
writes that take a body: it was never read, so a 201 told the client that a
request had succeeded when part of it had been thrown away. A `passengerName`
with a text-direction control or a line separator gets
`must not contain text-direction controls or line separators`. The published
pattern allowed these characters, and refusing them is treated as a fix, like
the unpaired-surrogate rule below, because they change only how a name is
displayed, not what it is, and a name that carries them can mislead whoever
reads the record. Readiness is treated as outside the rule: it answers 200
during a database outage where it answered 503, because the probe endpoints
answer the kubelet and the load balancer, not API clients, and no answer under
`/api/**` changes.

The other refusals are of input that 1.2.0 mishandled. A key sent twice in one
object gets the same `400 MALFORMED_REQUEST`, since the last value won while a
proxy or a log that kept the first would have shown a different request. A
status name with padding or controls around it, which Jackson trimmed and
applied, is `400 MALFORMED_REQUEST`, as an unknown name was. A `passengerName`
with an unpaired UTF-16 surrogate gets `must not contain unpaired surrogates`,
because the request fingerprint turned it into `?` and a name that differed only
there, on the same key, was answered as a replay instead of a 409; a name made
only of no-break or zero-width spaces, which passed `@NotBlank`, gets
`must not be blank`. A query filter holding a control character once trimmed,
such as `?origin=J%00K`, is `400 MALFORMED_REQUEST` before any query, where
PostgreSQL's refusal came back as `409 DUPLICATE_REQUEST`, telling the client to
retry a read that fails every time, and H2 answered an empty page. A body over
`app.http.max-body-bytes`, 16384 bytes by default, gets `413 PAYLOAD_TOO_LARGE`
once its declared length or a read shows it: with no limit, Jackson built a
whole string field before `@Size` checked it, and a few requests with a name of
millions of characters could exhaust the heap and end the JVM. And `/logout`, a
204 for any caller, a wrong password included, is denied like any path no rule
names, since the API keeps no session and the rules end in `denyAll()`. The
status name was never one the contract lists and `/logout` never a path the
rules allowed; the rest got a wrong answer or put the heap at risk. Each is a
fix.

Other fixes replace an answer that 1.2.0 got wrong. A badly encoded query value,
such as `?origin=%FF`, or a query name with a control character is
`400 MALFORMED_REQUEST` where it was `500 INTERNAL_ERROR`, on the public
`/actuator/health` too, and a form-encoded `PUT`, `PATCH` or `DELETE` body with
a bad percent escape and no credentials gets 401 where it got a 500.
`?origin=%00` lists every flight, as `?origin=` and `?origin=%20` do, where it
answered an empty page. `?sort=cancelledAt` lists the active bookings last in
both directions on both databases, which changes `,desc` on PostgreSQL; the
contract had never said where they went. A field that breaks two rules always
gets the most basic message, not whichever violation came first. With bearer
tokens on, the metadata at `GET /.well-known/oauth-protected-resource` no longer
claims certificate-bound tokens, which nothing checks. Under `postgres` and
`prod`, a request waits 5 s for a connection, not 30 s, before its
`503 DATABASE_UNAVAILABLE`, and a statement the database stopped answering ends
in the same 503 after 30 s, where it could hold its thread for about 15 minutes.
Bookings, cancellations and status changes on a flight inserted without a
version, each a 500, now go through, and a server whose
`default_transaction_isolation` is REPEATABLE READ no longer turns queued
bookings, cancellations and replays into `503 LOCK_TIMEOUT`. Each replaces an
unplanned 500 or a wrong, unstable or late answer, or settles one the contract
never specified, so each is a fix. With the majors treated as fixes and
readiness outside the rule, this release is 1.3.0 and not 2.0.0.

### Added

- **AWS SDK code on emulators in CI.** `SqsEventPublisherElasticMqTest` reads
  back unchanged what `SqsEventPublisher` sent to ElasticMQ. The Lambda's
  `BookingEventHandlerDynamoDbLocalTest` runs on DynamoDB Local keyed as in
  `lambda/template.yaml`: one item per booking, a redelivered batch refused
  without failing, a malformed body failing only its own message. Both skip
  without Docker, and the step "The emulator tests ran" fails CI if either
  skipped; neither talks to AWS. `scripts/numbers.sh` counts the Lambda's
  skipped tests. Dependabot skips the Lambda's Testcontainers version, as it
  does JUnit, since `lambda/pom.xml` copies Boot's.

- **Database login documented.** `doc/OPERATIONS.md` traces the RDS master login
  the service and Flyway share, an `rds_superuser` member owning every table,
  and what it allows; `doc/ARCHITECTURE.md` lists the fix, a migration user and
  a least-privilege runtime user, as open. No code changes.

- **Error answers logged.** Most 4xx answers logged nothing, so a quoted
  `X-Request-Id` found no line. `RequestIdFilter` now logs one INFO line per
  answer of 400 or above under the request id: method, path made printable by
  the 403 handler's rule, and status, never the query string, a header or the
  principal (ADR 0011). It skips `/actuator/`, so a failing readiness probe does
  not log every period, and a request whose chain throws, which
  `ApiErrorController` logs at ERROR under that id (`EscapedFailureLogTest`).

- **Browser console in `web/`.** A Next.js 16 TypeScript app that signs in as
  one of the service's accounts and runs every operation, including the 409 for
  a changed body on one idempotency key, ten identical bookings at once from its
  own server, and health and meters as `ops`. The browser talks only to the
  console, whose `web/lib/proxy.ts#forward` passes allow-listed calls to the API
  with the caller's credentials, neither logged nor kept. The API is unchanged,
  no CORS policy or cookie, so ADR 0006 holds, as a dated note there says.
  [ADR 0017](adr/0017-web-console.md) records the design, `web/README.md` each
  page's calls. Below 1280 px or when touch is the main pointer, controls are
  touch-sized and field text 16 px; Playwright checks every page at twelve
  Chromium viewports and each sign-in stop's keyboard outline. Never hosted.

- **CI checks the console.** On each push or pull request to `main`, a new `web`
  job lints, type-checks, unit-tests and builds it, then starts the service's
  jar on the default H2 profile and runs Playwright in Chromium against both;
  the deploy job needs it. CodeQL analyses the TypeScript as a second matrix
  entry, `analyze (javascript-typescript)`; the Java check keeps its name.
  Dependabot's fifth entry, `web/`, groups runtime dependencies and tooling into
  one pull request each.

- **193 tests for behaviour nothing pinned.** Any change to it now fails the
  build; no production code changed. Covered: seat limits per booking and per
  flight, idempotency fingerprints and replays, the flight status machine over
  HTTP, a stale flight write losing to a booking, both outbox job schedules, the
  gauge refresher's start and stop with the context, malformed bodies and path
  ids, every text field's length limit, one message for a field breaking several
  rules, departure time formats, default paging and sort, the 503 for a lost
  database on every booking endpoint, booking counters on every outcome, the
  flight write lock timeout, and the JSON envelope on the container's `TRACE`
  405 and firewall 400. Classes include `FlightStatusContractTest`,
  `MalformedRequestTest` and `ContainerErrorDispatchTest`.

- **Tests for claims nothing checked.** `FlightTest` walks every allowed move
  and checks each of the 30 moves between distinct statuses is listed once,
  allowed or refused. `SecurityRulesTest#aCrossSitePreflightIsNotApproved`
  expects no CORS headers on an anonymous cross-site preflight, which ADR 0006's
  CSRF decision rests on; Spring Security switches CORS on once a
  `CorsConfigurationSource` bean exists. `AwsConfigTest` checks the SQS client's
  call and attempt caps, and the Lambda's `TimeoutBudgetTest` that a batch whose
  DynamoDB calls all time out ends inside the function's `Timeout` in
  `lambda/template.yaml`; the Lambda's client settings moved to a constant, no
  behaviour change. `FixedErrorMessagesTest` asserts an unhandled exception's
  stack trace is logged, not only its text.
  `config/SecurityConfigJwtTest.java#anIssuerUriWithAudiencesStarts` starts the
  service on `issuer-uri` and `audiences` alone, as `application.yml` and
  `SECURITY.md` give, checking the bearer filter is on and the startup log names
  both checks and, for a public key, its source.

### Changed

- **The doc checkers know the console.** `scripts/refcheck.py` also checks
  citations of TypeScript, JavaScript module and CSS files, and
  `scripts/numbers.sh` prints the console's file, line, page and test counts.

- **Readiness no longer checks the database.** Its `db` indicator borrowed from
  the requests' pool of ten, so a dead shared database, or a hot flight's lock
  waiters filling the pools, would fail the probe on every pod at once, and
  withdrawing them all would make a busy flight an outage for every flight.
  `src/main/resources/application.yml` now makes the group `readinessState`
  alone: in a database outage readiness answers 200, each pod answers
  `503 DATABASE_UNAVAILABLE` with `Retry-After`, and `/actuator/health` still
  reports `db` and answers 503 (`HealthGroupsTest#rootHealthReportsTheDatabase`,
  `HealthGroupsTest#readinessLeavesTheDatabaseOut`). `doc/OPERATIONS.md` points
  the database alert at `db` and `hikaricp_connections_pending`, not readiness.
  Readiness used to answer 503 in a database outage; this release treats that
  as outside the versioning rule above, as the probes answer the kubelet and the
  load balancer, not API clients, and no answer under `/api/**` changes.

- **Java lines fit in 120 columns, and CI keeps them there.** Only an editor
  read `.editorconfig`'s `max_line_length = 120`; now the build job's step "Java
  lines fit in 120 columns" fails on a longer line in either module. Longer
  lines in the controllers, exception handler and tests are wrapped, with every
  compiled string, OpenAPI descriptions included, left exactly as it was.

### Fixed

- **Request bodies are capped.** A body over `app.http.max-body-bytes`
  (`HTTP_MAX_BODY_BYTES`, 16384 bytes by default) gets `413 PAYLOAD_TOO_LARGE`
  once its declared length or a read shows it, and nothing parses more. Before,
  huge `passengerName` values could exhaust the heap, and `FormContentFilter`
  read a form-encoded `PUT`, `PATCH` or `DELETE` body in full before the
  credentials were checked. `RequestBodyLimitFilter` refuses an oversized
  `Content-Length` unread, ahead of Spring Security, and counts a chunked body
  only as it is read; `GlobalExceptionHandler#handleMalformed` gives a chunked
  JSON body over it that 413, not `400 MALFORMED_REQUEST`. The OpenAPI document
  (three writes) and `doc/api.md` list the 413. Both 413s, like a 401 or 403,
  carry Spring Security's default headers, `X-Content-Type-Options: nosniff`,
  `Cache-Control` with `no-store` and `X-Frame-Options: DENY` among them.

- **A booking on a flown flight could be cancelled.** On a `DEPARTED` or
  `ARRIVED` flight, `DELETE /api/v1/bookings/{bookingId}` answered 200 and
  freed the seats; `BookingWriter#cancelBooking` now refuses an active booking
  with `409 BOOKING_NOT_CANCELLABLE` and changes nothing
  (`FlightStatus#acceptsCancellations`). One cancelled before departure still
  gets 200 with its original `cancelledAt`; one on a `CANCELLED` flight can
  still be cancelled. The 409 changes what the contract returns for a request
  it accepted, a major under the versioning rule above, but is treated as a
  fix: the 200 rewrote the record of a flight that had already flown, and
  relying on it meant relying on the defect.

- **Unknown and duplicate fields are refused.** An ignored unknown field let
  `"status": "DELAYED"` create a `SCHEDULED` flight with 201, and a key sent
  twice kept its last value. Both now get `400 MALFORMED_REQUEST` on the three
  writes with a body (`BookingControllerTest`, `FlightControllerTest`) through
  `spring.jackson.deserialization.fail-on-unknown-properties` and
  `spring.jackson.read.strict-duplicate-detection`, and the OpenAPI document
  names both in each 400 description and closes `BookingRequest`,
  `CreateFlightRequest` and `StatusUpdate` with `additionalProperties: false`.
  The open schemas let a request carry an extra field, so refusing one changes
  the answer to a request the contract accepted, a major under the versioning
  rule above, but like the 409 it is treated as a fix: the field was never
  read, so the 201 reported success when part of the request had been thrown
  away. The console sends only the listed fields.

- **Tests that proved less than their names said.** The first test to decode
  a valid token, `BearerTokenChallengeTest#aSignedTokensScopeMapsOntoTheRules`,
  checks that an RS256 `flights:read` token reads a flight but cannot book or
  read metrics. The no-session test asserts no session, not a `Set-Cookie`
  MockMvc never writes; the PostgreSQL replay race checks every caller gets
  the same booking; another booking keeps a seat sold in
  `ErrorContractTest.java#cancellationRepeatedAfterDepartureIsStillANoOp`.
  A default-order case that ran only its own sets is now named
  `controller/SortPolicyTest.java#plainOrderGetsAPlainIdTiebreaker`, and
  `FlightControllerTest.java#searchAcceptsPagingParameters` pins the real
  default. `ErrorContractTest` flights departing in 2030 and 2031 against the
  real clock would have failed six of its tests then; they now depart in 2099.

- **Validation messages.** `GlobalExceptionHandler#handleValidation` keeps a
  field's most basic violation (null, then blank, then size or range, then
  pattern), not whichever Hibernate Validator returned first, so an empty
  idempotency key or airport code gets one message every call; the key's
  pattern, like the others, accepts an empty string, so an empty key is only
  blank. A `passengerName` with an unpaired UTF-16 surrogate, which the
  fingerprint turned into `?`, let a name differing only there replay on the
  same key instead of getting a 409; it now gets
  `must not contain unpaired surrogates`. One of only no-break or zero-width
  spaces now gets `must not be blank`.

- **OpenAPI document corrections.** Paging is `page`, `size` and `sort` with
  their defaults (`@ParameterObject` on `FlightController#search` and
  `BookingController#byFlight`), not one required `pageable` whose Swagger UI
  sample `sort=string` got `400 UNKNOWN_SORT_PROPERTY`; the cap of 100 is not
  shown. `cancelledAt` is `string` or `null` and `FlightDto.status` refers to
  the `FlightStatus` enum component that `StatusUpdate` reads, with no change
  to the Java types or JSON. Both writes' 400 descriptions name the seat count
  and a text field sent as an array or object, not any wrong JSON type, as
  Jackson turns a number or `true` sent for a text field into text.

- **The 503 every operation can return.** The OpenAPI document gave five
  operations no 503 and four only `LOCK_TIMEOUT`, though
  `GlobalExceptionHandler#handleDatabaseUnavailable` gives any operation
  without a database connection, reads included, `503 DATABASE_UNAVAILABLE`.
  `OpenApiConfig#sharedResponses` now adds it to every operation under
  `/api/`, `Retry-After` to every 503 and `X-Request-Id` to every documented
  response, as `RequestIdFilter` sets it on every response the application
  handles; `LOCK_TIMEOUT` descriptions put a colon after the code, not a dash.

- **Control characters in query filters.** A NUL in `?origin=J%00K`,
  `?destination=J%00K` or `?flightNumber=A%00B` hit PostgreSQL SQLState 22021
  and got `409 DUPLICATE_REQUEST`, and H2 answered an empty page.
  `QueryParams#withoutControlCharacters` now answers a control character left
  after trimming with `400 MALFORMED_REQUEST` before any query and checks
  nothing else, so `?origin=J-K` is still an empty page; as a backstop,
  `GlobalExceptionHandler#handleDataIntegrity` gives any SQLState class 22 data
  error that 400. `FlightService#normalise` trims first, so `?origin=%00` lists
  every flight like `?origin=` and `?origin=%20` instead of an empty page
  (`FlightServiceTest`), and `doc/api.md` says a filter empty once trimmed
  counts as absent. A path flight number of controls alone that reaches the
  service, such as `/api/v1/flights/%01`, is still `404 FLIGHT_NOT_FOUND`.

- **SBOMs typed as applications.** Both `target/bom.json` files say
  `application`, not CycloneDX's default `library`, and the service module no
  longer attaches its SBOM over the one the Spring Boot parent embeds in the
  jar, which logged a replace warning on every build.

- **infra-lint pins the SAM CLI at 1.166.2.** setup-sam installed the latest
  release on every run, so its bundled cfn-lint (`sam validate --lint`) could
  turn a required check red with no template changed.

- **Dependency gates.** `trivy-fs` now sets `TRIVY_INCLUDE_DEV_DEPS` and
  dependency-review fails on `runtime, development`, so a fixable HIGH in the
  development packages that fill most of `web/package-lock.json` no longer
  passes both; `npm audit` reports nothing in either scope today. The
  dependency-graph probe keeps the answer's body, and a 403 naming a spent rate
  limit or a missing token permission fails the job, as CONTRIBUTING.md said a
  token problem would, rather than passing with a warning.

- **Dependabot.** Every group in `.github/dependabot.yml` now takes minors and
  patches only, as CONTRIBUTING.md gives a major its own pull request. It, the
  `Dockerfile`, `SECURITY.md` and `CONTRIBUTING.md` no longer say Dependabot
  moves both base images' digests: it has only ever offered the build stage a
  tag that changes the JDK, so that digest moves by hand. A CI comment says
  Dependabot can move that JDK within Maven 3 and the enforcer stops such a
  bump by failing the image job; `CONTRIBUTING.md`, ADR 0007 and both poms say
  the enforcer's upper bound keeps builds on the 21 that CI, the image and the
  Lambda runtime use.

- **The memory budget was half the off-heap need.** The deployment's comment
  allowed 128Mi outside the heap; native memory tracking on a laptop JDK 21,
  default profile, measured about 260 MiB committed before thread stacks. At
  `MaxRAMPercentage=75.0` a heap near its 576Mi ceiling would push the
  container past its 768Mi limit, to be killed with no `OutOfMemoryError`
  logged. The `Dockerfile` now gives the heap half the limit; the comment shows
  the measurement and, as native memory tracking is off in the image, points
  at `kubectl top pod` and the actuator's non-heap figure.

- **A full pool made callers wait 30 s for their 503.** The `postgres` and
  `prod` profiles kept Hikari's default 30 s `connection-timeout`, so a caller
  that gave up and retried would leave its request queued and never see the
  `503 DATABASE_UNAVAILABLE` with `Retry-After` from
  `GlobalExceptionHandler#handleDatabaseUnavailable`. Both now wait 5 s, longer
  than the 3 s `lock_timeout` and well inside callers' usual timeouts; the
  default H2 profile keeps 30 s for its concurrency tests.
  `DataSourceSettingsTest` checks the value each profile resolves to.

- **Scripts and the connection limit.** The `down.sh` stack sweep asked only
  for five finished states; it now lists every state but `DELETE_COMPLETE`, so
  it finds a stack still deleting (`deploy/aws/selftest.sh` has a case). The
  `up.sh` hand-off says to rename the deploy job before `DEPLOY_ENABLED` is
  set. `scripts/demo.sh` names defect log entries, not numbers the log lacks,
  and its Act 8 counts `/error` among the anonymous paths.
  `deploy/k8s/base/hpa.yaml`, `doc/DEPLOYMENT.md` and `doc/OPERATIONS.md` no
  longer say db.t4g.micro allows about 112 connections; it allows fewer, as
  `DBInstanceClassMemory` leaves out what the OS and RDS reserve, and they say
  to read the limit with `SHOW max_connections`.

- **The SAM recipe in `lambda/template.yaml`.** It sets `ap-south-1`, the
  region its teardown names, before the deploy. Its `sam local invoke` sets
  `DDB_TABLE=flight-status-events` and says to run after a deploy, since no
  table exists before one, as ADR 0009 now notes. The template, `up.sh` and
  `doc/DEPLOYMENT.md` said `sam deploy` reads `.aws-sam`; with
  `--template-file` it never does. The template drops its step that deleted
  it; the other two keep the removal only so a later bare `sam deploy` cannot
  pick up a stale build. The recipe has still never been run.

- **Queue and log retention.** `lambda/template.yaml#BookingEventQueue` set no
  `MessageRetentionPeriod`, so a backlog the Lambda stopped receiving would be
  deleted on day 4, never dead-lettered, with neither alarm notifying anyone
  and its outbox rows already counted as published. It now keeps 10 days; the
  dead-letter queue's 14 count from the original enqueue, leaving a message
  dead-lettered late at least 4 days.
  `lambda/template.yaml#BookingEventFunctionLogGroup` keeps 14 days, not 7, as
  a failed message's `FAILED <messageId>` line is the only record of why.
  `doc/OPERATIONS.md` and `doc/DEPLOYMENT.md` say a message still queued 10
  days after it was sent is deleted; `doc/OPERATIONS.md` adds that the default
  7-day `OUTBOX_RETENTION` has pruned its row by then, so it cannot be re-sent
  from the outbox, and its dead-letter queue playbook points at that line and
  counts 14 days from a message's first send.

- **H2's ENUM.** It upper-cases a lower-case `cancelled` rather than refusing
  it, so the H2 schema's tests and documents no longer claim it refuses every
  row PostgreSQL refuses.

- **Runbook claims corrected.** `doc/OPERATIONS.md` and `deploy/aws/README.md`
  check a Secret with `kubectl describe secret`, which lists each key and its
  size, not `kubectl get secret -o jsonpath='{.data}'`, which prints every
  value in base64, the database password included. `doc/OPERATIONS.md` and
  `doc/DEPLOYMENT.md` no longer put a replica's drain at about 100 events a
  second: `OutboxPublisher#drainOutbox` sends one row at a time and waits the
  poll interval after each drain, so both give
  `batch / (poll interval + batch × send latency)`, at most `1 / send latency`.
  Below that cap the playbook prefers a shorter interval to a bigger batch,
  which holds row locks longer, and at it adds replicas, up to the HPA's four.
  An event's latency counts the sends ahead of it, and a backlog, failed send
  or prune run adds more. The "Events stop arriving" command, which read only
  each pod's last 10 lines, passes `--tail=-1` and `--prefix`; the outbox
  playbook tells a dead row from a deferred one. `doc/DEPLOYMENT.md`, ADR 0009
  (with a dated correction), `deploy/aws/README.md` and the `down.sh`, `up.sh`
  and `cluster.yaml` comments say why the Ingress goes first, that an `up.sh`
  step checks first or is safe to repeat, and that a bad `aws-auth` edit cuts
  off only the roles it maps.

- **Doc claims corrected.** Not every body over the limit gets 413: a declared
  `Content-Length` over it does, unread; a chunked body does at the read that
  passes it, at binding (`GlobalExceptionHandler#handleMalformed`), not before
  authentication as `doc/ARCHITECTURE.md` had it; a body nothing reads is never
  counted. `doc/api.md` puts Tomcat's limit at Spring Boot's 8 KB default for
  the request line and headers together, and states the whole transition rule.
  `SECURITY.md` says the idempotency key, logged on a replay, is client-chosen
  and must hold nothing private, how far the load balancer controller's
  vendored policy reaches, that `RequestIdFilter` logs each answer of 400 or
  above, 5xx included, and that a `JwtDecoder` bean built in code escapes the
  startup check only while no decoder property is set. The HTTPS recipe
  suggests pinning `server.forward-headers-strategy: native`, not `framework`,
  which would trust forwarded headers from any caller.

- **Counts and references corrected.** `doc/OPERATIONS.md` no longer promises
  one `grep` for two searches. The defect log no longer gives the poisoned sort
  key as 29 characters, its timestamp half's length, and notes that both
  Surefire runs pin `Asia/Kolkata` where it says CI's UTC zone lets a zone bug
  ship green. It and the README no longer say most defects were reproduced
  against a running instance, nor the log that every entry names its commit.
  The ADR index dates its records in one sentence and marks 0016, the Oracle
  port, as a proposal from documentation, never built or run.
  `doc/ARCHITECTURE.md` shows the console as one more API client, and its
  binding row names the passenger name checks. The image size is measured on
  the pinned bases with the CA bundle; the root certificate troubleshooting
  row, the Oracle port's cost and the claim that the image is scanned once are
  corrected. Markdown prose over 80 columns is rewrapped where it can break,
  except in `README.md` and this changelog's released sections.

- **Comments corrected.** Code scanning, not the CodeQL category, keeps
  Trivy's results apart. `application.yml` says the outbox drain and the pruner
  share one scheduler thread and why that is acceptable, the JaCoCo comment in
  `pom.xml` gives the current coverage, and `OutboxPublisher`, ADR 0013 and the
  defect log agree a shift of the 2s base turns negative at attempt 54, not 64.

- **No statement timeout.** Under `postgres` and `prod`, a statement the
  database stopped answering could hold its connection for about 15 minutes and
  stop the outbox drain. Both profiles now give pgJDBC a 30 s `socketTimeout`,
  then the caller gets `503 DATABASE_UNAVAILABLE`; a longer migration statement
  takes its own connection through `spring.flyway.url`. `DataSourceSettingsTest`
  checks both set it and H2 does not; alert 6 in `doc/OPERATIONS.md` says so.

- **Tomcat and the ALB timed out together.** Tomcat's default 60 s idle timeout
  equalled the ALB's, risking a 502 with no line in the service's log.
  `server.tomcat.keep-alive-timeout` is now 75 s, so the load balancer closes an
  idle connection first (`TomcatKeepAliveTest`).

- **Isolation level left to the server.** The pool now sets
  `spring.datasource.hikari.transaction-isolation` to
  `TRANSACTION_READ_COMMITTED`, which `BookingWriter#insertNewBooking` needs;
  under a REPEATABLE READ `default_transaction_isolation`, every queued booking,
  cancellation and replay would abort with SQLSTATE `40001` as
  `503 LOCK_TIMEOUT` (ADR 0002, `DataSourceSettingsTest`).

- **Outbox gauges counted on the scrape thread.** With no database, a scrape
  would outlast Prometheus's default 10 s timeout and lose every series.
  `observability/OutboxMetrics.java#refresh` now counts `outbox_pending` and
  `outbox_dead` every 15 s on its own daemon thread; the gauges read the cache,
  `NaN` until the first count and once the last good one is over 45 s old.
  `doc/OPERATIONS.md` now describes the cache, where it said a failed count left
  the rest of the response unaffected.

- **SQS send lines lacked the booking's trace.**
  `service/OutboxPublisher.java#drainOutbox` now puts each event's stored
  `traceparent` in the MDC while it sends that event, then removes it, so in the
  ECS JSON log the send line and the drain's warnings for that event carry it as
  a field. Log messages are unchanged.

- **The flight log ran ahead of the write.**
  `service/FlightService.java#updateStatus` and `#cancel` left the UPDATE to the
  commit, so a race's loser logged the change and then got a 409, or a 503 after
  a lock timeout. Both now flush before the log line, so a refused write fails
  the call first.

- **Unusable flight rows.** A flight inserted by plain SQL got a NULL
  `flights.version`, and every booking, cancellation and status change on it
  failed at flush with a 500.
  `src/main/resources/db/migration/V9__flights_version_not_null.sql` sets any
  NULL to 0 and makes the column `NOT NULL DEFAULT 0`, as `Flight#version` does
  for H2. On PostgreSQL a hand-written `CANCELED` or `cancelled` status was
  stored and made every read of its flight a 500. `V10__flight_status_check.sql`
  adds `ck_flights_status` as `NOT VALID` and validates it in the same
  transaction, harmless at this size; its header says to move the `VALIDATE` to
  its own migration on a large table. A new `FlightStatus` constant needs a
  migration replacing the check, as its Javadoc says.
  `SchemaConstraintsPostgresTest`, now required by the CI step "The PostgreSQL
  tests ran", shows PostgreSQL refusing a bad status and a NULL version and
  booking a flight inserted without one. `FlightRepositoryTest` shows H2
  refusing `CANCELED` and a NULL version; H2 stores `cancelled` as `CANCELLED`
  rather than refusing it.

- **The default flight list sorted the whole table.** No index gave the
  `departure_time, id` order of an unfiltered `GET /api/v1/flights`.
  `V11__flights_departure_time_index.sql` builds `idx_flights_departure_time` on
  `(departure_time, id)` with `CREATE INDEX CONCURRENTLY`, alone so Flyway runs
  it outside a transaction, and `Flight` declares it for H2. With
  `spring.flyway.postgresql.transactional-lock: false` in the base document of
  `application.yml`, Flyway holds no transaction open on a second connection
  while the index builds. The page's `count(*)` still reads every row.
  `scripts/numbers.sh` now lists migrations by version, where the byte order of
  `git ls-files` would put `V10` and `V11` before `V2`.

- **Null order differed by database.** `SortPolicy#stable` now puts nulls last
  in both directions on the properties in a controller's `NULLABLE`, today only
  the booking's `cancelledAt`, so active bookings come last on both databases,
  as `doc/api.md` says. On PostgreSQL that changes `,desc`, which listed them
  first; the contract had never said where they went. `NOT NULL` properties and
  the `id` tiebreaker get no null handling, which on PostgreSQL would cost
  `?sort=id,desc` its primary key scan.

- **A misspelt profile ran on in-memory H2.** Profile names are case-sensitive,
  so under `SPRING_PROFILES_ACTIVE=Prod` each pod would run its own H2 with the
  demo flights, pass readiness and publish to the real queue.
  `config/EmbeddedDatabaseGuard.java#refuseInMemoryH2WithDbUrl` now stops
  startup when `DB_URL` is set and the datasource is in-memory H2, naming the
  active profiles and asking for `prod` in the cluster or `postgres` for compose
  or a local PostgreSQL, never `postgres` in the cluster, which seeds the demo
  flights and accepts an unhashed password. It refuses default-profile runs and
  H2 tests with `DB_URL` exported too, saying to unset it. `doc/OPERATIONS.md`
  describes the guard under Profiles.

- **`OUTBOX_ENABLED=yes` stopped the drain.** `OutboxProperties` binds `on`,
  `yes` and `1` as true, but `OutboxPublisher` and `OutboxPruner` compared the
  string, so neither bean was created and outbox rows piled up unsent and
  unpruned with no error. Both now use
  `config/OutboxEnabledCondition.java#getMatchOutcome`, which binds
  `app.outbox.enabled` as the record does, default true; a non-boolean value or
  an empty `OUTBOX_ENABLED=` stops startup, naming the property
  (`OutboxEnabledConditionTest`). The record's unread `enabled` now defaults to
  true too; before, a missing property bound false while the beans ran.

- **The smoke test could check the old release.** Its port-forward to
  `deployment/flight-ops` could reach the last old pod in its `preStop` sleep,
  so the test could pass on the old image or fail when that pod exited. The step
  in `.github/workflows/build-and-deploy.yml` now forwards to a Running, Ready
  pod of the Deployment's current revision whose container runs this commit's
  image, prints the pod and the image, fails without one, and checks the root
  `/actuator/health` is `UP`.

- **Re-running an old run could roll the cluster back.** A new deploy job step,
  "Is this commit still the head of main?", compares the run's commit with the
  line of `git ls-remote origin refs/heads/main` whose ref is exactly
  `refs/heads/main`. A superseded commit pushes and applies nothing and passes,
  with a notice and a job summary line pointing at a manual run on `main`.
  `doc/OPERATIONS.md` says not to re-run earlier commits' runs, which lack the
  step, while GitHub still allows it.

- **The ECR lifecycle policy could expire a running image.** It kept the last
  five tagged images, so five failed deploys in a row would expire the old pods'
  image. The deploy job now tags an image `deployed-<sha>` once its smoke test
  passes, with permissions the CI role already had, and a new first rule in
  `deploy/aws/foundation.yaml#EcrRepository` keeps the last ten such images. A
  release failing its smoke test after its rollout completes is serving
  untagged, so the rollback playbook says to roll it back.

- **Rollback and the schema.** The workflow's rollback comment and a new
  playbook in `doc/OPERATIONS.md` say `kubectl rollout undo` does not undo
  migrations, so each must keep the previous release working (expand now,
  contract in a later release); after more than one failed deploy, go back to
  the newest revision with a `deployed-<sha>` image tag.

- **Rollouts wait for the load balancer.** `deploy/k8s/namespace.yaml` labels
  the namespace `elbv2.k8s.aws/pod-readiness-gate-inject: enabled`, so a pod
  created while the Ingress exists is Ready only once the ALB reports its target
  healthy. `deploy/k8s/base/deployment.yaml` sets a 15s preStop sleep (was 5s)
  and `terminationGracePeriodSeconds` to 55, and
  `src/main/resources/application.yml` follows. Running pods get the gate at the
  next rollout, pods in the namespace are created only while the controller's
  webhook answers, and an existing namespace takes the label only when `up.sh`
  or an operator re-applies `namespace.yaml`, as CI's role cannot.

- **The scripts keep their own kubeconfig.**
  `deploy/aws/lib.sh#use_private_kubeconfig` exports `KUBECONFIG` as
  `deploy/aws/.state/kubeconfig`, mode 0600, at the start of step 4, before
  eksctl, so eksctl and `aws eks update-kubeconfig` no longer write the
  operator's `~/.kube/config` or switch its context. `down.sh` uses that file in
  place of a temporary one and deletes it only after a clean run; the summary
  and error hints print the `export` line. `deploy/aws/selftest.sh` checks the
  mode, the order and that no call uses the caller's file.

- **A new database behind an old Secret.** A re-run of `up.sh` that made a new
  database left the old password in `flight-ops-secret`, so every pod would fail
  at Flyway's first connection. Step 6 now records the new database in
  `deploy/aws/.state/flight-ops.env` before it starts; at step 9
  `deploy/aws/lib.sh#reconcile_secret_db_password` patches only `DB_PASSWORD`,
  on stdin, never in an argument or a file, and restarts the deployment if it
  exists. A run stopped before step 9 makes the next stop there and say how to
  set a new password; the re-run's restart finishes a stop between patch and
  restart. A shell's exported `DB_PASSWORD` is written in, to this Secret or a
  new one, after `deploy/aws/lib.sh#warn_db_password_from_shell` says what a
  stale one does and how to give the database the Secret's password. A
  deployment lookup failing for any reason but "not found" stops the run; the
  API and ops hashes stay; a re-run with nothing recorded leaves the Secret
  alone (`deploy/aws/selftest.sh` covers each path).

- **What down.sh leaves to you.** A clean teardown left `DEPLOY_ENABLED` set, so
  the next `main` push would target a deleted cluster. The step 0 plan says to
  set it to `false` first; the closing message repeats it and says to rename the
  job back to `deploy (gated off)`, with `CONTRIBUTING.md` in the same commit.

- **Script hints and a header that misled.** When `/actuator/health` never
  answered, step 11 of `deploy/aws/up.sh` blamed only the load balancer; it now
  says a down database, which readiness leaves out, stops the run there too, and
  gives the ops user's call that shows which component is not `UP`. Printed
  commands such as the `export` line quote their paths, so they work from a
  checkout whose path holds a space, as a new `deploy/aws/selftest.sh` check
  confirms. The `scripts/sweeps.sh` header now says a missing `SWEEP_PATTERNS`
  is a skip on every pull request, this repository's own included.

- **Malformed query strings were a 500.** A query value not valid as
  percent-encoded UTF-8 (`?origin=%FF`) or a name with a control character
  (`?%0D%0AFORGED=1`) was `500 INTERNAL_ERROR`, on the public `/actuator/health`
  too, with an ERROR stack trace that could forge a log line.
  `exception/GlobalExceptionHandler.java#handleMalformed` now gives both
  `400 MALFORMED_REQUEST` and one WARN line through
  `GlobalExceptionHandler.java#printable`
  (`ContainerErrorDispatchTest.java#aBadlyEncodedQueryValueIsA400`).

- **A form-encoded body was parsed before the credentials.** A bad escape
  (`a=%zz`) in a form-encoded `PUT`, `PATCH` or `DELETE` body was a 500 with an
  ERROR stack trace on any path, even unauthenticated. The writes read JSON
  only, so with form parsing off (`spring.mvc.formcontent.filter.enabled`) such
  a request without credentials gets 401
  (`RequestBodyLimitTest.java#aFormBodyIsNotReadBeforeTheCredentials`);
  `SECURITY.md` no longer says an oversized form body gets 413 before the
  credential check.

- **`/logout` answered 204 to anyone.** With CSRF off, the logout filter matched
  `GET`, `POST`, `PUT` and `DELETE` ahead of the rules and their closing
  `denyAll()`, for any caller, a wrong password included. As the API keeps no
  session, `config/SecurityConfig.java#apiSecurityFilterChain` turns it off, and
  the path is denied like any other no rule names
  (`SecurityRulesTest.java#logoutIsDenied`).

- **The bearer-token metadata was open and claimed certificate-bound tokens.**
  With bearer tokens on, RFC 9728 metadata is served to any caller, ahead of the
  rules, at `GET /.well-known/oauth-protected-resource` and under it;
  `SECURITY.md` and `config/SecurityConfig.java` said `denyAll()` closes the
  list, and now name the path.
  `config/SecurityConfig.java#apiSecurityFilterChain` turns off the metadata's
  claim that tokens are bound to a client certificate, which nothing here checks
  (`BearerTokenChallengeTest.java#theProtectedResourceMetadataIsPublic`).

- **A padded status name was applied.** Jackson's enum reader retries an unknown
  name trimmed, so a `PATCH` with `" DELAYED"`, or `CANCELLED` between a NUL and
  a tab, changed the status. `dto/StatusUpdate.java#ExactName` reads the exact
  name only; anything else is `400 MALFORMED_REQUEST`, as an unknown name was
  (`MalformedRequestTest.java#unreadableStatusBodiesGetTheFixedMessage`).

- **A header line Tomcat refused was logged in full.** Tomcat quoted it at INFO,
  so a stray control character in `Authorization` logged the credential, with no
  request id. `org.apache.coyote.http11.Http11Processor` now logs at WARN
  (`src/main/resources/application.yml`); the caller still gets the 400
  (`ContainerErrorDispatchTest.java#aRefusedHeaderLineIsNotLogged`).

- **Doc claims corrected.** `doc/api.md` says a `HEAD` on `/actuator` itself is
  403, from `ops` too, as Boot's matcher there covers `GET` only. `SECURITY.md`
  names the two console answers Next writes itself without the three hygiene
  headers: the 500 for a malformed percent escape and the 308 for a trailing or
  doubled slash. `doc/api.md`, which said only bcrypt or pbkdf2 passes the three
  startup checks without BouncyCastle, the playbook in `doc/OPERATIONS.md` and
  the known limitation in `SECURITY.md` now say a malformed `{scrypt}` or
  `{argon2}` value, such as `{scrypt}REPLACE_ME`, passes all three, as
  `{bcrypt}REPLACE_ME` does, and the scrypt encoder logs nothing.

### Security

- **The deploy job pushes the image CI scanned, and runs no scanner.** It built
  and scanned its own copy under the deploy role, using a Trivy CLI outside the
  action's SHA pin. The `image` job in `.github/workflows/build-and-deploy.yml`,
  with no AWS credentials, alone builds and scans: on a run that can deploy it
  records the saved image's sha256 before the scan and uploads it for a day once
  the scan passes, and the deploy job checks the sha256 and pushes those bytes.
  kubectl is still downloaded with no checksum held here and runs with the AWS
  keys; job-level `id-token: write` puts the OIDC request variables in every
  step. The job is gated off and has never run.

- **The controller's IAM policy is pinned and renamed.** Step 8 of `up.sh`
  fetched it unchecked by a movable Git tag. The v3.5.0 document is committed
  (`deploy/aws/up.sh#LBC_POLICY_FILE`) and checked against
  `deploy/aws/up.sh#LBC_POLICY_SHA256` with `deploy/aws/lib.sh#require_sha256`
  at step 1, before anything bills, and step 8, as `deploy/aws/selftest.sh` does
  in CI; `doc/DEPLOYMENT.md` gives its source and how to move to a new release.
  The old name, `AWSLoadBalancerControllerIAMPolicy`, let `up.sh` attach, and
  `down.sh` delete, another cluster's copy. It is now `flight-ops-lbc-v3.5.0`
  (`deploy/aws/lib.sh#LBC_POLICY_PREFIX` and the tag), tagged
  `Project=flight-ops`; `down.sh` deletes only that prefix, and the self-test
  checks it never names the generic one.

- **Bearer tokens need an audience.** Boot checks `aud` only with `audiences`
  and `iss` only with `issuer-uri`, so `issuer-uri` alone accepted another
  tenant client's token, and `jwk-set-uri` alone one from any key in the set.
  `config/SecurityConfig.java#requireIssuerAndAudience` now stops startup,
  naming the property and the reason, when `issuer-uri`, `jwk-set-uri` or
  `public-key-location` lacks `audiences`, or `jwk-set-uri` lacks `issuer-uri`,
  and logs what the decoder checks (`SecurityConfigJwtTest`).

- **Only hashed passwords under `prod`.** `{noop}` and `{ldap}` can hold plain
  text, so `config/SecurityConfig.java#refuseUnhashed` stops a `prod` start on
  any id but `bcrypt`, `pbkdf2`, `scrypt` and `argon2` (the last three with or
  without `@SpringSecurity_v5_8`), naming
  `app.security.api-password (API_PASSWORD)` or
  `app.security.ops-password (OPS_PASSWORD)`, never the value. It runs after the
  self-check, so a misspelt id still gets the encoder's reason. The default
  profile keeps `{noop}dev-secret` and `{noop}dev-ops`.

- **Local compose listens on loopback only.** `compose.yaml` published
  `8080:8080` on every interface, where on Linux Docker's rules bypass ufw. It
  publishes `127.0.0.1:8080:8080`; the commented database mapping, the
  `docker run` recipe in `doc/DEPLOYMENT.md` and the `postgres` comment in
  `application.yml` use `127.0.0.1:5432:5432`. `doc/DEPLOYMENT.md` says what a
  public single-instance setup needs.

- **Pods hold no API token and cannot reach the node role.** The application
  never calls the API server, so `automountServiceAccountToken` is false in
  `deploy/k8s/base/serviceaccount.yaml` and `deploy/k8s/base/deployment.yaml`;
  IRSA is unaffected. For new node groups, `deploy/aws/cluster.yaml` sets
  `disablePodIMDS` on `ng-1` and drops the unused `cloudWatch` add-on policy;
  `up.sh` passes the controller the `region` and `vpcId` it once read from IMDS.

- **No password on a command line.** Step 9 of `up.sh` passed the database
  password and bcrypt hashes as `--from-literal` arguments and the passwords to
  `htpasswd -b`, visible in the process list. Now `htpasswd -i` reads stdin, and
  `deploy/aws/lib.sh#create_secret` pipes `kubectl create -f -` the same Secret,
  each value base64-encoded by `openssl`, as is the `DB_PASSWORD` patch, so a
  quote or backslash survives. `deploy/aws/selftest.sh` checks that no value,
  plain or encoded, is an argument; step 6 still passes the password to
  `aws cloudformation deploy`. The example Secret's `htpasswd -b` comment and
  the `doc/OPERATIONS.md` rotation recipe's `kubectl patch -p` use stdin too.

- **Passenger names refuse text-direction controls.** A `passengerName` holding
  U+202A to U+202E, U+2066 to U+2069, U+200E, U+200F, U+061C, U+2028 or U+2029
  gets `must not contain text-direction controls or line separators`; U+200C and
  U+200D pass, as some scripts and emoji need them. The published pattern
  accepted these; refusing them is treated as a fix, like the unpaired-surrogate
  rule under Fixed, because these change only how a name is displayed, not what
  it is, and can mislead readers in the console or `GET /api/v1/bookings`.

- **The console's race route takes only a JSON body.** `web/lib/race.ts#runRace`
  made ten JSON bookings of a `text/plain` or form body, which another site can
  send without a preflight; it now answers `415 CONSOLE_UNSUPPORTED_MEDIA_TYPE`
  before reading it (unit and end-to-end tests). A new end-to-end test checks
  pages and proxied answers for the console's three security headers and no
  `X-Powered-By`; `web/next.config.ts` wrongly said the API's own security
  headers pass through.

- **The image is built from pinned base images.** Both `FROM` lines in the
  `Dockerfile` named only a tag, taking whatever it pointed to that day; each
  now adds a sha256 digest. The docker entry in `.github/dependabot.yml` moves
  the runtime digest when upstream rebuilds the tag, the only way OS and JRE
  fixes reach it, and now runs weekly. The build stage's digest moves by hand:
  for it Dependabot has offered only `maven` tags that change the JDK, which the
  enforcer fails. `# syntax=docker/dockerfile:1` still floats.

- **The database connection verifies the server.** With no `sslmode`,
  `deploy/aws/data.yaml#JdbcUrl` got the driver's `prefer`: no certificate or
  host name check, and plaintext on a TLS refusal. It now sets
  `sslmode=verify-full`, with `sslrootcert` naming the RDS global CA bundle,
  `certs/rds-global-bundle.pem`, in the image. `doc/DEPLOYMENT.md` gives its
  source, checksum and refresh steps; `SECURITY.md` covers the database leg
  under Transport. A `DB_URL` repository variable copied from the old output
  must be replaced by hand; local runs, compose and CI keep their own URLs.

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
