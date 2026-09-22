package com.smit.flightops.service;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.observability.OutboxMetrics;
import com.smit.flightops.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Deletes published outbox rows once they are older than the retention window.
 *
 * <p>Without this the table grows forever. Nothing breaks for months, which is
 * precisely the problem: the partial index stays small because it only covers
 * unpublished rows, so the poller keeps performing perfectly while the heap
 * underneath it reaches a few hundred gigabytes, and the first symptom is a
 * backup window, an autovacuum that never finishes, or a disk alert on a
 * Sunday. The fix is fifteen lines and it is much harder to retrofit once the
 * table is large, because the first delete is then the one that hurts.
 *
 * <h2>Why it is batched</h2>
 * {@code DELETE FROM outbox_events WHERE published_at < :cutoff} is shorter and
 * is the version that causes an incident. On a table with a real backlog it
 * takes one enormous transaction: row locks across the whole range, a WAL
 * burst big enough to stall replication, and the poller shut out of the table
 * until it commits. Each statement here deletes at most
 * {@code app.outbox.prune-batch-size} rows, so the cost of one statement is
 * fixed no matter how far behind the pruner is — and the worst case, the very
 * first run after this ships, is handled by exactly the same code path as the
 * steady state.
 *
 * <h2>Why there is a loop ceiling</h2>
 * {@link #MAX_BATCHES_PER_RUN} batches, then stop until the next tick. Without
 * it, a run that starts with millions of eligible rows holds a database
 * connection and a scheduler thread for as long as it takes to delete all of
 * them — turning a maintenance job into an outage of its own. Stopping early is
 * free: the next run picks up where this one left off, and the log line says
 * how far behind it is.
 *
 * <h2>Why each batch gets its own transaction</h2>
 * A {@code @Transactional} on the method would wrap all fifty batches into the
 * single long transaction the batching exists to avoid. {@link
 * TransactionTemplate} commits each one as it completes, so the locks are
 * released fifty times rather than once, and an interruption halfway through
 * keeps the work already done.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPruner {

    private static final Logger log = LoggerFactory.getLogger(OutboxPruner.class);

    /**
     * At the default batch size of 1,000 this is 50,000 rows an hour — around
     * fourteen bookings a second sustained, which is far more than this service
     * produces and still a bounded amount of work per run.
     */
    static final int MAX_BATCHES_PER_RUN = 50;

    private final OutboxEventRepository outboxEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final OutboxMetrics metrics;
    private final Clock clock;

    public OutboxPruner(OutboxEventRepository outboxEventRepository,
                        TransactionTemplate transactionTemplate,
                        OutboxProperties properties,
                        OutboxMetrics metrics,
                        Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * {@code initialDelayString} matches the interval so this does not run
     * during startup, when the JVM is still warming up, Flyway may still be
     * finishing and the pod is trying to pass its first readiness probe. A
     * maintenance job has no reason to compete with any of that.
     */
    @Scheduled(fixedDelayString = "${app.outbox.prune-interval}",
               initialDelayString = "${app.outbox.prune-interval}")
    public void prunePublishedEvents() {
        Instant cutoff = clock.instant().minus(properties.retention());
        int total = 0;

        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            Integer deleted = transactionTemplate.execute(status ->
                    outboxEventRepository.deletePublishedBefore(cutoff, properties.pruneBatchSize()));
            int n = deleted == null ? 0 : deleted;
            total += n;
            // Counted per batch, not per run, and that is the whole difference
            // between a counter and a summary. Each batch is its own committed
            // transaction, so rows deleted by batch three are gone whatever
            // batch four does. Incrementing once at the end meant an exception
            // in any later batch discarded the count of every batch that had
            // already succeeded — and outbox.pruned flat at zero is documented
            // as meaning "the pruner is not running", which would then be the
            // opposite of what happened. Counters are additive, so per-batch
            // and per-run agree on the happy path.
            metrics.pruned(n);

            // Short batch means the eligible rows ran out. Asking again would
            // cost a query to be told the same thing.
            if (n < properties.pruneBatchSize()) {
                if (total > 0) {
                    log.info("Pruned {} outbox event(s) published before {}", total, cutoff);
                }
                return;
            }
        }

        // Reaching the ceiling is not an error, but it is worth saying out
        // loud: it means there is still a backlog, and if this line repeats
        // every hour the retention window or the batch size is wrong.
        log.warn("Pruned {} outbox event(s) published before {} and stopped at the {}-batch ceiling; "
                 + "more remain and the next run will continue",
                 total, cutoff, MAX_BATCHES_PER_RUN);
    }
}
