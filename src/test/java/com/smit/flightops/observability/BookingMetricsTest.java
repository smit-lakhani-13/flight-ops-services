package com.smit.flightops.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The booking counters: eager registration, tag separation and the names Prometheus exposes. */
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
     * An alert on {@code rate(bookings_lock_timeout_total[5m])} sees "no data" until the
     * series exists, so it would miss the first lock timeout. This fails if the
     * registration moves into the increment methods.
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
    @DisplayName("a replay and a real booking are distinguishable")
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
     * A {@link SimpleMeterRegistry} keeps names verbatim, so only a real
     * {@link PrometheusMeterRegistry} shows the exposed name. The client strips
     * OpenMetrics' reserved suffixes such as {@code _created}, which would turn
     * {@code bookings.created} into {@code bookings_total}.
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

        // A reserved suffix eaten from a meter name.
        assertThat(scrape)
                .as("if this appears, a reserved suffix has eaten part of a meter name again")
                .doesNotContain("bookings_total");

        // One HELP line per meter, describing both of its series.
        assertThat(scrape.lines().filter(l -> l.startsWith("# HELP bookings_booked_total")).count())
                .isEqualTo(1);
        assertThat(scrape).contains(
                "# HELP bookings_booked_total Booking requests, split by whether they reserved "
                + "seats or replayed an existing booking");
    }
}
