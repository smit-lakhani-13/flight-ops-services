# 16. What porting to Oracle would change

Status: proposed (recorded 2026-09-25; not implemented, not run)

## Context

This service has not been run against Oracle. This note comes from Oracle's
and Hibernate's documentation, and the first step of any port would be to
check it with a Testcontainers Oracle Free run in CI.

Beyond the migrations' spellings, three things in the code are
PostgreSQL-specific: two native queries in the outbox, and the session setting
that bounds a row-lock wait.

## Decision

Nothing is ported now. If the service ever needs Oracle, these are the
changes.

* **Claiming a batch.**
  `src/main/java/com/smit/flightops/repository/OutboxEventRepository.java#claimUnpublished`
  ends in `ORDER BY id LIMIT :batchSize FOR UPDATE SKIP LOCKED`. Oracle has no
  `LIMIT`, and its documentation does not allow the row-limiting clause
  `FETCH FIRST n ROWS ONLY` together with `FOR UPDATE`. The tempting
  `WHERE ROWNUM <= :n … FOR UPDATE SKIP LOCKED` is wrong under contention.
  `ROWNUM` is assigned before the lock is tried, so locked rows use up the
  batch, and a second poller can get nothing while free rows exist. The Oracle
  idiom is a `FOR UPDATE SKIP LOCKED` cursor with no row limit in the SQL,
  fetched N rows and then closed. Here that would be a repository fragment
  chosen by database vendor.

* **Pruning.**
  `src/main/java/com/smit/flightops/repository/OutboxEventRepository.java#deletePublishedBefore`
  puts `FOR UPDATE SKIP LOCKED` inside the `DELETE`'s subquery, and Oracle
  does not allow `FOR UPDATE` in a subquery. The closest form is a bounded
  `DELETE … WHERE published_at < :cutoff AND ROWNUM <= :limit`, which loses
  `SKIP LOCKED`. Two pruners would then block each other for the length of one
  small statement. I would accept that, because the pruner is a background
  job, but it is a trade and not a translation.

* **The lock wait.** The `postgres` and `prod` profiles bound the wait with
  `SET lock_timeout = '3s'` in `connection-init-sql`. Oracle has no session
  setting for row-lock waits; `DDL_LOCK_TIMEOUT` covers DDL only. Hibernate's
  Oracle dialect renders a pessimistic lock with a timeout as
  `FOR UPDATE WAIT n`, so the two locking queries would carry the
  `jakarta.persistence.lock.timeout` hint, 3000 ms. Which way of setting that
  hint Hibernate 7 honours on a Spring Data `@Lock` query is the first thing
  the Oracle run would check. Oracle's ORA-30006 reaches Spring as a
  `PessimisticLockingFailureException`, which
  `src/main/java/com/smit/flightops/exception/GlobalExceptionHandler.java#handleLockTimeout`
  already maps to the 503. A hint bounds only the query that carries it, which
  is why [ADR 0002](0002-pessimistic-locking.md) chose the session setting:
  `src/main/java/com/smit/flightops/service/FlightService.java#updateStatus`
  and `#cancel` load the flight without a lock and write it at flush, so on
  Oracle they would have to use the locking query too, or the 503 documented
  for a status change or a flight cancellation would become a wait with no
  bound.

* **Spellings in the migrations.** An identity column for `BIGSERIAL`;
  `NUMBER(19)` for V1's `BIGINT`; `TIMESTAMP(6) WITH TIME ZONE` for V7's
  `TIMESTAMPTZ`; `ADD (column type)` for `ADD COLUMN`; `DEFAULT 0 NOT NULL`
  for V5's `NOT NULL DEFAULT 0`; `VARCHAR2(n CHAR)` for character lengths;
  `CLOB` for `TEXT`. The partial index `idx_outbox_unpublished` becomes a
  function-based index on `CASE WHEN published_at IS NULL THEN id END`, and
  the claim query has to use the same expression to reach it. V4's partial
  index, which V8 drops again, can be left out of an Oracle set together with
  that drop; V8's `DROP INDEX IF EXISTS` exists only from Oracle Database
  23ai. `ddl-auto: validate` would then show which entity mappings need a
  column type per vendor.

## Consequences

* The port is two rewritten queries, a session-wide lock-wait bound replaced
  by per-query hints and a second set of migrations, not a driver swap.
* Pruning on Oracle would give up `SKIP LOCKED`, so two replicas pruning at
  once would wait for each other.
* Nothing here has run. Every sentence is from documentation until the CI run
  below exists.

## Alternatives considered

* **`db/migration/{vendor}` folders**, with `flyway-database-oracle` and the
  Oracle JDBC driver `com.oracle.database.jdbc:ojdbc17`, whose version Spring
  Boot's dependency management supplies. `V1`–`V8` would move into the
  `postgresql` folder unchanged, and the Oracle folder would get its own
  spellings. This is the shape I would choose.
* **One SQL dialect for both databases.** It would give up `SKIP LOCKED` and
  the partial index on PostgreSQL too, to make Oracle cheaper. The PostgreSQL
  path is the one that has run, and I would not weaken it for one that has
  not.
* **Oracle Advanced Queuing in place of the outbox table and the poller.** An
  enqueue in the same database transaction is atomic without an outbox. But
  Lambda has no event source for it, so the consumer would change too, and the
  messaging would then be tied to the database vendor.

## How it would be checked, if it were ever built

An `OracleContainer` on `gvenzl/oracle-free:slim-faststart` in CI, with
`ddl-auto: validate`. Of the two concurrency tests in
`src/test/java/com/smit/flightops/service/OutboxPrunePostgresTest.java`, the
one with two pollers would carry over with only its container and profile
changed. The one with two pruners asserts that the second pruner does not wait
for the first. Pruning on Oracle gives that up, so the Oracle version would
assert the opposite: the second pruner waits until the first commits. One more
test: transaction A locks rows 1 to 10, and transaction B, with a batch size
of 10, must receive rows 11 to 20.
