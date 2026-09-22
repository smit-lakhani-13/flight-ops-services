package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the outbox poller and pruner, bound from {@code app.outbox.*}. The
 * compact constructor validates every bound, because one rule relates two values
 * ({@code max-retry-backoff} against {@code retry-backoff}), which a field annotation
 * cannot express.
 *
 * @param enabled         run the poller. On by default; a deployment with one
 *                        dedicated drainer pod sets it false on the other replicas.
 * @param pollInterval    milliseconds between drains, the floor on event latency (1s).
 * @param batchSize       rows claimed per drain; bounds how long one replica holds locks.
 * @param maxAttempts     failures before a row is no longer claimed (10). Stops one bad
 *                        row from heading every batch of the {@code ORDER BY id} claim.
 * @param retention       how long published rows are kept, so "did it publish?" can still
 *                        be answered (7d, at least 1h).
 * @param pruneInterval   how often the pruner runs (1h). Each run deletes about an hour's
 *                        worth of rows (those that crossed the retention line since the last run).
 * @param pruneBatchSize  rows per DELETE (1000); see {@code OutboxPruner}.
 * @param retryBackoff    first retry delay, doubling per attempt (2s; 0 disables, for tests).
 *                        It makes {@code maxAttempts} a bound on time, not on poll ticks.
 * @param maxRetryBackoff cap on the doubling (5m). With the defaults, ten attempts span about
 *                        thirteen and a half minutes (810s of waits), longer than a deployment.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(boolean enabled,
                               long pollInterval,
                               int batchSize,
                               @DefaultValue("10") int maxAttempts,
                               @DefaultValue("7d") Duration retention,
                               @DefaultValue("1h") Duration pruneInterval,
                               @DefaultValue("1000") int pruneBatchSize,
                               @DefaultValue("2s") Duration retryBackoff,
                               @DefaultValue("5m") Duration maxRetryBackoff) {

    /**
     * Below an hour, the hourly pruner can delete a row published moments ago, and
     * "published and pruned" looks the same as "never recorded".
     */
    private static final Duration MINIMUM_RETENTION = Duration.ofHours(1);

    public OutboxProperties {
        if (pollInterval <= 0) {
            throw new IllegalArgumentException("app.outbox.poll-interval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("app.outbox.batch-size must be positive");
        }
        if (maxAttempts <= 0) {
            throw new IllegalArgumentException("app.outbox.max-attempts must be positive");
        }
        if (retention == null || retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw new IllegalArgumentException(
                    "app.outbox.retention must be at least " + MINIMUM_RETENTION
                    + " so a published event stays auditable for longer than it takes to ask about it");
        }
        if (pruneInterval == null || pruneInterval.isZero() || pruneInterval.isNegative()) {
            throw new IllegalArgumentException("app.outbox.prune-interval must be positive");
        }
        if (pruneBatchSize <= 0) {
            throw new IllegalArgumentException("app.outbox.prune-batch-size must be positive");
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException(
                    "app.outbox.retry-backoff must not be negative (zero disables backoff)");
        }
        if (maxRetryBackoff == null || maxRetryBackoff.compareTo(retryBackoff) < 0) {
            throw new IllegalArgumentException(
                    "app.outbox.max-retry-backoff must be at least app.outbox.retry-backoff, "
                    + "or the cap would shorten the first retry instead of bounding the last");
        }
    }
}
