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

* `ORDER BY id` on the outbox is roughly insertion order. An id is drawn at
  insert, not at commit, so two transactions can commit out of id order. The
  poller's claim and the pruner's walk use it to stay cheap and fair, not for
  correctness: each event is projected on its own.

## Alternatives considered

* **`GenerationType.SEQUENCE` with a pooled allocator.** It restores insert
  batching and is the right choice for bulk writes. It buys nothing for a path
  that does one insert per request. Rollback gaps are not the difference:
  `BIGSERIAL` draws from a sequence too, so a rolled-back insert leaves a gap
  either way. A pooled allocator only makes gaps larger, because a restart
  discards the rest of each reserved block.

* **UUIDv4 primary keys.** No enumeration leak, no round trip, generated
  anywhere. They are 16 bytes in every index, and random ordering destroys
  B-tree locality on insert, measurably so at scale. UUIDv7 fixes the ordering
  but not the width.

* **Application-generated snowflake ids.** A component to run and a clock to
  trust, for a property this service does not need.

**Correction (2026-09-23).** The SEQUENCE entry used to count gaps on rollback
against it. IDENTITY over `BIGSERIAL` draws from a sequence as well, and
PostgreSQL never rolls a sequence back, so both leave gaps. The last
consequence used to say the outbox claim and the pruner rely on `ORDER BY id`
being insertion order to stay correct. It is only roughly insertion order, and
neither query needs it for correctness.
