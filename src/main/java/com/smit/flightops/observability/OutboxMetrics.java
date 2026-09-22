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
 * What the outbox looks like from the outside: two gauges and two counters.
 *
 * <p>The outbox's whole selling point over a direct send is that the backlog is
 * a table you can query. That is only true if somebody is querying it, so:
 *
 * <ul>
 *   <li>{@code outbox.pending} — unpublished and still retryable. Normally a
 *       handful; a rising line is publisher lag and is the alert that catches
 *       an SQS outage before a consumer notices missing events.</li>
 *   <li>{@code outbox.dead} — unpublished and out of attempts. Rows the poller
 *       will never claim again. <b>This is the gauge to alert on at
 *       {@code > 0}</b>: unlike pending, it does not recover on its own and it
 *       means a booking has no event and never will until somebody runs
 *       {@code UPDATE outbox_events SET attempts = 0 WHERE id = ?}.</li>
 *   <li>{@code outbox.publish{result}} — sends attempted, by outcome. The ratio
 *       is the transport's health; the absolute {@code failure} rate is what
 *       shows a partial outage that {@code pending} hides while the backlog is
 *       still draining faster than it grows.</li>
 *   <li>{@code outbox.pruned} — rows deleted. Flat at zero while the table
 *       grows means the pruner is not running, which is otherwise a completely
 *       silent failure.</li>
 * </ul>
 *
 * <h2>The gauges query the database, and that needs care</h2>
 * Micrometer calls a gauge's function on every scrape, on the scraping thread,
 * outside any transaction of ours. Two consequences are handled here.
 *
 * <p>First, <b>an exception must not escape</b>. A gauge function that throws
 * takes down the whole {@code /actuator/prometheus} response — so a database
 * blip would remove every unrelated metric from the scrape at exactly the
 * moment they are most wanted, and the graph would show a hole rather than a
 * problem. Both functions return {@code NaN} instead, which Prometheus
 * represents as a gap in that one series and nothing else.
 *
 * <p>Second, <b>the queries must stay cheap</b>. Both are counts against
 * {@code idx_outbox_unpublished}, the partial index that only contains
 * unpublished rows — so they stay fast however large the table grows, which is
 * the same property that lets the poller work. A count of published rows would
 * not have that property and is deliberately not offered.
 *
 * <p>A weak reference is what Micrometer holds on the state object, hence
 * passing the repository explicitly: it is a singleton the container keeps
 * alive, so the gauge cannot silently stop reporting the way one bound to a
 * local variable does.
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

    /**
     * One description for all three series, because they share a meter name and
     * the exporter prints one {@code # HELP} line per name — a per-tag
     * description would silently describe all three series as whichever
     * registered last. Learned the hard way in {@link BookingMetrics}.
     */
    private static Counter publishCounter(MeterRegistry registry, String result) {
        return Counter.builder(PUBLISH)
                .tag("result", result)
                .description("Outbox publish attempts by outcome")
                .register(registry);
    }

    /**
     * {@code NaN} rather than a throw, and rather than a stale last-known
     * value. A gap in the series is honest about not knowing; a repeated last
     * value looks like a healthy flat line and is the worse lie of the two.
     */
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

    /**
     * Counted in addition to {@link #publishFailed()}, not instead of it: the
     * send did fail, and it was also the last one. Separating them would make
     * {@code failure} undercount the transport's real error rate.
     */
    public void attemptsExhausted() {
        exhausted.increment();
    }

    public void pruned(int rows) {
        pruned.increment(rows);
    }
}
