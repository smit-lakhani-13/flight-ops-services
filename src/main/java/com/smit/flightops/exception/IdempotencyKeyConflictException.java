package com.smit.flightops.exception;

/**
 * The same idempotency key, a different booking. The unique constraint cannot
 * see this, so each booking stores {@code BookingRequest.fingerprint} and every
 * replay compares it. 409 rather than 422: the payload is valid, and the key is
 * what is already spoken for.
 *
 * <p>The message does not describe the stored booking. Telling a caller who
 * merely knows or guesses the key "that key belongs to Ada Lovelace on UA123"
 * would turn the key into a way to read other people's reservations.
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
