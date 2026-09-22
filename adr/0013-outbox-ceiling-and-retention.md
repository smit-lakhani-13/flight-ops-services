# 13. The outbox is bounded: an attempt ceiling and a retention window

Status: accepted (recorded 2026-09-22, decision taken in commit `a6efc1c`)

## Context

[ADR 0001](0001-transactional-outbox.md) left two unbounded quantities, and
both are the kind that only hurt in production:

* **Attempts.** The claim query is `ORDER BY id`, so an event the transport
  structurally rejects — a payload it will refuse identically on attempt
  10,000 — is retried *first* on every tick, consuming the batch while live
  events queue behind it. One malformed row is a total publishing outage that
  no amount of waiting resolves.
* **Rows.** Published rows were never deleted. The table grows forever; the
  partial index on unpublished rows keeps the *poller* fast, so the growth is
  invisible until a backup, a `VACUUM` or a disk alert makes it visible.

## Decision

Bound both, in `src/main/java/com/smit/flightops/config/OutboxProperties.java`:
`max-attempts` (10), `retry-backoff` (2s), `max-retry-backoff` (5m),
`retention` (7 days), `prune-interval` (1 hour) and `prune-batch-size` (1000),
each validated at binding time.

The claim query carries `AND attempts < :maxAttempts`. A row that exhausts them
drops out of the claim, `outbox.dead` goes above zero, and the log names the
event id and the booking. Bringing it back is deliberate and manual:
`UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?`.

**A ceiling without a backoff is a ceiling measured in seconds**, and that was
the first version of this decision. The poller runs every second, so a
transport error that fails fast — a wrong queue URL, an expired credential, a
DNS failure — burned all ten attempts on every row in about ten seconds and
dead-lettered the whole backlog before any alert could fire. Worse, the signal
pointed the wrong way: rows that have gone dead leave `outbox_pending`, so the
gauge an operator watches *fell* to zero while the service was losing every
event.

So a failed row also gets a `next_attempt_at`
(`src/main/resources/db/migration/V7__outbox_next_attempt_at.sql`), set to
`retry-backoff` doubled once per attempt and capped at `max-retry-backoff`, and
the claim query skips a row whose time has not come. Ten attempts now span
about thirteen minutes. The doubling is written as a bounded loop rather than a
shift, because a shift is silently wrong at attempt 64 and the loop stops at
the cap.

Retention is enforced by
`src/main/java/com/smit/flightops/service/OutboxPruner.java`, a batched delete
with a per-run ceiling of `MAX_BATCHES_PER_RUN` batches, each in its own
transaction.

## Consequences

* **A dead row is visible, not silent.** The whole point of dropping it out of
  the claim is that it stops blocking live traffic; the gauge and the WARN are
  what stop that from also meaning "nobody finds out".
* Redrive is manual by design. An automatic reset would mean a permanently
  broken event cycles back into the head of the queue forever, which is the
  behaviour the ceiling was added to remove.
* The pruner's delete is `ORDER BY id ... LIMIT n FOR UPDATE SKIP LOCKED`, so
  it walks the primary key and needs **no index on `published_at`** — an index
  there would be a write on the booking transaction's hot path, paid on every
  booking to make a maintenance job cheaper.
* The per-run ceiling bounds one run rather than the backlog: a large backlog
  is cleared over several runs instead of in one long-running transaction that
  holds locks and bloats WAL.
* Both bounds are configuration, because their right values are deployment
  facts, not code facts. Tests drive them with one-attempt and one-row limits
  precisely so the behaviour at the boundary is proven rather than assumed.

## Alternatives considered

* **A partitioned table, dropping yesterday's partition.** `DROP PARTITION` is
  O(1) and a delete is not, which matters from roughly the first hundred
  million rows. Below that it buys a partitioning scheme, a job to create
  partitions ahead of time, and an outage when that job is what fails.
* **A dead-letter table.** A second place for the same row, and a second thing
  to remember to look at. The flag is already on the row and the gauge already
  counts it.
* **Unlimited retries with exponential backoff.** Backoff spreads the damage
  out; it does not stop a permanently poisoned row from being claimed first
  forever. The decision above takes the backoff and keeps the ceiling: the
  backoff makes the ceiling reachable on a human timescale, and the ceiling is
  what stops the poisoned row. Either alone is the wrong half.
