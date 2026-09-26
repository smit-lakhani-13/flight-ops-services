package com.smit.flightops.observability;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
 * <p>The gauges read counts that {@link #refresh()} caches, never the database. A
 * query on the scrape thread would wait out Hikari's 30 s connection timeout while the
 * database is unreachable, once per gauge, and a scraper would give up on the whole
 * response and lose every other series with it. The refresh runs every
 * {@link #REFRESH_INTERVAL} on a thread of its own, {@code outbox-metrics}, not on the
 * scheduler thread the drain and the pruner share, so a refresh stuck on the pool
 * delays neither. A failed count is logged at DEBUG and keeps the last good one. A
 * gauge reports {@code NaN} until its first successful count, and again once that
 * count is older than {@link #STALE_AFTER} by the application's {@link Clock}. Both
 * count through {@code idx_outbox_unpublished}, so they stay cheap however large the
 * table is. Not conditional on {@code app.outbox.enabled}: a replica that does not
 * drain still shows the backlog.
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

    /** Shorter than a usual scrape interval, so a scrape reads a count at most this old. */
    static final Duration REFRESH_INTERVAL = Duration.ofSeconds(15);

    /**
     * Three intervals: long enough to ride out a slow refresh, short enough that a
     * database outage shows as {@code NaN} within a minute rather than as a flat line.
     */
    static final Duration STALE_AFTER = REFRESH_INTERVAL.multipliedBy(3);

    private final OutboxEventRepository repository;
    private final OutboxProperties properties;
    private final Clock clock;

    private final AtomicReference<Sample> pending = new AtomicReference<>();
    private final AtomicReference<Sample> dead = new AtomicReference<>();

    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("outbox-metrics").daemon().factory());

    private final Counter published;
    private final Counter failed;
    private final Counter exhausted;
    private final Counter pruned;

    /** A count and when it was read. */
    private record Sample(long count, Instant at) {}

    public OutboxMetrics(MeterRegistry registry,
                         OutboxEventRepository repository,
                         OutboxProperties properties,
                         Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;

        // Micrometer holds the state object weakly; these are fields of a singleton.
        Gauge.builder(PENDING, pending, this::read)
                .description("Outbox rows waiting to be published and still within the attempt ceiling")
                .register(registry);

        Gauge.builder(DEAD, dead, this::read)
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

    /**
     * The first refresh runs at once, so the gauges have values moments after startup.
     * A fixed delay rather than a fixed rate, so a refresh that waited on the pool is
     * not followed by a burst of catch-up runs.
     */
    @PostConstruct
    void start() {
        refresher.scheduleWithFixedDelay(this::refresh, 0, REFRESH_INTERVAL.toMillis(),
                                         TimeUnit.MILLISECONDS);
    }

    /** {@code shutdownNow}, so a refresh waiting on the pool is interrupted, not awaited. */
    @PreDestroy
    void stop() {
        refresher.shutdownNow();
    }

    /**
     * Runs both counts and caches each one that succeeds. Public so a test can read a
     * gauge straight after changing the table. It is {@code synchronized}, so a refresh
     * that began before the change cannot store its older count over a newer one.
     */
    public synchronized void refresh() {
        refresh(pending, () -> repository.countByPublishedAtIsNullAndAttemptsLessThan(
                properties.maxAttempts()));
        refresh(dead, () -> repository.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(
                properties.maxAttempts()));
    }

    /**
     * The catch is what keeps the refresher alive: an executor stops rescheduling a task
     * that throws, and every gauge would go {@code NaN} for the life of the pod.
     */
    private void refresh(AtomicReference<Sample> cache, CountQuery query) {
        try {
            long count = query.run();
            cache.set(new Sample(count, clock.instant()));
        } catch (RuntimeException e) {
            log.debug("Outbox gauge could not read the database: {}", e.toString());
        }
    }

    /** {@code NaN}, not a stale last value, which would look like a healthy flat line. */
    private double read(AtomicReference<Sample> cache) {
        Sample sample = cache.get();
        if (sample == null || sample.at().isBefore(clock.instant().minus(STALE_AFTER))) {
            return Double.NaN;
        }
        return sample.count();
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
