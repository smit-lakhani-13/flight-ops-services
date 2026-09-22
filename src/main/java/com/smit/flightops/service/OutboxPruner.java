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
 * Deletes published outbox rows older than the retention window, so the table
 * does not grow without bound.
 *
 * <p>Batched: each statement deletes at most {@code app.outbox.prune-batch-size}
 * rows, so its cost is fixed however far behind the pruner is. A single
 * unbounded {@code DELETE} would lock the whole range and stall replication.
 * {@link #MAX_BATCHES_PER_RUN} bounds one run; the next run continues. Each batch
 * commits in its own {@link TransactionTemplate} transaction, because a
 * {@code @Transactional} method would rebuild the one long transaction the
 * batching avoids.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPruner {

    private static final Logger log = LoggerFactory.getLogger(OutboxPruner.class);

    /** 50,000 rows an hour at the default batch size, far more than this service produces. */
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

    /** The initial delay matches the interval, so pruning does not compete with startup. */
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
            // Counted per batch: each batch has committed, so an exception in a
            // later one must not lose the count of rows already deleted.
            metrics.pruned(n);

            // A short batch means the eligible rows ran out.
            if (n < properties.pruneBatchSize()) {
                if (total > 0) {
                    log.info("Pruned {} outbox event(s) published before {}", total, cutoff);
                }
                return;
            }
        }

        // Not an error, but a backlog remains. If this repeats every hour, the
        // retention window or the batch size is wrong.
        log.warn("Pruned {} outbox event(s) published before {} and stopped at the {}-batch ceiling; "
                 + "more remain and the next run will continue",
                 total, cutoff, MAX_BATCHES_PER_RUN);
    }
}
