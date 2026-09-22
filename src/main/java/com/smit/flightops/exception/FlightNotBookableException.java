package com.smit.flightops.exception;

import com.smit.flightops.entity.FlightStatus;

/**
 * The flight exists but its status refuses reservations: cancelled, departed
 * or arrived. Its own type, not {@link InsufficientSeatsException}, because that
 * message counts seats, and a cancelled flight with free seats would send the
 * caller off to retry with fewer of them.
 */
public class FlightNotBookableException extends RuntimeException {

    private final String flightNumber;
    private final FlightStatus status;

    public FlightNotBookableException(String flightNumber, FlightStatus status) {
        super("Flight %s is %s and cannot be booked".formatted(flightNumber, status));
        this.flightNumber = flightNumber;
        this.status = status;
    }

    public String getFlightNumber() { return flightNumber; }
    public FlightStatus getStatus() { return status; }
}
