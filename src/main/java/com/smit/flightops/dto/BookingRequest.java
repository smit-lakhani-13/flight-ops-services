package com.smit.flightops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The body of {@code POST /api/v1/bookings}.
 *
 * @param flightNumber matched against {@link CreateFlightRequest#FLIGHT_NUMBER}
 * @param passengerName free text without control characters. {@code \p{Cc}} is the
 *        Unicode category, so it also refuses U+0080 to U+009F, which Java's
 *        {@code \p{Cntrl}} lets through. PostgreSQL refuses NUL in a text column, and
 *        {@code SEP} must not appear in any field
 * @param seats one to nine. Marked required for the OpenAPI document, which
 *        treats a primitive as optional; Jackson refuses a missing one
 * @param idempotencyKey client-generated; the same key twice is the same
 *        booking, never two. It is written to the log on every replay and
 *        echoed in the 409 message, so it takes the character class
 *        {@code RequestIdFilter} enforces on {@code X-Request-Id}
 */
public record BookingRequest(
    @NotBlank @Size(max = 10)
    @Pattern(regexp = CreateFlightRequest.FLIGHT_NUMBER, message = "must contain only letters and digits")
    String flightNumber,

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}]*$", message = "must not contain control characters")
    String passengerName,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) @Max(9) int seats,

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[A-Za-z0-9._:-]+$",
             message = "must contain only letters, digits and . _ : -")
    String idempotencyKey
) {

    /**
     * Field separator for the fingerprint. ASCII 31, the unit separator, chosen
     * because validation keeps it out of all three fields: the flight number is
     * letters and digits, the passenger name refuses control characters, and
     * the seat count is an integer. JSON itself carries U+001F without complaint,
     * so it is the validation, not the encoding, that makes this separator safe. A
     * printable delimiter such as {@code |} would let {@code ("UA1", "23|BOB")}
     * and {@code ("UA1|23", "BOB")} hash to the same value.
     */
    private static final String SEP = "\u001f";

    /**
     * SHA-256 of what this request asks for, excluding the key. Same key and
     * same fingerprint is a retry; same key and a different fingerprint is
     * {@code IdempotencyKeyConflictException}.
     *
     * <p>The flight number is upper-cased because {@code BookingWriter} books the
     * same seat for {@code ua123} and {@code UA123}, so a retry that changed
     * case must hash the same. The name is trimmed so a retry that changed only
     * its padding is the same request; the stored name is the one first sent. All 64
     * hex characters are stored, in {@code bookings.request_fingerprint VARCHAR(64)}.
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
            // The platform specification requires every JVM to ship SHA-256.
            throw new IllegalStateException("SHA-256 unavailable on this JVM", e);
        }
    }
}
