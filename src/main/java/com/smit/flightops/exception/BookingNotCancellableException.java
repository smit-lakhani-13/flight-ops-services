package com.smit.flightops.exception;

import com.smit.flightops.entity.FlightStatus;

/**
 * The booking is still active and its flight has departed or arrived. 409 for
 * the reason {@link IllegalFlightTransitionException} gives: the request is
 * valid, and the flight's state is what conflicts. A booking cancelled before
 * departure never gets here, so a retried {@code DELETE} still answers 200.
 */
public class BookingNotCancellableException extends RuntimeException {

    private final Long bookingId;
    private final String flightNumber;
    private final FlightStatus status;

    public BookingNotCancellableException(Long bookingId, String flightNumber, FlightStatus status) {
        super("Flight %s is %s and booking %d cannot be cancelled"
                  .formatted(flightNumber, status, bookingId));
        this.bookingId = bookingId;
        this.flightNumber = flightNumber;
        this.status = status;
    }

    public Long getBookingId() { return bookingId; }
    public String getFlightNumber() { return flightNumber; }
    public FlightStatus getStatus() { return status; }
}
