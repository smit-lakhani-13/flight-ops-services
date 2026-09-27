package com.smit.flightops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The body of {@code POST /api/v1/bookings}. A field not listed here is refused
 * with 400 {@code MALFORMED_REQUEST}, as {@code additionalProperties: false} tells
 * the OpenAPI document.
 *
 * @param flightNumber matched against {@link CreateFlightRequest#FLIGHT_NUMBER}
 * @param passengerName free text without control characters. {@code \p{Cc}} is the
 *        Unicode category, so it also refuses U+0080 to U+009F, which Java's
 *        {@code \p{Cntrl}} lets through. PostgreSQL refuses NUL in a text column, and
 *        {@code SEP} must not appear in any field. An unpaired surrogate such as
 *        U+D800 is refused too: {@code getBytes(UTF_8)} turns it into {@code ?}, so
 *        two different names would share a fingerprint. A well-formed pair, such as
 *        an emoji, is one code point and passes. {@code @NotBlank} lets through a
 *        name made only of U+00A0 or U+200B, so the third pattern asks for one
 *        character that is neither whitespace nor a format character. It accepts an
 *        empty string, like the key's pattern, and leaves that failure to
 *        {@code @NotBlank}. The text-direction controls, U+202A to U+202E, U+2066
 *        to U+2069, U+200E, U+200F and U+061C, are refused as well: wherever a
 *        client shows the name, such as the console's booking table, they can
 *        reorder the text around them, so a stored name can read as something
 *        else. So are U+2028 and U+2029, which some viewers break a line on.
 *        U+200C and U+200D, the zero-width non-joiner and joiner, pass,
 *        because some scripts and emoji need them. swagger-core publishes a
 *        lone {@code @Pattern} and drops repeated ones, so {@code @Schema}
 *        states the character rule for the OpenAPI document
 * @param seats one to nine. Marked required for the OpenAPI document, which
 *        treats a primitive as optional; Jackson refuses a missing one
 * @param idempotencyKey client-generated; the same key twice is the same
 *        booking, never two. It is written to the log on every replay and
 *        echoed in the 409 message, so it takes the character class
 *        {@code RequestIdFilter} enforces on {@code X-Request-Id}. The pattern
 *        uses {@code *} for the reason {@link CreateFlightRequest} gives
 */
@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record BookingRequest(
    @NotBlank @Size(max = 10)
    @Pattern(regexp = CreateFlightRequest.FLIGHT_NUMBER, message = "must contain only letters and digits")
    String flightNumber,

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[^\\p{Cc}]*$", message = "must not contain control characters")
    @Pattern(regexp = "^\\P{Cs}*$", message = "must not contain unpaired surrogates")
    @Pattern(regexp = "^$|^.*[^\\p{Z}\\p{Cf}\\s].*$", flags = Pattern.Flag.DOTALL,
             message = "must not be blank")
    @Pattern(regexp = "^[^\\u061C\\u200E\\u200F\\u2028-\\u202E\\u2066-\\u2069]*$",
             message = "must not contain text-direction controls or line separators")
    @Schema(pattern = "^[^\\p{Cc}\\p{Cs}\\u061C\\u200E\\u200F\\u2028-\\u202E\\u2066-\\u2069]*$")
    String passengerName,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) @Max(9) int seats,

    @NotBlank @Size(max = 255)
    @Pattern(regexp = "^[A-Za-z0-9._:-]*$",
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
