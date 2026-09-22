package com.smit.flightops.exception;

import com.smit.flightops.entity.FlightStatus;

/**
 * A status change the flight lifecycle does not allow — un-cancelling a
 * cancelled flight, or sending an arrived one back to BOARDING.
 *
 * <p>409 rather than 400, and the distinction is the useful part: the request
 * is well formed and the target status is a real status, so nothing about the
 * payload is wrong. What is wrong is the state the flight is in right now,
 * which is precisely what 409 Conflict means. A 400 would tell the client to
 * fix its input, and there is no input to fix.
 *
 * <p>Carries both statuses so the message can name them. "Flight UA123 cannot
 * go from CANCELLED to SCHEDULED" is actionable; "invalid status" is not.
 */
public class IllegalFlightTransitionException extends RuntimeException {

    private final String flightNumber;
    private final FlightStatus from;
    private final FlightStatus to;

    public IllegalFlightTransitionException(String flightNumber, FlightStatus from, FlightStatus to) {
        super("Flight %s cannot go from %s to %s".formatted(flightNumber, from, to));
        this.flightNumber = flightNumber;
        this.from = from;
        this.to = to;
    }

    public String getFlightNumber() { return flightNumber; }
    public FlightStatus getFrom() { return from; }
    public FlightStatus getTo() { return to; }
}
