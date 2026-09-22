# 1. A transactional outbox, not a send after commit

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

A booking has to do two things that must agree: debit seats in PostgreSQL and
tell the rest of the world a booking happened. The database write is
transactional. The queue send cannot be, because SQS has no way to join a
PostgreSQL transaction.

The first version sent the message from the service method, inside the
transaction. That arrangement has two failure modes, and they are not
symmetrical:

* **Send first, commit afterwards.** The message goes out inside the
  transaction, the commit fails, and a consumer now knows about a booking that
  does not exist. A slow queue also holds the flight row lock open for the
  length of the send, so an SQS latency spike becomes a booking outage.

* **Commit first, send afterwards.** The commit succeeds, the process is
  killed, and the event is gone. Nothing anywhere records that it was owed.

Neither is acceptable for the thing the system exists to do.

## Decision

I write the event to an `outbox_events` row in the booking's own transaction
(`src/main/java/com/smit/flightops/service/OutboxWriter.java`). A scheduled
poller (`src/main/java/com/smit/flightops/service/OutboxPublisher.java`) drains
the table afterwards and sends to SQS.

The writer is annotated `Propagation.MANDATORY`, so it refuses to run outside a
transaction. This annotation matters most. With the default `REQUIRED` the
method would still work: Spring Data would open its own transaction, the row
would appear, and every test would pass. The atomicity that justifies the whole
pattern would be gone, and nothing would fail.

I build and serialise the event at booking time, in the writer. The payload
then describes what happened at that moment, whatever the row looks like by the
time the poller sends it. A booking cancelled two seconds later must still
publish a `BookingCreated` describing the booking.

## Consequences

* Delivery is at-least-once. The poller publishes and then marks the row, so a
  crash in between republishes. Marking first and publishing second would lose
  events instead, which is worse. The consumer absorbs duplicates with a
  conditional write (see [ADR 0008](0008-standalone-lambda-consumer.md)).

* Events arrive with up to one poll interval of latency. A booking confirmation
  does not notice that. Anything needing sub-second delivery would.

* The backlog is a table, so it can be queried, alerted on and re-driven. This
  is the operational advantage over a direct send, and it only counts if
  something queries the table. The gauges in
  `src/main/java/com/smit/flightops/observability/OutboxMetrics.java` are there
  to do it.

* The table grows forever unless something prunes it. A row the transport
  permanently rejects would also be retried forever at the head of the queue.
  [ADR 0013](0013-outbox-ceiling-and-retention.md) deals with both.

## Alternatives considered

* **Change data capture.** Debezium on the WAL removes the poll entirely, and it
  is the right answer at a larger size. It adds Kafka Connect, a connector to
  operate, and a replication slot that fills the disk if the consumer stops. It
  is not free, and not obviously worth it here.

* **Two-phase commit.** SQS does not support XA. Distributed transactions across
  PostgreSQL and SQS would trade this problem for a worse one.

* **Accepting the lost event.** Defensible for analytics. Not for the record
  that a seat was sold.
