package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for the outbox poller, bound from {@code app.outbox.*}.
 *
 * @param enabled      whether the poller runs at all. On by default. A
 *                     deployment that wanted a dedicated drainer pod would set
 *                     this false on the request-serving replicas — the poller
 *                     is safe to run everywhere, but it does not have to.
 * @param pollInterval milliseconds between drains. This is the floor on event
 *                     latency and the trade-off is explicit: lower means
 *                     fresher events and more empty queries against the
 *                     database. One second is chosen because the consumer
 *                     projects a read model, and a read model that is a second
 *                     behind is a read model.
 * @param batchSize    rows claimed per drain. Bounds how long one replica can
 *                     hold locks, and therefore how badly a queue outage can
 *                     affect the others. See
 *                     {@code OutboxEventRepository.claimUnpublished}.
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(boolean enabled, long pollInterval, int batchSize) {

    public OutboxProperties {
        if (pollInterval <= 0) {
            throw new IllegalArgumentException("app.outbox.poll-interval must be positive");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("app.outbox.batch-size must be positive");
        }
    }
}
