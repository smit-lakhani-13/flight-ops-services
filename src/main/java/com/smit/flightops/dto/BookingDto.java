package com.smit.flightops.dto;

import com.smit.flightops.entity.Booking;

import java.time.Instant;

/**
 * What a caller gets back for a booking.
 *
 * <p>{@code idempotencyKey} is deliberately not here, although the request
 * carries one and the column stores it. It is the caller's own value: they
 * chose it, they already have it, and echoing it back adds nothing they can
 * use. What it does add is a way to read other people's keys — the list
 * endpoint returns every booking on a flight, so a reader with
 * {@code flights:read} could harvest the keys of bookings they did not make and
 * replay against them. Not returning it costs a caller nothing and closes that.
 */
public record BookingDto(Long bookingId, String flightNumber, String passengerName,
                         int seats, Instant createdAt,
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
                              b.getSeats(), b.getCreatedAt(), b.getCancelledAt());
    }
}
