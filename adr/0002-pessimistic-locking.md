# 2. Pessimistic row locks for seat inventory, with a bounded wait

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`,
bounded by `SET LOCAL lock_timeout` in `eac8cc4`)

## Context

`available_seats` is a counter that concurrent requests decrement. The last few
seats on a popular flight are exactly where every request arrives at once, so
the concurrency strategy is chosen for the worst case, not the average one.

JPA offers two: optimistic (`@Version`, detect the collision at flush and
retry) and pessimistic (`SELECT ... FOR UPDATE`, serialise at read).

## Decision

Pessimistic. `FlightRepository#findByFlightNumberForUpdate` takes
`LockModeType.PESSIMISTIC_WRITE` on the flight row, and every write path —
booking and cancellation alike — takes that lock **first** and the booking row
second.

The wait is bounded per transaction with `SET LOCAL lock_timeout`, applied in
`src/main/java/com/smit/flightops/service/BookingWriter.java#insertNewBooking`.
A request that cannot acquire the lock inside that window fails with
`503 LOCK_TIMEOUT`.

## Consequences

* Contention on one flight is serialised rather than retried. Throughput on a
  single hot flight is bounded by the lock hold time, which is a small
  transaction: read, check, decrement, insert, insert.
* **A bounded wait is what keeps this from becoming an outage.** Without
  `lock_timeout` a stuck transaction holds every subsequent request until the
  connection pool is empty, and the failure surfaces as the whole service
  timing out rather than as one endpoint returning 503. `LockTimeoutTest`
  pins both the timeout and the status code.
* **One lock order everywhere.** Two paths that disagree deadlock under load
  and nowhere else. That is why `cancelBooking` takes the flight row first
  even though it is the booking row it is modifying.
* `503` with `Retry-After` semantics is an honest answer: the request did not
  fail, it did not get a turn.
* The `version` column stays on `flights` even though the write path does not
  rely on it. It costs one integer and catches any future path that updates a
  flight without taking the row lock.

## Alternatives considered

* **Optimistic locking.** The better default for low contention, and the worst
  fit here: every collision becomes a retry, and retries peak exactly when the
  system is busiest. It would also push retry logic into the controller.
* **`UPDATE flights SET available_seats = available_seats - :n WHERE ... AND
  available_seats >= :n`.** Atomic, fast, and it bypasses the domain model
  entirely — the seat rule would then live in a SQL string rather than in
  `src/main/java/com/smit/flightops/entity/Flight.java#reserveSeats`, and the
  booking insert still has to happen in the same transaction anyway.
* **A queue in front of the seat counter.** Correct at airline scale. Enormous
  overkill here, and it makes booking asynchronous, which changes the API.
