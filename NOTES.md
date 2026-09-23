# Bugs I found and fixed

These are the defects I found in this service and how I fixed them. I
reproduced most of them against a running instance before changing the code.
Where I found one by reading the code or its documentation instead, the entry
says so. Each entry says what I saw, why it happened, what I changed, the test
that pins it where one exists, and the commit the fix landed in. The
[README](README.md#what-i-found-in-review) has the short list.

| Section | What broke |
|---|---|
| [Seats sold on a cancelled flight](#seats-sold-on-a-cancelled-flight) | `reserveSeats` checked the seat count and ignored the flight status |
| [A Location header that led to a 404](#a-location-header-that-led-to-a-404) | a test checked the header's text and never followed it |
| [One idempotency key, two answers](#one-idempotency-key-two-answers) | racing replays got 201 or 409 for one booking |
| [A booking lookup that failed on every call](#a-booking-lookup-that-failed-on-every-call) | a lazy association read after the session closed |
| [Paging that could skip a row](#paging-that-could-skip-a-row) | a bad sort was a 500, and a tied sort was unstable |
| [The outbox and a slow queue](#the-outbox-and-a-slow-queue) | an SQS call with no timeout, then a poison row and a ceiling measured in seconds |
| [Metrics under the wrong names](#metrics-under-the-wrong-names) | `bookings.created` exported as `bookings_total` |
| [A teardown that could pass on an error](#a-teardown-that-could-pass-on-an-error) | an expired token read like an empty account |
| [The Boot 4 upgrade](#the-boot-4-upgrade) | what the 3.5 to 4.1 bump broke |
| [First review pass](#first-review-pass) | the rest of the first read-through |
| [Second review pass](#second-review-pass) | failures that stay green on a laptop |
| [Third review pass](#third-review-pass) | the gaps I had listed as open |
| [Fourth review pass](#fourth-review-pass) | input that reached a URL, a log line or a count unchecked, startup checks that passed a bad password or blamed the wrong setting, and a deploy path that could not build |

---

## Seats sold on a cancelled flight

`Flight.reserveSeats` guarded the seat count. It did not guard the flight
status. Three `curl` calls against a running instance showed it:

```
DELETE /api/v1/flights/UA123      → 204, status CANCELLED
POST   /api/v1/bookings (4 seats) → 201            ← should have been refused
GET    /api/v1/flights/UA123      → availableSeats 180 → 176
```

Each sale also published a `BookingCreated` event. Under
`app.events.publisher: sqs` the bad data would have reached a second store with
no way to know the flight was cancelled. Every test passed the whole time,
because every test asserted a rule I had already thought of.

The fix puts the missing rule next to the existing one:

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

**Bookability lives on the enum.** If the service checked
`status != CANCELLED`, the next caller (a bulk import, an admin endpoint, a
message consumer) would forget it. `FlightStatus.isBookable()` is an exhaustive
`switch` with no `default`. Adding a status breaks the build until someone
decides whether it is bookable. A `default -> true` would give the new status
an answer nobody chose.

**A new exception type.** I did not reuse `InsufficientSeatsException`. Its
message is "Flight X has N seat(s) available, M requested". On a cancelled
flight with 176 free seats that would be false, and it would send the client
into a retry-with-fewer-seats loop that never ends. The new one has the same
409 status, a different code and different advice.

**Status before seats.** The status check runs first, so a cancelled full
flight says "CANCELLED" instead of "not enough seats". The order of two guards
is not usually a decision. Here it changes what the client does next.

**Release stays unguarded.** `releaseSeats` has no status check, because
refunds happen on the flights that were cancelled. Making `cancel()` throw on
an already-cancelled flight would make `DELETE` non-idempotent, which is a
worse bug than the one it prevents. Tests assert both, so neither gets made
"consistent" later.

The transition graph came next. `updateStatus` used to allow any status to
become any other, so a `PATCH` back to `SCHEDULED` un-cancelled a flight and
put its seats back on sale. `FlightStatus.canTransitionTo` is now the graph.
`ARRIVED` and `CANCELLED` are terminal, and `DEPARTED` can only become
`ARRIVED`. A status may always move to itself, so a retried `PATCH` answers 200.
It uses the same exhaustive `switch` with no `default`, for the same reason.

`FlightTest.cancelledFlightIsNotBookable`, `releaseSeatsIsNotStatusGuarded`,
`everyStatusIsClassified` and `cancelIsIdempotent` pin the entity rules.
`ErrorContractTest.cancelledFlightCannotBeRevived` pins the graph through the
real stack. The status guard is in the first commit, `4a9a5b9`. The transition
graph came in `eac8cc4`.

## A Location header that led to a 404

`POST /api/v1/bookings` answered `201 Location: /api/v1/bookings/1`. There was
no `GET /api/v1/bookings/{id}`, so that URL returned 404. A green suite covered
it the whole time, because the test asserted the header's string value and
stopped:

```java
.andExpect(header().string("Location", "/api/v1/bookings/1"))   // proves the text, nothing else
```

The replacement follows the header instead of trusting it:

```java
String location = mockMvc.perform(post("/api/v1/bookings")...)
        .andReturn().getResponse().getHeader("Location");
mockMvc.perform(get(location)).andExpect(status().isOk());       // this would have 404'd
```

This is the cancelled-flight lesson again. A test that asserts the mechanism
passes, and a test that asserts the consequence catches things. I found both
bugs by probing a running instance, and both now have tests that follow through
to the outcome.

Both `Location` headers are now built from the returned DTO. `FlightService`
normalises flight numbers (trim and upper-case), so `POST /api/v1/flights` with
`{"flightNumber":" ua999 "}` answers `Location: /api/v1/flights/UA999`. That is
the only form that resolves. I followed both headers with `curl` and got 200
from each.

`BookingControllerTest.locationHeaderResolves` and
`FlightControllerTest.createReturns201WithAResolvableLocation` pin it. Both
fixes are in the first commit, `4a9a5b9`.

## One idempotency key, two answers

`BookingController`'s Javadoc promised that every replay of an idempotency key
gets `201`. `GlobalExceptionHandler`'s Javadoc promised that a race loser gets
`409`. They contradicted each other, and no single request could show which
was true, because a single request never races itself. Ten threads on one key
did:

```
4 callers -> 201
6 callers -> 409
```

That was one logical booking. The controller's Javadoc was the false one.

The fix splits the write into two transactions on a second bean,
`BookingWriter`, so the recovery step runs in a fresh transaction. This is the
catch block, cut down to the recovery call. The first version caught only the
constraint violation and took only the key. Today's version also rethrows the
original failure when no booking holds the key, because then there was no race
to lose:

```java
try {
    return bookingWriter.insertNewBooking(request);
} catch (LostIdempotencyRaceException | DataIntegrityViolationException e) {
    return bookingWriter.recoverReplay(request.idempotencyKey(), fingerprint);   // ← the fix: 201, not 409
}
```

`BookingService.book` is no longer `@Transactional`. Both transaction
boundaries live on `BookingWriter`, called through Spring's proxy, which is
what makes the annotations apply at all. A call through `this.` would bypass
them. That split is the fix. While `book` was `@Transactional`, the recovery
read ran inside the transaction PostgreSQL had already marked aborted. It got
`current transaction is aborted` instead of the winner's row. Now `book` sits
outside any transaction, and Spring has rolled the failed one back before the
catch block runs. `REQUIRES_NEW` on `recoverReplay` is insurance for the day
`book` becomes transactional again.

`BookingIdempotencyTest.racingTenCallersOnTheSameKeyAllGetTheSameBooking` pins
it. Ten threads send one key, every caller gets the same booking id back, one
row exists and one seat is debited. `BookingServiceTest.racingTheWriterRecoversTheWinner`
and `theWritersLostRaceSignalAlsoRecovers` cover both signals with the writer
mocked. The fix is `d4e0113`.

Some contracts are only false under concurrency. The test that catches them has
to create the race, and restating the single-request case twice does not.

### A replay must be the same request

Reusing a key with a different payload used to return the original booking
with 201. A different passenger or a different flight got the first booking
back. Each booking now stores a SHA-256 fingerprint of the request that created
it (`V3__booking_request_fingerprint.sql`). A key that arrives with a payload
that does not match gets `409 IDEMPOTENCY_KEY_REUSED`.

The check is in both places: the pre-flight read in `BookingService.book` and
the recovery in `BookingWriter.recoverReplay`. Under a real race none of the
callers sees the others in the pre-flight read. All of them go on to the insert,
and the losers land in recovery. A comparison in only the first place looks
complete and is not.

Adding the check turned an existing test red. The ten-caller race sent "Racer
0" through "Racer 9" on one key and asserted that all ten got the same booking,
so it had the bug written down as the contract. It is now two tests.
`BookingIdempotencyTest.racingCallersWithDifferentPayloadsOnOneKeyGetConflicts`,
`BookingWriterTest.recoverReplayRejectsAReusedKey` and
`ErrorContractTest.reusingAKeyForADifferentBookingIsAConflict` pin it. The fix
is in `eac8cc4`.

### The last seat

A replay that arrived after the winner took the last seat failed on seat
availability. It never reached the unique constraint, so it never reached
recovery. `BookingWriter.insertNewBooking` now locks the flight row and checks
the key again under that lock. A loser queued behind the winner finds the
winner's row even when the winner took the last seat, and
`LostIdempotencyRaceException` sends it to `recoverReplay`.

The unique constraint stays as the backstop for the one case the lock cannot
serialise: the same key sent for a different flight. Different flights take
different row locks.

`BookingIdempotencyTest.racingCallersOnTheLastSeatAllGetTheSameBooking`,
`BookingWriterTest.racingReplayOnTheLastSeatIsNotAnOversell` and
`theReCheckIsInsideTheLock` pin it. The fix is in `50e8871`.

## A booking lookup that failed on every call

The Location fix made `GET /api/v1/bookings/{id}` something a client would
follow. Following it far enough showed that the endpoint threw on every real
lookup, with no concurrency involved:

```
org.hibernate.LazyInitializationException: Could not initialize proxy [Flight#4] - no session
```

`Booking.flight` is `@ManyToOne(fetch = LAZY)`, and `BookingDto.from`
dereferences it. `BookingService.findById` had no `@Transactional` and called
the inherited `JpaRepository.findById`. The race fix had given
`findByIdempotencyKey` and `findByFlightNumber` a `JOIN FETCH`, and this plain
lookup had none. The repository's own short-lived session had closed by the
time the DTO mapping ran.

`BookingControllerTest.locationHeaderResolves` mocks
`bookingService.findById(...)`, which is why the suite never caught it. That
test proves the controller calls the method. It says nothing about whether the
method works.

```java
@Transactional(readOnly = true)               // ← the fix
public BookingDto findById(Long bookingId) {
```

`BookingFindByIdLazyLoadingTest.sequentialCreateThenFetchByIdDoesNotThrow` pins
it. Nothing in its chain is mocked, and the test class has no `@Transactional`
of its own. A test-managed transaction would keep the session open for the
whole test and let this bug pass with the fix reverted. The fix is `b24caea`.

## Paging that could skip a row

`GET /api/v1/flights?sort=nonsense` returned `500 INTERNAL_ERROR`. Spring Data
resolves the sort property inside the repository proxy, past anything a
controller validates, so `PropertyReferenceException` reached the catch-all. A
typo read as "we are broken". It is now `400 UNKNOWN_SORT_PROPERTY`, naming the
property and nothing else. `ErrorContractTest.unknownSortPropertyIsABadRequest`
pins it, from `eac8cc4`.

Both list endpoints also paged on a non-unique sort key. Two flights sharing a
departure time could appear twice, or not at all, across pages. The Javadoc of
both endpoints promised a tie-breaker and neither implemented one, which is how
I found it.

`SortPolicy` now does both jobs in one place. It checks the sort property
against a list each endpoint publishes, and it appends `id` as a tie-breaker
when the caller did not sort on it. The list leaves out `idempotencyKey`,
because sorting on it would hand back other people's keys one bit at a time.
`ErrorContractTest.idempotencyKeyIsNotSortable` pins the list. `SortPolicy`
landed in `50e8871`. I have not written a test that pins the tie-breaker yet.

## The outbox and a slow queue

`SqsClient` was built with SDK defaults and no `apiCallTimeout`, which means no
timeout. It published inside the booking transaction, holding the flight row
lock and one of ten pool connections. An SQS endpoint that accepted the
connection and stopped answering would have blocked every booking for that
flight until the socket gave up. I bounded it at 5s overall and 2s per attempt
in `eac8cc4`. The attempt timeout alone would have been a subtle mistake,
because three retries of 2s is a 6s call.

Then I took the publish out of the transaction. No ordering of a database write
and a queue send is atomic:

- Publish, then commit: the transaction rolls back and a `BookingCreated` event
  exists for a booking that does not. The Lambda projects a phantom reservation
  into DynamoDB and nothing ever corrects it.

- Commit, then publish: the process dies between the two, and the booking
  exists with no event. The seats are gone from the primary store and the read
  model never hears about it.

Neither shows up in a test that does not kill the process at the wrong moment.
`OutboxWriter.recordBookingCreated` now inserts the event into `outbox_events`
in the booking's transaction, on the same connection. A `@Scheduled` poller
publishes it afterwards. That landed in `e83d846`, and
[adr/0001](adr/0001-transactional-outbox.md) records the decision.

The writer is `@Transactional(propagation = MANDATORY)`. Called outside a
transaction it would otherwise work, and throw away the atomicity it exists
for. `MANDATORY` turns that mistake into an `IllegalTransactionStateException`
on the first call. `OutboxTest.recordingOutsideATransactionIsRefused` pins it.

The drain has a `try`/`catch` per row. If one failure propagated, it would roll
back `markPublished` on rows whose payloads had already left the process. One
bad event would become many duplicates on the next drain.
`OutboxTest.oneBadEventDoesNotPoisonTheBatch` pins it.

### A poison row

The claim is `ORDER BY id`, so an event the transport always rejects was
retried first on every tick. It spent the whole batch failing while live events
queued behind it. One bad row meant a total publishing outage, and waiting
would never fix it.

The claim now carries `AND attempts < :maxAttempts`. After ten attempts the row
drops out, `outbox_dead` rises above zero, and a WARN names the id and the
booking. Bringing the row back is a manual step:

```sql
UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?
```

`OutboxPoisonRowTest.resettingAttemptsRedrivesTheRow` runs that statement, so
the one an operator pastes is a tested one. `anExhaustedRowDropsOutOfTheClaim`
pins the ceiling. The ceiling landed in `a6efc1c`.

### Backoff

The ceiling needed a backoff, or it was a ceiling measured in seconds. The
poller runs every second. Before `next_attempt_at` existed
(`V7__outbox_next_attempt_at.sql`), a transport error that fails fast burned all
ten attempts on every row in about ten seconds. A wrong queue URL, an expired
credential or a DNS failure would dead-letter the whole backlog before an alert
could fire. The signal pointed the wrong way too. A dead row leaves
`outbox_pending`, so the gauge an operator watches fell to zero while the
service was losing every event.

A failed row now waits `app.outbox.retry-backoff` (2s), doubling per attempt up
to a `max-retry-backoff` cap (5m). The claim skips a row whose time has not
come, so ten attempts span about thirteen minutes. The doubling is a bounded
loop. A shift, `base << (attempt - 1)`, is one character shorter and wrong at
attempt 64.

`OutboxRetryBackoffTest.aBurstOfDrainsDoesNotBurnTheCeiling` and
`theWaitDoublesUpToTheCap` pin it. The backoff landed in `50e8871`.

### Retention

Published rows were kept forever. Nothing breaks for months: the partial index
covers only unpublished rows, so the poller keeps performing while the table
under it grows. The first symptom would have been a backup window or a disk
alert on a Sunday.

`OutboxPruner` now deletes rows published more than `app.outbox.retention` ago
(7 days). It works in batches of 1,000, each in its own transaction, with at
most 50 batches per run. The bounds are for the first run on an old table. A
single `DELETE FROM outbox_events WHERE published_at < :cutoff` against a year
of rows would take row locks over the whole range. It would write a WAL burst
large enough to stall replication, and shut the poller out until it committed.
Unpublished rows are never deleted at any age, because an undelivered event is
a backlog.

`OutboxPrunerTest` proves the retention rules on H2, including
`unpublishedRowsAreNeverPruned`. `OutboxPrunePostgresTest` runs the native
statement against PostgreSQL 17 in CI, with `pruneStatementRunsOnPostgres` and
`concurrentPrunersDoNotBlockEachOther`. The pruner landed in `a6efc1c`, and the
two-pruner test came in `50e8871`.

## Metrics under the wrong names

I found three naming bugs while writing the meters, and all of them would have
shipped green.

The booking meter was first called `bookings.created`. It exported as
`bookings_total`, because `_created` is a reserved suffix in OpenMetrics and the
Prometheus client strips it before appending `_total`. There was no warning and
no error, just a meter under a name no dashboard would query.
`BookingMetricsTest.exportedNamesSurviveTheTripThroughPrometheus` scrapes a
real `PrometheusMeterRegistry`. A `SimpleMeterRegistry` stores the name as given
and would have passed for any name at all.

Every counter is now registered in the constructor, on startup. A series that
does not exist yet returns no data, and most alerting rules treat no data as
neither firing nor resolved. The alert written to catch the first lock timeout
would have stayed silent for the first lock timeout.
`BookingMetricsTest.metersAreRegisteredEagerly` pins it.

Two counters shared a meter name with different descriptions. The exporter
prints one `# HELP` line per name, so whichever registered last described both
series. The metric was right and its documentation was wrong, and that is the
harder one to notice. There is now one description per meter name, held in a
constant, and the Prometheus test asserts a single `# HELP bookings_booked_total`
line. The fixes are in `d18f58b`, the commit that added the meters.

## A teardown that could pass on an error

I found these by reading `deploy/aws/down.sh` the way an operator would: from
the top, with an expired token, on the second run, after something failed.
There is no AWS account to run it against.

The script ends with a sweep of checks, and a check treats empty
output as PASS. A failed AWS call also prints nothing on stdout, and every check
threw stderr away. An expired token, a throttle, a missing permission or a
mistyped query made every check print PASS. The script exited 0 while a
cluster, a NAT gateway and an RDS instance could still be billing $7.72 a day.
Every query now goes through a wrapper that turns a non-zero exit into loud
output. The same check reports that as FAIL and names the failure.

The same read found more:

- It deleted the account-wide `aws-sam-cli-managed-default` bucket. That bucket
  holds every SAM project's artefacts in the region, so deleting it is now
  opt-in behind `--delete-sam-bucket`.

- It treated an unreachable cluster as "no ingress". That skipped the Ingress
  deletion, the step its own header calls the expensive mistake.

- Deleting the foundation stack also deleted the GitHub OIDC provider. There is
  one per account, shared by every repository that authenticates Actions to
  AWS, so it now has `DeletionPolicy: Retain`.

Nothing automated tests these scripts, because a real test needs an AWS
account. CI runs `shellcheck` and `bash -n` over them. The fixes are in
`b576b6f`. `down.sh` now finishes with fourteen checks, and
[DEPLOYMENT.md](DEPLOYMENT.md) lists them.

## The Boot 4 upgrade

Spring Boot 3.5 left OSS support on 30 June 2026, and 3.5.16 was its last
patch. Dependabot opened the 3.5.16 → 4.1.1 bump and the build failed. This is
what it took, in the order the compiler found it:

- `spring-boot-starter-web` became `spring-boot-starter-webmvc`. The old
  artifact still resolves, but Boot's own POM describes it as deprecated. It is
  the same Tomcat and the same MVC. The rename is part of splitting every
  technology into its own module.

- The test starters split. `@WebMvcTest` moved to
  `spring-boot-starter-webmvc-test`, and `@DataJpaTest` with `TestEntityManager`
  moved to `spring-boot-starter-data-jpa-test`. Without both, the slice tests do
  not compile, because the annotations are gone from the old jars.

- `flyway-core` became `spring-boot-starter-flyway`. The auto-configuration
  moved out of the core Boot jar into a per-technology starter. A bare
  `flyway-core` leaves `FlywayAutoConfiguration` absent, and the migrations
  never run, with no error.

- Jackson 2 became Jackson 3. The package root is `tools.jackson` instead of
  `com.fasterxml.jackson`. `ObjectMapper.writeValueAsString` now throws the
  unchecked `JacksonException` where it used to throw the checked
  `JsonProcessingException`. Every `catch` on the old type stops compiling,
  which is the good outcome. The bad one would have been a handler that can
  never run.

- Actuator packages moved. `EndpointRequest` is now in
  `org.springframework.boot.security.autoconfigure.actuate.web.servlet`, and
  `HealthEndpoint` is in a new `spring-boot-health` module. `SecurityConfig`
  needs both to match actuator endpoints by type instead of by literal path.

- Testcontainers 2.x renamed every module. `org.testcontainers:postgresql`
  became `testcontainers-postgresql`, and the old coordinates are no longer
  published.

- `@MockBean` and `@SpyBean` are gone: deprecated since Boot 3.4 and removed in
  4.0. `@MockitoBean` and `@MockitoSpyBean` replace them.

- JUnit 6.0.3 arrives with the BOM, so `lambda/pom.xml` moved to match. Two
  JUnit majors in one repository is a trap when switching between the modules.

The JDK was never the blocker, because Boot 4 needs Java 17 or later. The
upgrade is `7b45b5b`, and [adr/0007](adr/0007-spring-boot-4.md) records the
decision.

## First review pass

I read through my own code looking for rules that existed in one place and were
trusted everywhere. Every fix in this pass landed in `eac8cc4`. Four of them
are told above:
[the sort parameter](#paging-that-could-skip-a-row),
[the reused key](#a-replay-must-be-the-same-request),
[the SQS timeout](#the-outbox-and-a-slow-queue) and
[the transition graph](#seats-sold-on-a-cancelled-flight).

### Readiness ignored the database

`/actuator/health` went 503 while `/actuator/health/readiness` stayed 200, and
every API call returned 500. A pod with a dead database reported itself ready.
Readiness now includes the database (`readiness.include: readinessState,db`).
Liveness stays on `livenessState` alone, because a database outage must not
restart every pod. No test pins this. The setting is in
`src/main/resources/application.yml`.

### No lock timeout

`SELECT … FOR UPDATE` had no lock timeout. A stuck holder blocked every other
booker until the JDBC socket gave up. A Hikari `connection-init-sql` now runs
`SET lock_timeout = '3s'`, which surfaces as `503 LOCK_TIMEOUT` with
`Retry-After`. It is a connection-level setting. Every lock the service takes,
native SQL included, gets the same bound, and no query can forget a hint.
`LockTimeoutTest.contendedFlightRowGives503` pins it.

### A sort key that sorted wrong

The DynamoDB sort key used `Instant.toString()`, which prints 0, 3, 6 or 9
fractional digits. DynamoDB sorts range keys as bytes, so `…:01Z` sorted after
`…:01.000001Z` (`Z` is 0x5A and `.` is 0x2E). Both sides now format with
`uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`. The Lambda's
`BookingEventContractTest.theContractProducesAUsableSortKey` asserts that the
`#` separator lands at index 27.

### Same origin and destination

`POST /api/v1/flights` accepted an `origin` equal to its `destination`.
`@DistinctEndpoints` is now a class-level Bean Validation constraint that
normalises before comparing, so "ewr" and "EWR" are the same airport. It
reports through the same `fieldErrors` shape as every other validation error.
`ErrorContractTest.sameOriginAndDestinationIsRejectedAtTheEdge` pins it.

### Passenger names in the log

The passenger name was logged at INFO on every booking. That made the
application log an unaudited copy of the passenger list. Bookings are now
logged by id and flight number, and no personal data reaches the log line. No
test pins this.

### A batching setting that did nothing

`hibernate.jdbc.batch_size: 50` sat beside `GenerationType.IDENTITY`, which
disables insert batching. The setting read as a tuning decision and did
nothing. I removed it, and the YAML says why. Real batching would mean
`SEQUENCE` with a pooled optimiser, a different trade-off this workload does not
need.

### No way to cancel a booking

`Flight.releaseSeats` had no production caller, and there was no cancel-booking
endpoint, so seats could only leave inventory. `DELETE /api/v1/bookings/{id}`
now cancels a booking and returns its seats (`V4__booking_cancellation.sql`).
Cancellation is a timestamp on the row. The booking keeps its idempotency key,
and a replay of the original request returns the cancelled booking. Cancelling
twice is a 200 no-op, because a client retrying a cancel that already succeeded
should not be told it failed. `ErrorContractTest.cancellingTwiceIsNotAConflict`,
`cancellationReturnsSeatsExactlyOnce` and `replayAfterCancellationDoesNotRebook`
pin it.

### Time from the wall clock

Time came from `Instant.now()` inside the domain, so no test could drive a
clock-dependent path. An injected `Clock` (`config/TimeConfig.java`) is now the
source everywhere except the `Booking.createdAt` field initialiser. An entity is
constructed with `new`, so that field cannot be injected. ArchUnit's
`the_wall_clock_is_read_only_by_entities` rule is scoped to allow it.

## Second review pass

I ran the second pass against the first, looking for failures that stay green
everywhere a developer looks. Three of these would take the service down in
production while every probe, every test and every local run passed. All of
them landed in `c47729d`.

### A missing API_PASSWORD started the service

`@ConfigurationProperties` binding resolves placeholders with
`ignoreUnresolvablePlaceholders=true`. `@Value` fails instead. An unset variable
therefore bound as the literal 15-character string `${API_PASSWORD}`. The
context started, both probes passed and the pod went Ready. Then every
authenticated request returned a bodyless 500.

`DelegatingPasswordEncoder` finds `{` at index 1 instead of 0 and throws
`IllegalArgumentException`. That is not an `AuthenticationException`, so no
filter catches it. It is thrown before `DispatcherServlet`, so
`@RestControllerAdvice` never sees it either.

`ApiSecurityProperties` now requires an `{id}` algorithm prefix
(`config/ApiSecurityProperties.java`). I first wrote that as `@Validated` with a
`@Pattern`. Since `5cb8fa4` the check sits in the record's compact
constructor, so Boot's bind report no longer prints the rejected value. The
context fails to start, with a message naming the variable. A pod that looks
healthy and answers nothing is worse than one that refuses to boot.
`ApiSecurityPropertiesValidationTest.anUnresolvedPlaceholderIsRejected` pins
it.

### @Lob would have crash-looped every replica

`@Lob` on a `String` resolves to `SqlTypes.CLOB`, and PostgreSQL's dialect maps
CLOB to `oid`, a pointer into `pg_largeobject`. `V5__outbox.sql` creates the
column as `TEXT`. So `ddl-auto: validate` compared `text (Types#VARCHAR)` with
`oid (Types#CLOB)`, refused, and the context failed to refresh. It was
invisible on a laptop, because H2 runs `create-drop` and generates the column
itself.

The payload is now `@JdbcTypeCode(SqlTypes.LONG32VARCHAR)`
(`entity/OutboxEvent.java`), the explicit spelling of what I meant. PostgreSQL
renders it as `text` and H2 keeps its `clob`. Nothing goes through `setClob`,
which would have orphaned a server-side large object on every `markPublished`.
`BookingIntegrationTest.migrationRanAndSchemaValidates` pins it on PostgreSQL
in CI.

### HEAD was a 403 for a reader

`requestMatchers(HttpMethod.GET, …)` matches the literal verb, but Spring MVC
serves HEAD for every `@GetMapping`. HEAD fell past the read rule into
`anyRequest().denyAll()`. There is now an explicit HEAD rule beside the GET one
(`config/SecurityConfig.java`). `SecurityRulesTest.headIsTreatedAsARead`
asserts that HEAD is a read for the reader and still forbidden for the ops
principal.

### A bad bearer token got a bodyless 401

`OAuth2ResourceServerConfigurer` installs its own
`BearerTokenAuthenticationEntryPoint` directly on
`BearerTokenAuthenticationFilter`, which handles the exception itself.
`ExceptionTranslationFilter` never runs, so the entry point configured under
`exceptionHandling` was dead code on that path. I wired the JSON entry point
and access-denied handler into the configurer as well. The error contract now
holds for bearer tokens as it does for Basic. No test covers this path yet.

### An expanded year poisoned the sort key

`Instant.parse` accepts `+12026-09-15T10:00:00Z`, and the `uuuu` pattern emits
the sign. The key became 29 characters starting with `+` (0x2B, below every
ASCII digit). It sorted ahead of the whole partition and was invisible to
`begins_with(eventTime, "2026-")`. `sortKey` now range-checks the year and
throws outside the range. The message is reported as a batch item failure, so
it is retried and lands in the DLQ, where someone can inspect it. The Lambda's
`BookingEventContractTest.anExpandedYearIsRejected` pins it.

### The contract test checked the shape and not the values

Changing the formatter's zone from UTC to the host's keeps 27 characters, six
fractional digits, a trailing `Z` and monotonic ordering. The `Z` is a quoted
literal in the pattern, so it survives a zone change. Every assertion stayed
green while every event on the queue would have shifted by the host offset.

The serialised event is now compared with `contracts/booking-created-v1.json`
as a whole document, in
`BookingEventContractTest.theWireFormatMatchesTheContractExactly`.
`theWireTimestampIsUtcWhateverTheHostZone` names the zone in its failure
message. CI runs in UTC and a laptop does not, which is the arrangement in which
a zone bug ships green.

### The example Secret lacked the passwords

`k8s/secret.example.yaml` did not carry the two API passwords. Anyone following
the example deployed a pod with neither set, which is the first bug in this
section. Both keys are in the example now, with the
`htpasswd -bnBC 10 "" 'pw' | tr -d ':\n'` recipe and a note that leaving them
out fails startup. No test covers the example file.

## Third review pass

These were the gaps the README listed as open. I had written them down as known
and unfixed, which is the easiest kind of debt to leave alone.

### Nothing tied a log line to a request

A 500 in a three-replica deployment meant grepping by timestamp and hoping. It
was the largest operability gap, and it had been open the longest.
`RequestIdFilter` now puts a request id in the MDC ahead of Spring Security. It
echoes the id on every response the application handles, 401 and 403 included.
`traceId` and `spanId` sit beside it, and the `prod` profile emits ECS JSON so
all four are queryable fields. An inbound id must match
`^[A-Za-z0-9._:-]{1,128}$`, and anything else is replaced.
`RequestIdFilterTest.refusesToEchoSomethingDangerous` and
`SecurityRulesTest.everyResponseCarriesARequestId` pin it. It landed in
`d18f58b`.

### Published outbox rows were kept forever

This is the [retention](#retention) story above. The pruner landed in
`a6efc1c`.

### A poisoned event was retried first, forever

This is the [poison row](#a-poison-row) story above. The ceiling landed in
`a6efc1c`.

## Fourth review pass

This pass was a full audit of every tracked file. I reproduced each confirmed
finding, or pinned it with a test that fails without the fix, before changing
the code. The deploy path is the exception, and its entry says why.

### A fractional seat count booked fewer seats

A booking request with `"seats": 2.7` was accepted as a booking for two seats.
Jackson's default coerces a float into an `int` field by truncating it, so
Bean Validation only saw the 2 and `@Min(1)` passed.
`spring.jackson.deserialization.accept-float-as-int: false` in
`src/main/resources/application.yml` turns the coercion off, for `totalSeats`
on a new flight as well. A fractional number is now `400 MALFORMED_REQUEST`,
and the service is never called.
`controller/BookingControllerTest.java#seatsMustBeAWholeNumber` sends 2.5, a
null and a missing field and expects that 400 for each. The fix is `f67d245`,
and the test followed in `ccad5b4`.

### The whole-number rule had other ways round it

`accept-float-as-int` reaches the JSON reader only. springdoc brings in
swagger-core, which brings `jackson-dataformat-yaml`, and Spring MVC then
registers a YAML reader that no `spring.jackson` setting configures. A booking
sent as `application/yaml` with `seats: 2.5` still booked two seats. The three
writes now declare `consumes = application/json`, so a YAML body gets
`415 UNSUPPORTED_MEDIA_TYPE`. The fix is `dff5ef9`, pinned by
`controller/BookingControllerTest.java#yamlBodyReturns415` and
`controller/FlightControllerTest.java#yamlBodyReturns415`.

The JSON reader had gaps of its own. `"seats": "2"` was read from text.
`"status": 4` was read as the constant at index 4, which is `CANCELLED`. A
`departureTime` of `1798797600000`, meant as milliseconds, was read as epoch
seconds: a date in the year 58971, which passed `@Future`.
`spring.jackson.mapper.allow-coercion-of-scalars: false` closes the first
(`bdb6781`) and `spring.jackson.datatype.enum.fail-on-numbers-for-enums: true`
the second (`f1ae45e`). For the third, `validation/IsoInstantDeserializer.java`
accepts a departure time only as an ISO-8601 string. An offset such as
`+05:30` still works and is stored in UTC (`f1ae45e`). Each case is
`400 MALFORMED_REQUEST`. The tests are
`controller/BookingControllerTest.java#seatsMustBeAWholeNumber`,
`controller/FlightControllerTest.java#statusAsANumberReturns400`,
`controller/FlightControllerTest.java#departureTimeMustBeAnIsoString` and
`controller/FlightControllerTest.java#departureTimeWithAnOffsetIsRead`, and
each failed before its fix.

### A flight number that broke its own Location header

Creating a flight built `Location` by concatenation,
`URI.create("/api/v1/flights/" + flight.flightNumber())`. A probe showed what
that did with a flight number: `UA/12` became two path segments, `UA?9` became
the path `/api/v1/flights/UA` with the query `9`, and `UA%41` decoded to `UAA`.
`UA 12` made `URI.create` throw after the flight was saved, so the client got
a 400 for a flight that now existed. Flight numbers are now letters and digits
(`CreateFlightRequest.FLIGHT_NUMBER`, which `BookingRequest` shares), and both
controllers build `Location` with `UriComponentsBuilder`.
`controller/FlightControllerTest.java#flightNumberMustBeLettersAndDigits`
refuses each of those numbers, and one with a newline in it, before anything
is created. The fix is `ccad5b4`.

### A page past the last row was a 500

`GET /api/v1/flights?page=2147483647&size=100` failed with a 500. Spring Data
computes the row offset as an `int`, and `page * size` overflowed it.
`SortPolicy.stable` now refuses a page whose offset would pass
`Integer.MAX_VALUE` with `400 MALFORMED_REQUEST` and the message
`page * size must not exceed 2147483647.`
`ErrorContractTest.java#pagePastTheLastAddressableRowIsABadRequest` checks both
lists. It also checks that page 107374182 at the default size of 20, the last
page that fits, is still a 200. The fix is `ccad5b4`.

### A password the encoder could not verify

A stored password with an unknown algorithm id, such as `{foo}bar` or a
misspelt `{bcrpyt}`, passed the prefix check. The service started, and every
login then got `500 INTERNAL_ERROR`. `DelegatingPasswordEncoder` reports
`There is no password encoder mapped for the id` only when asked to match. An
`{argon2}` hash also failed at the first login, because this build leaves out
BouncyCastle. `SecurityConfig` now calls `encoder.matches` once per account at
startup and turns any failure into an `IllegalStateException` naming the
property. `PasswordVerifiabilityTest.java#anUnknownAlgorithmIdStopsStartup`,
`PasswordVerifiabilityTest.java#argon2WithoutBouncyCastleStopsStartup` and
`PasswordVerifiabilityTest.java#aPbkdf2HashStartsTheContext` cover both
failures and a hash the old prefix pattern refused. The fix is `5cb8fa4`.

### A typo in the publisher setting gave the wrong error

`app.events.publisher=noop` stopped startup with a
`NoSuchBeanDefinitionException` for `EventPublisher`, which does not name the
property. `EventProperties` checks the value in its compact constructor, but no
bean depended on it, so the context reached the missing publisher first.
`OutboxPublisher` now takes `EventProperties` as its first constructor
argument, and the failure reads
`app.events.publisher must be one of [log, sqs], not "noop"`.
`EventPropertiesTest.java#applicationStartupNamesTheProperty` starts the whole
application with that value, and it failed before the fix. The fix is
`db00444`.

### The Lambda stored seat counts the service never sends

The Lambda accepted `"2"`, `2.9` and `0` for `seats`. Jackson's defaults
coerce a string or a float into an `int`, and nothing in Jackson rejects 0.
The handler's mapper now refuses both coercions, and the `BookingEvent` record
rejects a count below 1, which is the service's own `@Min(1)`. The same commit
cleans body values before they reach the log, because a `bookingId` carrying
`\r\n` could write a fake `Processed booking` line.
`lambda/BookingEventHandlerTest.java#aSeatsValueThatIsNotAPositiveWholeNumberIsNotWritten`
and `lambda/BookingEventHandlerTest.java#bodyValuesAreLoggedOnOneBoundedLine`
pin both. The fix is `056eb61`.

### The deploy path could not build the Lambda

I found this by reading, because the deployment has never run against real
AWS. `template.yaml` had `CodeUri: ./lambda` and `deploy/aws/up.sh` ran
`sam build`, which builds in a scratch copy where the Lambda tests cannot find
`../events` and `../contracts`. Maven now builds the jar, `CodeUri` names it,
and step 3 runs `sam deploy --template-file template.yaml`. Step 10 had a
second bug: `kubectl wait` ran before CI had created the deployment and exited
at once with NotFound, so `deploy/aws/lib.sh#wait_for_deployment` now waits for
it to appear first. `deploy/aws/selftest.sh` tests the scripts against stubbed
tools in CI, and a `build` job step checks that `CodeUri` names a built jar
holding the handler. The fix is `86b9e41`.
