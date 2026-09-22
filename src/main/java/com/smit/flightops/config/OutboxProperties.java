package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Tuning for the outbox poller and pruner, bound from {@code app.outbox.*}.
 *
 * <p>Every bound is validated in the compact constructor rather than with
 * {@code @Positive} and friends. A {@code jakarta.validation} annotation here
 * would work equally well for the simple cases and not at all for the one that
 * matters — {@code retention} being shorter than a plausible outage, which is a
 * relationship between two values rather than a property of one.
 *
 * @param enabled        whether the poller runs at all. On by default. A
 *                       deployment that wanted a dedicated drainer pod would set
 *                       this false on the request-serving replicas — the poller
 *                       is safe to run everywhere, but it does not have to.
 * @param pollInterval   milliseconds between drains. This is the floor on event
 *                       latency and the trade-off is explicit: lower means
 *                       fresher events and more empty queries against the
 *                       database. One second is chosen because the consumer
 *                       projects a read model, and a read model that is a second
 *                       behind is a read model.
 * @param batchSize      rows claimed per drain. Bounds how long one replica can
 *                       hold locks, and therefore how badly a queue outage can
 *                       affect the others. See
 *                       {@code OutboxEventRepository.claimUnpublished}.
 * @param maxAttempts    how many times a row may fail before the poller stops
 *                       claiming it. Ten, because the failures worth retrying
 *                       are transient — a throttle, a DNS blip, a redeployed
 *                       endpoint — and ten seconds of retries clears all of
 *                       them. A payload the transport structurally rejects
 *                       fails identically on attempt 10,000, and because the
 *                       claim is {@code ORDER BY id} it fails <em>first</em>
 *                       every time, spending the batch on a row that cannot
 *                       succeed while live events queue behind it. The ceiling
 *                       is what stops one bad event from becoming an outage.
 * @param retention      how long a published row is kept before the pruner may
 *                       delete it. Seven days, which is the window in which
 *                       somebody asks "did that booking publish?" and can still
 *                       be answered from this table.
 * @param pruneInterval  how often the pruner runs. Hourly: the table is not
 *                       urgent and an hourly delete of a day's worth of rows is
 *                       small enough to be invisible.
 * @param pruneBatchSize rows deleted per statement. See {@code OutboxPruner}
 *                       for why this is batched at all rather than one DELETE.
 * @param retryBackoff   how long the FIRST retry waits, doubling per attempt.
 *                       This is what makes {@code maxAttempts} a bound on time
 *                       rather than on ticks. Without it the two were the same
 *                       thing at the poll rate: ten attempts, one second apart,
 *                       meant a ten-second queue outage permanently abandoned
 *                       every pending event — none of them defective, and the
 *                       {@code outbox.pending} gauge falling to zero as it
 *                       happened. Zero disables backoff, which only the tests
 *                       that drain twice in a row have any use for.
 * @param maxRetryBackoff the cap on the doubling. Two seconds doubling to a
 *                       five-minute ceiling spans about twenty minutes over ten
 *                       attempts, so an outage has to outlast a deployment
 *                       before anything is given up on, and a genuinely poison
 *                       row still drops out after the same ten attempts.
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
     * The shortest retention this will accept. Below an hour, a pruner running
     * hourly can delete a row published moments ago — so an operator asking
     * "did booking 4471 publish?" finds nothing and cannot tell "published and
     * pruned" from "never recorded". The floor is not about disk.
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
