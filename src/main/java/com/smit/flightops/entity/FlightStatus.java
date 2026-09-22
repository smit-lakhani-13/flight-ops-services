package com.smit.flightops.entity;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

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

    /**
     * Whether this status is allowed to become {@code next}.
     *
     * <p>{@code Flight.updateStatus} used to accept any status from any status,
     * which meant {@code PATCH /api/v1/flights/UA123 {"status":"SCHEDULED"}}
     * would un-cancel a cancelled flight and put its seats back on sale, and
     * an arrived flight could be sent back to BOARDING. Neither is a thing that
     * happens to an aeroplane. The old Javadoc called the missing graph a
     * "known limitation, and a conscious one"; this is the graph.
     *
     * <p>Two rules are worth stating because they are judgement calls rather
     * than facts about aviation:
     *
     * <ul>
     *   <li><b>A status may always transition to itself.</b> PATCH is not
     *       required to be idempotent, but a client that re-sends DELAYED after
     *       a timeout should get 200 and no change, not 409. Rejecting the
     *       no-op would make the endpoint unsafe to retry, which is the same
     *       mistake the booking path exists to avoid.</li>
     *   <li><b>ARRIVED and CANCELLED are terminal.</b> Correcting a status
     *       recorded in error is a data-repair job with an audit trail, not a
     *       PATCH. Leaving the door open so that operations can fix a typo is
     *       how a cancelled flight ends up selling seats.</li>
     * </ul>
     *
     * <p>Exhaustive switch, no {@code default}, for the same reason as
     * {@link #isBookable()}: a new constant must not inherit a transition
     * policy nobody chose.
     */
    public boolean canTransitionTo(FlightStatus next) {
        Objects.requireNonNull(next, "next must not be null");
        if (next == this) {
            return true;
        }
        return switch (this) {
            // No BOARDING step is required first: flights depart without one
            // ever being recorded, and refusing DEPARTED here would make the
            // system disagree with the aircraft.
            case SCHEDULED -> next == BOARDING || next == DEPARTED
                           || next == DELAYED  || next == CANCELLED;
            case DELAYED   -> next == BOARDING || next == DEPARTED
                           || next == CANCELLED;
            case BOARDING  -> next == DEPARTED || next == DELAYED
                           || next == CANCELLED;
            // In the air. It lands, or the record is wrong. A diversion still
            // ends in ARRIVED, at a different airport.
            case DEPARTED  -> next == ARRIVED;
            case ARRIVED, CANCELLED -> false;
        };
    }
}
