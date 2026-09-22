package com.smit.flightops.dto;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The wire contract with the SQS-triggered Lambda in {@code lambda/}. Field
 * names must match {@code BookingEventHandler.BookingEvent}, and both sides are
 * tested against {@code contracts/booking-created-v1.json}.
 *
 * <p>{@code timestamp} is the booking's persisted {@code createdAt}, never
 * {@code Instant.now()}: the consumer derives its DynamoDB sort key from it, so
 * it has to be the same on every redelivery of a message.
 */
public record BookingCreatedEvent(String bookingId, String flightNumber, int seats, String timestamp) {

    /**
     * Fixed width: six fractional digits, always {@code Z}. {@code Instant.toString()}
     * drops a zero fraction, and {@code ...T10:00:00Z} then sorts after
     * {@code ...T10:00:00.000001Z} because {@code Z} is greater than {@code .}.
     * Six digits is the precision {@code Booking.createdAt} is truncated to. The
     * consumer re-formats the value as well; see {@code BookingEventHandler.sortKey}.
     */
    private static final DateTimeFormatter WIRE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    public static BookingCreatedEvent from(BookingDto b) {
        return new BookingCreatedEvent(String.valueOf(b.bookingId()), b.flightNumber(),
                                       b.seats(), WIRE_TIME.format(b.createdAt()));
    }
}
