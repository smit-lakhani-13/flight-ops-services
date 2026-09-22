package com.smit.flightops.exception;

/**
 * The same idempotency key, a different booking.
 *
 * <p>This is the failure the unique constraint cannot see. {@code
 * uk_bookings_idempotency_key} guarantees one booking per key, so a client that
 * reuses key {@code abc-123} for a different passenger, flight or seat count
 * used to get 201 and somebody else's booking back — the wrong answer, with a
 * success status, on a money path. The request looked like a retry because the
 * only thing the service compared was the key.
 *
 * <p>Detected by storing a hash of the request alongside the booking and
 * comparing it on every replay; see {@code BookingRequest.fingerprint}. 409,
 * because the key is in use and the caller has to choose a new one. Not 422:
 * the payload is valid, it is the key that is already spoken for.
 *
 * <p>The message deliberately does not describe the stored booking. Telling an
 * unauthenticated caller "that key belongs to a booking for Ada Lovelace on
 * UA123" turns a guessable key into a way to read other people's reservations.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    private final String idempotencyKey;

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(("Idempotency key %s has already been used for a different booking. "
               + "Use a new key, or resend the original request unchanged.")
                      .formatted(idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
}
