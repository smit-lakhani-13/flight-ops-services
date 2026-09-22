package com.smit.flightops.exception;

/**
 * Internal signal: this request lost a race on its idempotency key, and the winner
 * has committed. It never reaches a client and has no handler in
 * {@code GlobalExceptionHandler}: {@code BookingService.book} catches it and returns
 * the winner's booking with 201, or 409 when the two requests differ.
 *
 * <p>{@code insertNewBooking} throws it from its re-read under the flight row lock,
 * before seats are debited. That ordering makes a replay that races a winner who took
 * the last seats get the booking, not {@code InsufficientSeatsException}.
 *
 * @see com.smit.flightops.service.BookingWriter#insertNewBooking
 */
public class LostIdempotencyRaceException extends RuntimeException {

    private final String idempotencyKey;

    public LostIdempotencyRaceException(String idempotencyKey) {
        super("Idempotency key " + idempotencyKey +
              " was committed by a concurrent request while this one held the flight lock");
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
