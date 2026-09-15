package com.smit.flightops.dto;

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

    public static BookingCreatedEvent from(BookingDto b) {
        return new BookingCreatedEvent(String.valueOf(b.bookingId()), b.flightNumber(),
                                       b.seats(), b.createdAt().toString());
    }
}
