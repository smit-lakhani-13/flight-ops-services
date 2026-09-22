package com.smit.flightops.dto;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The wire contract between this service and the SQS-triggered Lambda in
 * {@code lambda/} — field names here must match
 * {@code BookingEventHandler.BookingEvent} exactly.
 *
 * <p>{@code timestamp} is the booking's persisted {@code createdAt}, never a
 * freshly generated {@code Instant.now()}. The consumer derives its DynamoDB
 * sort key from it, so it must be stable across redeliveries of the same
 * message — that stability is what makes the consumer's conditional write an
 * idempotency check rather than a coin flip.
 */
public record BookingCreatedEvent(String bookingId, String flightNumber, int seats, String timestamp) {

    /**
     * Fixed width: exactly six fractional digits, always {@code Z}.
     *
     * <p>{@code Instant.toString()} was used here and it is not fixed width —
     * it prints the shortest form that round-trips, so a whole second has no
     * fractional part and the next microsecond has six digits. The consumer
     * builds a DynamoDB sort key from this string, and two strings of different
     * lengths do not sort the way the instants do: {@code ...T10:00:00Z} sorts
     * after {@code ...T10:00:00.000001Z}, because {@code Z} is greater than
     * {@code .}. Six digits is the precision {@code Booking.createdAt}
     * truncates to and the precision {@code TIMESTAMP(6)} stores, so nothing is
     * lost by padding.
     *
     * <p>The consumer re-formats what it receives rather than trusting this, so
     * the two fixes are independent — see {@code BookingEventHandler.sortKey}.
     * Both are still worth having: this one keeps the wire format honest for
     * any other consumer, that one keeps the key format the consumer's own
     * business.
     */
    private static final DateTimeFormatter WIRE_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    public static BookingCreatedEvent from(BookingDto b) {
        return new BookingCreatedEvent(String.valueOf(b.bookingId()), b.flightNumber(),
                                       b.seats(), WIRE_TIME.format(b.createdAt()));
    }
}
