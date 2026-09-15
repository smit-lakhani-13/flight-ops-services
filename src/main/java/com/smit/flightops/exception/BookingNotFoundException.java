package com.smit.flightops.exception;

public class BookingNotFoundException extends RuntimeException {

    private final Long bookingId;

    public BookingNotFoundException(Long bookingId) {
        super("Booking not found: " + bookingId);
        this.bookingId = bookingId;
    }

    public Long getBookingId() { return bookingId; }
}
