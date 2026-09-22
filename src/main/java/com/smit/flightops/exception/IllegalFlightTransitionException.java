package com.smit.flightops.exception;

import com.smit.flightops.entity.FlightStatus;

/**
 * A status change the flight lifecycle does not allow, such as un-cancelling a
 * cancelled flight. 409 rather than 400: the payload is valid and the status is
 * real; the flight's current state is what conflicts. Both statuses go into the
 * message so the caller can act on it.
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
