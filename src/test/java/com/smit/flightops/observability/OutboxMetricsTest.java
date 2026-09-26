package com.smit.flightops.observability;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The outbox gauges read a cache, and only the refresher touches the database. Most
 * tests construct the bean without starting it, so nothing runs in the background and
 * every count is the one the test stubbed.
 */
class OutboxMetricsTest {

    private static final int MAX_ATTEMPTS = 10;

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final MovableClock clock = new MovableClock(Instant.parse("2026-01-01T00:00:00Z"));

    private static OutboxProperties properties() {
        return new OutboxProperties(true, 1000, 100, MAX_ATTEMPTS,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5));
    }

    private OutboxMetrics metricsOn(MeterRegistry registry) {
        return new OutboxMetrics(registry, repository, properties(), clock);
    }

    private static double gauge(MeterRegistry registry, String name) {
        return registry.get(name).gauge().value();
    }

    private void countsAre(long pending, long dead) {
        when(repository.countByPublishedAtIsNullAndAttemptsLessThan(MAX_ATTEMPTS)).thenReturn(pending);
        when(repository.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(MAX_ATTEMPTS)).thenReturn(dead);
    }

    /** A scrape that queried the database would wait on the pool once per gauge. */
    @Test
    @DisplayName("reading the gauges never queries the database; only refresh does")
    void gaugeReadsDoNotQueryTheDatabase() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = metricsOn(registry);
        countsAre(3, 1);

        assertThat(gauge(registry, OutboxMetrics.PENDING)).as("before the first refresh").isNaN();
        verifyNoInteractions(repository);

        metrics.refresh();
        for (int i = 0; i < 100; i++) {
            gauge(registry, OutboxMetrics.PENDING);
            gauge(registry, OutboxMetrics.DEAD);
        }

        verify(repository, times(1)).countByPublishedAtIsNullAndAttemptsLessThan(MAX_ATTEMPTS);
        verify(repository, times(1)).countByPublishedAtIsNullAndAttemptsGreaterThanEqual(MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("each refresh replaces the cached counts")
    void refreshUpdatesTheGauges() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = metricsOn(registry);

        countsAre(3, 1);
        metrics.refresh();
        assertThat(gauge(registry, OutboxMetrics.PENDING)).isEqualTo(3);
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isEqualTo(1);

        countsAre(5, 0);
        clock.advance(Duration.ofSeconds(15));
        metrics.refresh();
        assertThat(gauge(registry, OutboxMetrics.PENDING)).isEqualTo(5);
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isZero();
    }

    /**
     * A failed count keeps the last good one until it is three intervals old, then the
     * gauge says it does not know. A flat last value would look like a healthy outbox.
     */
    @Test
    @DisplayName("while the database fails, the last count holds for three intervals and then reads NaN")
    void aFailingDatabaseTurnsTheGaugesNaNOnceTheCountIsStale() {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = metricsOn(registry);
        countsAre(3, 1);
        metrics.refresh();

        DataAccessResourceFailureException down = new DataAccessResourceFailureException("connection refused");
        when(repository.countByPublishedAtIsNullAndAttemptsLessThan(anyInt())).thenThrow(down);
        when(repository.countByPublishedAtIsNullAndAttemptsGreaterThanEqual(anyInt())).thenThrow(down);

        clock.advance(OutboxMetrics.STALE_AFTER);
        metrics.refresh();
        assertThat(gauge(registry, OutboxMetrics.PENDING)).as("exactly three intervals old").isEqualTo(3);
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(1));
        metrics.refresh();
        assertThat(gauge(registry, OutboxMetrics.PENDING)).as("past the window").isNaN();
        assertThat(gauge(registry, OutboxMetrics.DEAD)).isNaN();
    }

    /**
     * The failure this class exists for: a database that hangs rather than refuses.
     * The refresher is stuck on it, and a scrape still answers at once.
     */
    @Test
    @DisplayName("a scrape answers at once while the refresher is stuck on the database")
    void aHungDatabaseDoesNotHoldUpTheScrape() throws Exception {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        OutboxMetrics metrics = new OutboxMetrics(prometheus, repository, properties(), Clock.systemUTC());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.countByPublishedAtIsNullAndAttemptsLessThan(MAX_ATTEMPTS)).thenAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return 0L;
        });

        metrics.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).as("the refresher is inside the query").isTrue();

            // Preemptive, so a gauge that queried again would fail here rather than hang
            // the build; 5 s is far below the pool's 30 s wait this rules out.
            String scrape = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> prometheus.scrape());

            assertThat(scrape).contains("outbox_pending NaN").contains("outbox_dead NaN");
        } finally {
            release.countDown();
            metrics.stop();
        }
    }

    /** Its own daemon thread, not the scheduler thread the drain and the pruner share. */
    @Test
    @DisplayName("the refresh runs at once on its own daemon thread, which stops with the bean")
    void theRefresherRunsOnItsOwnThreadAndStops() throws Exception {
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = metricsOn(registry);
        AtomicReference<Thread> refresher = new AtomicReference<>();
        when(repository.countByPublishedAtIsNullAndAttemptsLessThan(MAX_ATTEMPTS)).thenAnswer(invocation -> {
            refresher.set(Thread.currentThread());
            return 2L;
        });

        metrics.start();
        verify(repository, timeout(5_000)).countByPublishedAtIsNullAndAttemptsGreaterThanEqual(MAX_ATTEMPTS);
        metrics.stop();

        Thread thread = refresher.get();
        assertThat(thread.getName()).isEqualTo("outbox-metrics");
        assertThat(thread.isDaemon()).as("it must not keep the JVM alive").isTrue();
        thread.join(5_000);
        assertThat(thread.isAlive()).as("stop() shuts the executor down").isFalse();
        assertThat(gauge(registry, OutboxMetrics.PENDING)).isEqualTo(2);
    }

    /** A Clock a test can move forward. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
