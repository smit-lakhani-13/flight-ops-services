package com.smit.flightops.entity;

import java.util.Arrays;
import java.util.List;

/**
 * Lifecycle of a flight, and the one question the booking path needs answered
 * about it: can this flight still take a reservation?
 *
 * <p>{@link #isBookable()} lives here rather than in the service on purpose. As
 * a {@code status != CANCELLED} check in {@code BookingService} it would be one
 * caller's opinion, and the second caller — a bulk import, an admin endpoint, a
 * message consumer — would forget it. As a {@code switch} over every constant it
 * is exhaustive: adding a status to this enum stops compiling until somebody
 * decides whether that status accepts bookings.
 */
public enum FlightStatus {

    /** Not departed, seats on sale. The state a flight is created in. */
    SCHEDULED,

    /** Boarding has started. Gate sales are a real thing, so still bookable. */
    BOARDING,

    /** In the air. Nobody else is getting on. */
    DEPARTED,

    /** Landed. Terminal state. */
    ARRIVED,

    /** Soft-cancelled — the row survives because bookings reference it. */
    CANCELLED,

    /** Late, but still going. Delays do not stop ticket sales. */
    DELAYED;

    /**
     * Whether this status still accepts seat reservations.
     *
     * <p>Written as an exhaustive switch with no {@code default}, which is the
     * point: {@code default -> false} (or {@code -> true}) would let a new
     * constant inherit an answer nobody chose. Without it, javac rejects the
     * switch as non-exhaustive and the new status has to be classified.
     */
    public boolean isBookable() {
        return switch (this) {
            case SCHEDULED, BOARDING, DELAYED -> true;
            case DEPARTED, ARRIVED, CANCELLED -> false;
        };
    }

    /**
     * The same answer as {@link #isBookable()}, in the form a database query
     * needs.
     *
     * <p>This exists so a JPQL {@code status IN :statuses} can be driven by the
     * enum instead of repeating the list as a literal. A hardcoded
     * {@code status = 'SCHEDULED'} in a query is precisely the "second caller
     * that forgets" this class's Javadoc warns about — it compiles, it passes,
     * and it silently stops selling seats on every delayed flight.
     */
    public static List<FlightStatus> bookableStatuses() {
        return Arrays.stream(values()).filter(FlightStatus::isBookable).toList();
    }
}
