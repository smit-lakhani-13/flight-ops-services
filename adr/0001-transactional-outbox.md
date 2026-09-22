# 1. A transactional outbox, not a send after commit

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

A booking has to do two things that must agree: debit seats in PostgreSQL and
tell the rest of the world a booking happened. The database write is
transactional. The queue send is not, and cannot be — SQS has no way to join a
PostgreSQL transaction.

The first version sent the message from the service method, inside the
transaction. That arrangement has two failure modes and they are not
symmetrical:

* **Send inside the transaction, commit afterwards.** The message goes out,
  the commit fails, and a consumer now knows about a booking that does not
  exist. Worse, a slow queue holds the flight row lock open for the duration
  of the send, so an SQS latency spike becomes a booking outage.
* **Commit first, send afterwards.** The commit succeeds, the process is
  killed, and the event is gone with nothing anywhere recording that it was
  owed.

Neither is acceptable for the thing the system exists to do.

## Decision

The event is written to an `outbox_events` row **in the booking's own
transaction** (`src/main/java/com/smit/flightops/service/OutboxWriter.java`),
and a scheduled poller
(`src/main/java/com/smit/flightops/service/OutboxPublisher.java`) drains it
afterwards and sends to SQS.

The writer is annotated `Propagation.MANDATORY`, so it refuses to run outside a
transaction. That is the load-bearing annotation: with the default `REQUIRED`
the method would still work — Spring Data would open its own transaction, the
row would appear, every test would pass — and the atomicity that justifies the
whole pattern would be gone with nothing failing.

The event is built and serialised at booking time, not by the poller, so the
payload describes what happened rather than what the row looks like whenever it
is eventually sent. A booking cancelled two seconds later must still publish a
`BookingCreated` describing the booking.

## Consequences

* Delivery is **at-least-once**. The poller publishes, then marks the row; a
  crash in between republishes. The alternative order loses events instead,
  which is strictly worse. The consumer absorbs duplicates with a conditional
  write — see [ADR 0008](0008-standalone-lambda-consumer.md).
* Events arrive with up to one poll interval of latency. For a booking
  confirmation that is invisible; for anything needing sub-second delivery it
  would not be.
* The backlog is a table, so it can be queried, alerted on and re-driven. That
  is the operational advantage over a direct send, and it is only real if
  something actually queries it — hence the gauges in
  `src/main/java/com/smit/flightops/observability/OutboxMetrics.java`.
* The table grows forever unless something prunes it, and a row the transport
  permanently rejects would be retried forever at the head of the queue. Both
  are addressed in [ADR 0013](0013-outbox-ceiling-and-retention.md).

## Alternatives considered

* **Change data capture (Debezium on the WAL).** Removes the poll entirely and
  is the right answer at a larger size. It adds Kafka Connect, a connector to
  operate, and a replication slot that fills the disk if the consumer stops.
  Not free, and not obviously worth it here.
* **Two-phase commit across PostgreSQL and SQS.** SQS does not support XA, and
  distributed transactions would trade this problem for a worse one.
* **Accepting the lost event.** Defensible for analytics. Not for the record
  that a seat was sold.
