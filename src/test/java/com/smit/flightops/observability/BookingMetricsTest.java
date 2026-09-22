package com.smit.flightops.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final BookingMetrics metrics = new BookingMetrics(registry);

    private double count(String name, String outcome) {
        Counter counter = outcome == null
                ? registry.find(name).counter()
                : registry.find(name).tag("outcome", outcome).counter();
        assertThat(counter).as("meter %s{outcome=%s} is not registered", name, outcome).isNotNull();
        return counter.count();
    }

    /**
     * The reason the constructor registers every counter eagerly instead of
     * creating them on first use.
     *
     * <p>A Prometheus alert like
     * {@code rate(bookings_lock_timeout_total[5m]) > 0.1} evaluates against a
     * series that does not exist yet, and most alerting rules treat "no data"
     * as neither firing nor resolved — so the alert that was written to catch
     * the first lock timeout is silent for exactly the first lock timeout. This
     * test fails if someone moves the registration into the increment methods,
     * which is the tidier-looking version of this class.
     */
    @Test
    @DisplayName("every series exists at zero before anything has happened")
    void metersAreRegisteredEagerly() {
        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.CREATED)).isZero();
        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.REPLAYED)).isZero();
        assertThat(count(BookingMetrics.CANCELLATIONS, BookingMetrics.CANCELLED)).isZero();
        assertThat(count(BookingMetrics.CANCELLATIONS, BookingMetrics.ALREADY_CANCELLED)).isZero();
        assertThat(count(BookingMetrics.LOCK_TIMEOUT, null)).isZero();
    }

    @Test
    @DisplayName("each outcome lands on its own series and nowhere else")
    void incrementsDoNotLeakAcrossTags() {
        metrics.bookingCreated();
        metrics.bookingReplayed();
        metrics.bookingReplayed();
        metrics.bookingCancelled();
        metrics.cancellationWasANoOp();
        metrics.lockTimedOut();

        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.CREATED)).isEqualTo(1);
        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.REPLAYED)).isEqualTo(2);
        assertThat(count(BookingMetrics.CANCELLATIONS, BookingMetrics.CANCELLED)).isEqualTo(1);
        assertThat(count(BookingMetrics.CANCELLATIONS, BookingMetrics.ALREADY_CANCELLED)).isEqualTo(1);
        assertThat(count(BookingMetrics.LOCK_TIMEOUT, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("a replay and a real booking are distinguishable, which is the whole point")
    void replaysAreNotCountedAsSales() {
        for (int i = 0; i < 10; i++) {
            metrics.bookingReplayed();
        }
        metrics.bookingCreated();

        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.CREATED))
                .as("ten retries of one booking are one sale, not eleven")
                .isEqualTo(1);
        assertThat(count(BookingMetrics.BOOKED, BookingMetrics.REPLAYED)).isEqualTo(10);
    }

    /**
     * The test that pays for this whole class.
     *
     * <p>A {@link SimpleMeterRegistry} stores the Micrometer name verbatim, so
     * the assertions above pass for any name at all. What reaches a dashboard
     * is the Prometheus <em>exposition</em> name, and the translation is not a
     * simple dots-to-underscores: the client strips OpenMetrics' reserved
     * suffixes first. The original {@code bookings.created} came out of
     * {@code /actuator/prometheus} as {@code bookings_total}, because
     * {@code _created} is reserved — no warning, no error, just a meter under a
     * name nobody would query. Scraping a real
     * {@link PrometheusMeterRegistry} is the only way to see that from a test.
     */
    @Test
    @DisplayName("the names Prometheus actually exposes are the names an alert would query")
    void exportedNamesSurviveTheTripThroughPrometheus() {
        PrometheusMeterRegistry prometheus = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new BookingMetrics(prometheus);

        String scrape = prometheus.scrape();

        assertThat(scrape)
                .contains("bookings_booked_total{outcome=\"created\"}")
                .contains("bookings_booked_total{outcome=\"replayed\"}")
                .contains("bookings_cancelled_total{outcome=\"cancelled\"}")
                .contains("bookings_cancelled_total{outcome=\"already_cancelled\"}")
                .contains("bookings_lock_timeout_total");

        // The regression itself, named: a reserved suffix silently eaten.
        assertThat(scrape)
                .as("if this appears, a reserved suffix has eaten part of a meter name again")
                .doesNotContain("bookings_total");

        // One HELP line per meter, describing both of its series honestly.
        assertThat(scrape.lines().filter(l -> l.startsWith("# HELP bookings_booked_total")).count())
                .isEqualTo(1);
        assertThat(scrape).contains(
                "# HELP bookings_booked_total Booking requests, split by whether they reserved "
                + "seats or replayed an existing booking");
    }
}
