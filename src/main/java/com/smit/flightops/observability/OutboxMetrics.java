package com.smit.flightops.observability;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The outbox from the outside: two gauges and two counters.
 * <ul>
 *   <li>{@code outbox.pending}: unpublished and still retryable. A rising line is
 *       publisher lag.</li>
 *   <li>{@code outbox.dead}: out of attempts and never claimed again. <b>Alert at
 *       {@code > 0}</b>; it recovers only after
 *       {@code UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?}.</li>
 *   <li>{@code outbox.publish{result}}: sends by outcome; the {@code failure} rate shows a
 *       partial outage that {@code pending} hides while the backlog still drains.</li>
 *   <li>{@code outbox.pruned}: rows deleted. Flat at zero while the table grows means the
 *       pruner is not running.</li>
 * </ul>
 *
 * <p>The gauges query the database on the scrape thread. A throw would drop the whole
 * {@code /actuator/prometheus} response, so they return {@code NaN} instead. Both count
 * through {@code idx_outbox_unpublished}, so they stay cheap however large the table is.
 * Micrometer holds the state object weakly, so it is the singleton repository.
 */
@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    public static final String PENDING = "outbox.pending";
    public static final String DEAD = "outbox.dead";
    public static final String PUBLISH = "outbox.publish";
    public static final String PRUNED = "outbox.pruned";

    /** Sent and accepted by the transport. */
    public static final String SUCCESS = "success";
    /** The transport threw. The row keeps its attempt and is retried. */
    public static final String FAILURE = "failure";
    /** The row hit the attempt ceiling on this send and will not be claimed again. */
    public static final String EXHAUSTED = "exhausted";

    private final Counter published;
    private final Counter failed;
    private final Counter exhausted;
    private final Counter pruned;

    public OutboxMetrics(MeterRegistry registry,
                         OutboxEventRepository repository,
                         OutboxProperties properties) {

        Gauge.builder(PENDING, repository,
                      r -> count(() -> r.countByPublishedAtIsNullAndAttemptsLessThan(
                              properties.maxAttempts())))
                .description("Outbox rows waiting to be published and still within the attempt ceiling")
                .register(registry);

        Gauge.builder(DEAD, repository,
                      r -> count(() -> r.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(
                              properties.maxAttempts())))
                .description("Outbox rows that exhausted their attempts and will never be claimed again")
                .register(registry);

        this.published = publishCounter(registry, SUCCESS);
        this.failed = publishCounter(registry, FAILURE);
        this.exhausted = publishCounter(registry, EXHAUSTED);
        this.pruned = Counter.builder(PRUNED)
                .description("Published outbox rows deleted by the retention pruner")
                .register(registry);
    }

    /** One description for the three series: Prometheus prints one {@code # HELP} per name. */
    private static Counter publishCounter(MeterRegistry registry, String result) {
        return Counter.builder(PUBLISH)
                .tag("result", result)
                .description("Outbox publish attempts by outcome")
                .register(registry);
    }

    /** {@code NaN}, not a stale last value, which would look like a healthy flat line. */
    private static double count(CountQuery query) {
        try {
            return query.run();
        } catch (RuntimeException e) {
            log.debug("Outbox gauge could not read the database: {}", e.toString());
            return Double.NaN;
        }
    }

    @FunctionalInterface
    private interface CountQuery {
        long run();
    }

    public void publishSucceeded() {
        published.increment();
    }

    public void publishFailed() {
        failed.increment();
    }

    /** Counted as well as {@link #publishFailed()}, so {@code failure} is the full error rate. */
    public void attemptsExhausted() {
        exhausted.increment();
    }

    public void pruned(int rows) {
        pruned.increment(rows);
    }
}
