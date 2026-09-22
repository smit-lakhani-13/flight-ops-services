package com.smit.flightops.dto;

import com.smit.flightops.entity.Booking;

import java.time.Instant;

public record BookingDto(Long bookingId, String flightNumber, String passengerName,
                         int seats, String idempotencyKey, Instant createdAt,
                         /**
                          * Null while the booking is active. Added rather than a
                          * boolean {@code cancelled} because the time is the part
                          * a caller reconciling against its own records needs, and
                          * {@code cancelledAt != null} answers the boolean question
                          * without a second field that could disagree with this one.
                          */
                         Instant cancelledAt) {

    /**
     * Touches booking.getFlight(), which is a LAZY association — call this
     * inside the transaction, or fetch the flight with a JOIN FETCH first.
     */
    public static BookingDto from(Booking b) {
        return new BookingDto(b.getId(), b.getFlight().getFlightNumber(), b.getPassengerName(),
                              b.getSeats(), b.getIdempotencyKey(), b.getCreatedAt(),
                              b.getCancelledAt());
    }
}
