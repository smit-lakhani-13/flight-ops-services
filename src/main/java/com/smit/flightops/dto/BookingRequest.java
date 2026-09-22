package com.smit.flightops.dto;

import jakarta.validation.constraints.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public record BookingRequest(
    @NotBlank @Size(max = 10)  String flightNumber,
    @NotBlank @Size(max = 255) String passengerName,
    @Min(1) @Max(9)            int seats,
    /** Client-generated. Same key twice = the same booking, never two. */
    @NotBlank @Size(max = 255) String idempotencyKey
) {

    /**
     * Field separator for the fingerprint below. ASCII 31, the unit separator,
     * chosen because it cannot appear in any of the three fields: a flight
     * number, a passenger name and a seat count all arrive as JSON strings or
     * numbers, and a control character does not survive a JSON encoder that
     * anyone would use. A printable delimiter such as {@code |} would let
     * {@code ("UA1", "23|BOB")} and {@code ("UA1|23", "BOB")} hash to the same
     * value, which is a small but real collision.
     */
    private static final String SEP = "\u001f";

    /**
     * A stable hash of what this request actually asks for, excluding the
     * idempotency key itself.
     *
     * <p>This is what lets the replay path distinguish a genuine retry from key
     * reuse. Two requests with the same key and the same fingerprint are the
     * same request arriving twice, and the second gets the first one's booking.
     * Same key, different fingerprint, and the client has reused a key for a
     * different booking — 409, see {@code IdempotencyKeyConflictException}.
     *
     * <p>Normalisation matters here and is not cosmetic. The flight number is
     * upper-cased because {@code BookingWriter} upper-cases it before looking
     * the flight up, so {@code ua123} and {@code UA123} book the same seat and
     * must therefore hash the same; without this, a client that changed the case
     * of its own retry would get a 409 for an identical booking. The passenger
     * name is trimmed for the same reason in miniature. Seats is an {@code int},
     * so there is nothing to normalise.
     *
     * <p>SHA-256 truncated to nothing — the full 64 hex characters are stored,
     * which is why {@code bookings.request_fingerprint} is {@code VARCHAR(64)}.
     * This is not a security boundary and does not need to be: it is a
     * same-or-different test on data the client just sent us. What it does need
     * is to be collision-resistant enough that two genuinely different bookings
     * never look identical, and SHA-256 is far past that bar.
     */
    public String fingerprint() {
        String canonical = String.join(SEP,
                flightNumber == null ? "" : flightNumber.trim().toUpperCase(Locale.ROOT),
                passengerName == null ? "" : passengerName.trim(),
                Integer.toString(seats));
        return sha256Hex(canonical);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required by the platform specification to ship
            // SHA-256. If this throws, the JVM is broken in a way that a
            // booking service cannot sensibly degrade around.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
