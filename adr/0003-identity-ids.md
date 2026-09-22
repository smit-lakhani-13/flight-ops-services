# 3. Database-assigned IDENTITY ids

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`)

## Context

I had to choose entity identifiers before anything else could be written, and
the choice is hard to reverse: the id is in every foreign key, every URL and
every stored event payload.

## Decision

`GenerationType.IDENTITY` over PostgreSQL `BIGSERIAL`. The database assigns the
id, and the application never invents one.

## Consequences

* **No insert batching.** Hibernate cannot batch inserts for IDENTITY entities,
  because it must round-trip to learn each id. This service inserts one booking
  and one outbox row per request, so the cost is nil. It is still the reason to
  revisit the decision if a bulk-import path is ever added.

* Ids are small, sequential and readable. `Location` headers, support
  conversations and log lines stay legible.

* Sequential ids leak volume: a competitor can count bookings by making two. I
  accepted that. This is an internal service behind authentication, and the
  alternative costs more than the leak is worth here.

* `ORDER BY id` on the outbox is insertion order. The poller's claim query and
  the pruner's key walk rely on that to stay correct and cheap.

## Alternatives considered

* **`GenerationType.SEQUENCE` with a pooled allocator.** It restores insert
  batching and is the right choice for bulk writes. It also allocates an id
  before the transaction commits, so gaps appear on rollback. And it buys
  nothing for a path that does one insert per request.

* **UUIDv4 primary keys.** No enumeration leak, no round trip, generated
  anywhere. They are 16 bytes in every index, and random ordering destroys
  B-tree locality on insert, measurably so at scale. UUIDv7 fixes the ordering
  but not the width.

* **Application-generated snowflake ids.** A component to run and a clock to
  trust, for a property this service does not need.
