package com.smit.flightops.dto;

import com.smit.flightops.entity.Booking;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What a caller gets back for a booking.
 *
 * <p>{@code idempotencyKey} is left out although the request carries it. The
 * caller already has its own key, and the list endpoint returns every booking
 * on a flight, so echoing keys would let a {@code flights:read} caller harvest
 * other people's keys and replay against them.
 *
 * @param cancelledAt null while the booking is active. A timestamp rather than
 *        a boolean, because the time is what a caller reconciling its own
 *        records needs, and {@code cancelledAt != null} answers the boolean.
 *        Jackson writes the null out, so {@code @Schema} adds {@code "null"} to
 *        the types, which is how OpenAPI 3.1 marks a value nullable. Without it
 *        a client that validates responses against the document refuses every
 *        active booking
 */
public record BookingDto(Long bookingId, String flightNumber, String passengerName,
                         int seats, Instant createdAt,
                         @Schema(types = {"string", "null"}, format = "date-time") Instant cancelledAt) {

    /** Reads the LAZY {@code flight} association, so call it inside the transaction. */
    public static BookingDto from(Booking b) {
        return new BookingDto(b.getId(), b.getFlight().getFlightNumber(), b.getPassengerName(),
                              b.getSeats(), b.getCreatedAt(), b.getCancelledAt());
    }
}
