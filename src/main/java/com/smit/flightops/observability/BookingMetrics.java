package com.smit.flightops.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The three counters that {@code http.server.requests} cannot give you.
 *
 * <p><b>Why so few.</b> Micrometer already counts every request by URI, method
 * and status, so a meter for "bookings that 404'd" would be a worse copy of
 * data that exists. These three are here because each one is invisible in the
 * HTTP metrics:
 *
 * <ul>
 *   <li>{@code bookings.booked{outcome}} — a replay and a genuine booking are
 *       both 201 on the same URI. Only this counter can answer "are we actually
 *       selling seats, or is a client stuck in a retry loop?", which is the
 *       difference between a healthy graph and an incident.</li>
 *   <li>{@code bookings.cancelled{outcome}} — same shape, same reason: a
 *       repeated {@code DELETE} returns 200 and releases nothing, by design.
 *       {@code already_cancelled} climbing on its own means a client believes
 *       its cancellations are not sticking.</li>
 *   <li>{@code bookings.lock_timeout} — 503s do show up in the HTTP metrics,
 *       but mixed in with every other cause of a 503. This one names the
 *       specific failure: somebody held the flight row for longer than the
 *       three-second {@code lock_timeout}. It is the leading indicator for the
 *       whole write path stalling, so it gets its own meter and its own alert
 *       rather than a status-code filter somebody has to remember to write.</li>
 * </ul>
 *
 * <p><b>{@code bookings.booked}, not {@code bookings.created}.</b> That was the
 * first name, and scraping it showed {@code bookings_total} — the word
 * "created" had vanished. {@code _created} is a reserved suffix in OpenMetrics
 * (it names a counter's own creation timestamp), so the Prometheus client
 * strips it before appending {@code _total}, silently, with no warning
 * anywhere. The meter was fine and the dashboard query would have returned
 * nothing. {@code BookingMetricsTest} scrapes a real
 * {@code PrometheusMeterRegistry} so that this is caught by the build rather
 * than by somebody wondering why a panel is empty.
 *
 * <p>Both series of one meter share a description, which is not tidiness: with
 * two different descriptions the exporter prints whichever registered last as
 * the {@code # HELP} line for both, so the text on the graph describes half the
 * data. That is also how it was first written.
 *
 * <p>Counters are registered eagerly in the constructor rather than looked up
 * per call. A counter that is only created on first increment is absent from
 * {@code /actuator/prometheus} until the event happens, which breaks the alert
 * that was supposed to fire on it: {@code rate(bookings_lock_timeout_total[5m])}
 * over a missing series returns no data, and "no data" is not "zero" to most
 * alerting rules. Registering up front means every series exists at zero from
 * the first scrape.
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

    /**
     * Covers both replay paths — the one served from a committed row and the
     * one recovered after losing a unique-constraint race. They are the same
     * event from a client's point of view, and splitting them would produce a
     * series whose only reader is somebody debugging this service rather than
     * operating it.
     */
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
