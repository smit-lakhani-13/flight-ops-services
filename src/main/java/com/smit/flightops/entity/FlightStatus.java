package com.smit.flightops.entity;

import java.util.Objects;

/**
 * Lifecycle of a flight, and whether it can still take a reservation.
 *
 * <p>{@link #isBookable()} lives here, not in the service: as a check in
 * {@code BookingService} it would be one caller's rule, and a second caller (a bulk
 * import, an admin endpoint, a message consumer) would forget it. The switches have
 * no {@code default}, so a new constant does not compile until someone classifies it.
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

    /** Whether this status still accepts seat reservations. */
    public boolean isBookable() {
        return switch (this) {
            case SCHEDULED, BOARDING, DELAYED -> true;
            case DEPARTED, ARRIVED, CANCELLED -> false;
        };
    }

    /**
     * Whether a booking on a flight in this status may still be cancelled. Not
     * the inverse of {@link #isBookable()}: a cancelled flight still takes
     * cancellations, because refunds happen on cancelled flights. A departed or
     * arrived flight does not, because cancelling then would rewrite the record
     * of a flight that has already flown.
     */
    public boolean acceptsCancellations() {
        return switch (this) {
            case SCHEDULED, BOARDING, DELAYED, CANCELLED -> true;
            case DEPARTED, ARRIVED -> false;
        };
    }

    /**
     * Whether this status may become {@code next}. The judgement calls:
     *
     * <ul>
     *   <li><b>A status may always transition to itself</b>, so a client re-sending
     *       DELAYED after a timeout gets 200 and no change, not 409.</li>
     *   <li><b>ARRIVED and CANCELLED are terminal.</b> Correcting one recorded in error
     *       is a data repair with an audit trail, not a PATCH; otherwise a cancelled
     *       flight could go back on sale.</li>
     * </ul>
     */
    public boolean canTransitionTo(FlightStatus next) {
        Objects.requireNonNull(next, "next must not be null");
        if (next == this) {
            return true;
        }
        return switch (this) {
            // No BOARDING step is required: flights depart without one being recorded.
            case SCHEDULED -> next == BOARDING || next == DEPARTED
                           || next == DELAYED  || next == CANCELLED;
            case DELAYED   -> next == BOARDING || next == DEPARTED
                           || next == CANCELLED;
            case BOARDING  -> next == DEPARTED || next == DELAYED
                           || next == CANCELLED;
            // In the air: it lands. A diversion still ends in ARRIVED.
            case DEPARTED  -> next == ARRIVED;
            case ARRIVED, CANCELLED -> false;
        };
    }
}
