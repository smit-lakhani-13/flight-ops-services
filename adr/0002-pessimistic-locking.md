# 2. Pessimistic row locks for seat inventory, with a bounded wait

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`,
bounded by a session `lock_timeout` in `eac8cc4`)

## Context

`available_seats` is a counter that concurrent requests decrement. The last few
seats on a popular flight are where every request arrives at once, so I chose
the concurrency strategy for the worst case.

JPA offers two: optimistic (`@Version`, detect the collision at flush and
retry) and pessimistic (`SELECT ... FOR UPDATE`, serialise at read).

## Decision

Pessimistic. `FlightRepository#findByFlightNumberForUpdate` takes
`LockModeType.PESSIMISTIC_WRITE` on the flight row. Every path that changes
seats or bookings (booking and cancellation alike) takes that lock first and
the booking row second.

The wait is bounded with `SET lock_timeout = '3s'`, issued once per connection
as HikariCP's `connection-init-sql` in `src/main/resources/application.yml`. A
request that cannot get the lock inside that window fails with
`503 LOCK_TIMEOUT`.

I set the timeout on the connection instead of per query. A per-query hint
(`jakarta.persistence.lock.timeout`) covers only the queries that carry it. A
native `SELECT ... FOR UPDATE`, or a repository method added next year without
the annotation, would wait forever and nothing would say so. The connection
setting costs one line of configuration, covers every lock the service takes,
and cannot be forgotten on a path added later.

**Correction (2026-09-23).** This section used to give a different reason: that
the PostgreSQL dialect discards a positive `lock.timeout` hint without an
error. For the Hibernate version this project ships (7.4.5) that is not true.
Reading the jar shows `PostgreSQLLockingSupport` applying a positive timeout by
issuing `SET LOCAL lock_timeout` on the connection before the locking query. I
read that from the bytecode, and no test here exercises it. The decision stands
on the reason above. Only the old reason was wrong.

## Consequences

* Contention on one flight is serialised, and nothing retries. Throughput on a
  single hot flight is bounded by the lock hold time. The transaction holding
  the lock is small: read, check, decrement, insert, insert.

* **The wait is bounded.** Without `lock_timeout`, a stuck transaction holds
  every later request until the connection pool is empty, and the whole service
  times out. With it, the failure stays on one endpoint as a 503.
  `LockTimeoutTest` pins both the timeout and the status code.

* **One lock order everywhere.** Two paths that disagree deadlock under load and
  nowhere else. This is why `cancelBooking` takes the flight row first, even
  though the row it is modifying is the booking.

* `503` with `Retry-After` semantics is the right answer. The request did not
  fail. It did not get a turn.

* The `version` column on `flights` does real work, although the booking paths
  do not rely on it. `FlightService#updateStatus` and `FlightService#cancel`
  load the flight without the row lock. If one of them races a booking on the
  same flight, `version` catches the stale write at flush and the caller gets
  `409 CONCURRENT_MODIFICATION`. Without it, the stale UPDATE would put back
  the seats the booking had just debited.

**Correction (2026-09-23).** The last bullet used to say that `version` only
guards some future path that updates a flight without the row lock. Two such
paths already existed, and the bullet now names them. For the same reason, the
Decision section used to say "every write path" takes the lock. It now says
every path that changes seats or bookings.

## Alternatives considered

* **Optimistic locking.** The better default for low contention, and the worst
  fit here. Every collision becomes a retry, and retries peak when the system is
  busiest. It would also push retry logic into the controller.

* **A conditional `UPDATE`.**
  `UPDATE flights SET available_seats = available_seats - :n WHERE ... AND available_seats >= :n`
  is atomic and fast. It also bypasses the domain model. The seat rule would
  then live in a SQL string, away from
  `src/main/java/com/smit/flightops/entity/Flight.java#reserveSeats`, and the
  booking insert still has to happen in the same transaction anyway.

* **Queueing the bookings.** A queue in front of the seat counter is correct at
  airline scale. It is overkill here, and it makes booking asynchronous, which
  changes the API.
