package com.smit.flightops.exception;

import com.smit.flightops.entity.FlightStatus;

/**
 * The flight exists and has seats, but its status does not accept reservations —
 * cancelled, departed or already arrived.
 *
 * <p>Deliberately its own type rather than a reuse of
 * {@link InsufficientSeatsException}. That exception's message is
 * "Flight X has N seat(s) available, M requested", which on a cancelled flight
 * with 176 free seats would be actively false and would send the caller off to
 * retry with fewer seats forever.
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
