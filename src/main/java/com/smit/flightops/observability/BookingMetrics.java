package com.smit.flightops.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Three meters that {@code http.server.requests} cannot give you.
 * <ul>
 *   <li>{@code bookings.booked{outcome}}: a replay and a new booking are both 201 on
 *       the same URI; only this tells them apart.</li>
 *   <li>{@code bookings.cancelled{outcome}}: a repeated {@code DELETE} returns 200 and
 *       releases nothing; {@code already_cancelled} rising means a client thinks its
 *       cancels are not sticking.</li>
 *   <li>{@code bookings.lock_timeout}: a flight row held past the 3s {@code lock_timeout},
 *       the earliest sign of the write path stalling. Nothing alerts on it yet.</li>
 * </ul>
 *
 * <p>Not {@code bookings.created}: {@code _created} is a reserved OpenMetrics suffix
 * that the Prometheus client strips ({@code BookingMetricsTest} scrapes a real registry
 * to check). Both series of a meter share one description because Prometheus prints
 * one {@code # HELP} line per name. Registered eagerly so every series exists at zero
 * from the first scrape; an alert on a missing series sees no data, not zero.
 */
@Component
public class BookingMetrics {

    /** POST /api/v1/bookings, by what it actually did. */
    public static final String BOOKED = "bookings.booked";
    /** DELETE /api/v1/bookings/{id}, by what it actually did. */
    public static final String CANCELLATIONS = "bookings.cancelled";
    /** Untagged: gave up waiting for the flight row lock. */
    public static final String LOCK_TIMEOUT = "bookings.lock_timeout";

    private static final String BOOKED_DESCRIPTION =
            "Booking requests, split by whether they reserved seats or replayed an existing booking";
    private static final String CANCELLED_DESCRIPTION =
            "Cancellation requests, split by whether they released seats or were a no-op";

    /** A booking that debited seats for the first time. */
    public static final String CREATED = "created";
    /** A repeat of a request that had already succeeded. No seats moved. */
    public static final String REPLAYED = "replayed";
    /** A cancellation that actually released seats. */
    public static final String CANCELLED = "cancelled";
    /** A {@code DELETE} on a booking that was already cancelled. A no-op. */
    public static final String ALREADY_CANCELLED = "already_cancelled";

    private final Counter created;
    private final Counter replayed;
    private final Counter cancelled;
    private final Counter alreadyCancelled;
    private final Counter lockTimeouts;

    public BookingMetrics(MeterRegistry registry) {
        this.created = bookingCounter(registry, BOOKED, CREATED, BOOKED_DESCRIPTION);
        this.replayed = bookingCounter(registry, BOOKED, REPLAYED, BOOKED_DESCRIPTION);
        this.cancelled = bookingCounter(registry, CANCELLATIONS, CANCELLED, CANCELLED_DESCRIPTION);
        this.alreadyCancelled =
                bookingCounter(registry, CANCELLATIONS, ALREADY_CANCELLED, CANCELLED_DESCRIPTION);
        this.lockTimeouts = Counter.builder(LOCK_TIMEOUT)
                .description("Requests that gave up waiting for the flight row lock")
                .register(registry);
    }

    private static Counter bookingCounter(MeterRegistry registry, String name,
                                          String outcome, String description) {
        return Counter.builder(name)
                .tag("outcome", outcome)
                .description(description)
                .register(registry);
    }

    public void bookingCreated() {
        created.increment();
    }

    /** Both replay paths: from a committed row, and recovered after a lost race. */
    public void bookingReplayed() {
        replayed.increment();
    }

    public void bookingCancelled() {
        cancelled.increment();
    }

    public void cancellationWasANoOp() {
        alreadyCancelled.increment();
    }

    public void lockTimedOut() {
        lockTimeouts.increment();
    }
}
