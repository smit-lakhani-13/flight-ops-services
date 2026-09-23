# 13. The outbox is bounded: an attempt ceiling and a retention window

Status: accepted (recorded 2026-09-22, decision taken in commit `a6efc1c`; the
retry backoff was added in `50e8871` and recorded in place on 2026-09-23)

## Context

[ADR 0001](0001-transactional-outbox.md) left two quantities unbounded, both of
the kind that only hurt in production:

* **Attempts.** The claim query is `ORDER BY id`. An event the transport
  structurally rejects (a payload it will refuse identically on attempt 10,000)
  is therefore retried *first* on every tick, forever. Each one holds a slot at
  the head of every batch. A batch's worth of them, 100 at the default size,
  stops publishing completely, and no amount of waiting resolves it.

* **Rows.** Published rows were never deleted, so the table grew forever. The
  partial index on unpublished rows keeps the *poller* fast, which hides the
  growth until a backup, a `VACUUM` or a disk alert shows it.

## Decision

I bound both in `src/main/java/com/smit/flightops/config/OutboxProperties.java`:
`max-attempts` (10), `retry-backoff` (2s), `max-retry-backoff` (5m),
`retention` (7 days), `prune-interval` (1 hour) and `prune-batch-size` (1000).
Each is validated at binding time.

The claim query carries `AND attempts < :maxAttempts`. A row that exhausts its
attempts drops out of the claim, `outbox.dead` goes above zero, and the log
names the event id and the booking. Bringing it back is a manual step:
`UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?`.

The first version of this decision had the ceiling and no backoff, which made
the ceiling a matter of seconds. The poller runs every second. A transport
error that fails fast (a wrong queue URL, an expired credential, a DNS failure)
burned all ten attempts on every row in about ten seconds. It dead-lettered the
whole backlog before any alert could fire. The signal also pointed the wrong
way. Rows that have gone dead leave `outbox_pending`, so the gauge an operator
watches *fell* to zero while the service was losing every event.

So a failed row also gets a `next_attempt_at`
(`src/main/resources/db/migration/V7__outbox_next_attempt_at.sql`). It is set to
`retry-backoff` doubled once per attempt and capped at `max-retry-backoff`, and
the claim query skips a row whose time has not come. With the defaults, ten attempts now span
about thirteen and a half minutes (810 seconds of waits). I wrote the doubling as a bounded loop instead of a
shift. A shift is wrong at attempt 64 without raising any error, and the loop
stops at the cap.

`src/main/java/com/smit/flightops/service/OutboxPruner.java` enforces retention
with a batched delete. Each batch runs in its own transaction, and one run does
at most `MAX_BATCHES_PER_RUN` batches.

## Consequences

* **Dead rows stay visible.** A dead row leaves the claim so that it stops
  blocking live traffic. The gauge and the WARN are how someone still finds out
  about it.

* Redrive is manual by design. An automatic reset would cycle a permanently
  broken event back to the head of the queue forever, and the ceiling was added
  to remove that behaviour.

* The pruner's delete is `ORDER BY id ... LIMIT n FOR UPDATE SKIP LOCKED`, so it
  walks the primary key and needs no index on `published_at`. An index there
  would add a write to the booking transaction's hot path. Every booking would
  pay for it to make a maintenance job cheaper.

* The per-run ceiling bounds a single run. A large backlog clears over several
  runs, and never in one long-running transaction that holds locks and bloats
  WAL.

* Both bounds are configuration, because their right values are facts about
  the deployment. Tests drive them with a two-attempt ceiling
  (`OutboxPoisonRowTest`) and a one-row prune batch (`OutboxPrunerTest`), so
  the behaviour at the boundary is proven by a test.

## Alternatives considered

* **A partitioned table.** Detaching yesterday's partition and dropping its
  table (`ALTER TABLE ... DETACH PARTITION`, then `DROP TABLE`) is O(1) and a
  delete is not. That matters from roughly the first hundred million rows.
  Below that, it buys a partitioning scheme, a job to create partitions ahead
  of time, and an outage when that job is what fails.

* **A dead-letter table.** A second place for the same row, and a second thing
  to remember to look at. The flag is already on the row, and the gauge already
  counts it.

* **Unlimited retries with exponential backoff.** Backoff spreads the damage
  out. It does not stop a permanently poisoned row from being claimed first
  forever. The decision above takes the backoff and keeps the ceiling. The
  backoff makes the ceiling reachable on a human timescale, and the ceiling is
  what stops the poisoned row. Either alone is the wrong half.
