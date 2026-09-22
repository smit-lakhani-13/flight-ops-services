# 3. Database-assigned IDENTITY ids

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`)

## Context

Entity identifiers had to be chosen before anything else could be written, and
the choice is hard to reverse: it is in every foreign key, every URL and every
stored event payload.

## Decision

`GenerationType.IDENTITY` over PostgreSQL `BIGSERIAL`. The database assigns the
id; the application never invents one.

## Consequences

* **Hibernate cannot batch inserts for IDENTITY entities**, because it must
  round-trip to learn each id. This service inserts one booking and one outbox
  row per request, so the cost is nil — but it is the reason the decision would
  be revisited if a bulk-import path were ever added.
* Ids are small, sequential and readable, which makes `Location` headers,
  support conversations and log lines legible.
* Sequential ids leak volume: a competitor can count bookings by making two.
  Accepted deliberately — this is an internal service behind authentication,
  and the alternative costs more than the leak is worth here.
* `ORDER BY id` on the outbox is insertion order, which is what makes the
  poller's claim query and the pruner's key walk correct and cheap.

## Alternatives considered

* **`GenerationType.SEQUENCE` with a pooled allocator.** Restores insert
  batching and is the right choice for bulk writes. It also means an id is
  allocated before the transaction commits, so gaps appear on rollback, and it
  buys nothing for a one-insert-per-request path.
* **UUIDv4 primary keys.** No enumeration leak, no round trip, generated
  anywhere. They are 16 bytes in every index, and random ordering destroys
  B-tree locality on insert — measurably, at scale. UUIDv7 fixes the ordering
  but not the width.
* **Application-generated snowflake ids.** A component to run and a clock to
  trust, for a property this service does not need.
