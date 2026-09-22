package com.smit.flightops.exception;

/**
 * Internal signal: this request lost a concurrent race on its idempotency key,
 * and the winner is already committed.
 *
 * <p><b>Not an API error.</b> It never reaches a client and it is deliberately
 * absent from {@code GlobalExceptionHandler}: {@code BookingService.book} is
 * the only thing that can see it, and it answers by recovering the winner's
 * booking and returning 201 — the same answer the winner got. A replay of a
 * request that succeeded is a success.
 *
 * <p>It exists because the alternative was worse. The unique constraint on
 * {@code idempotency_key} catches the same race a moment later, and for most of
 * this service's life that was the whole mechanism. It has one blind spot:
 * {@code insertNewBooking} debits seats before it inserts, so when the winner
 * took the last seats the loser threw {@code InsufficientSeatsException} and
 * the caller got 409 for a booking that had in fact been made. The race that
 * matters is precisely the one on the last seat, so the check moved earlier —
 * under the flight row lock, where the winner is guaranteed committed — and
 * this is how that check reports what it found.
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
