package com.smit.flightops.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fingerprint against fixed values. It is stored with every booking in
 * {@code bookings.request_fingerprint} and compared on every replay, so a
 * release that computes it differently answers every retry of an older key
 * with 409 IDEMPOTENCY_KEY_REUSED. The other idempotency tests compute the
 * stored and the incoming value with the same build, so only a constant can
 * catch that. Plain JUnit, like {@code FlightTest}.
 */
class BookingFingerprintTest {

    /** SHA-256 of the UTF-8 bytes of "UA123", U+001F, "Jane Doe", U+001F, "3", in lower-case hex. */
    private static final String UA123_JANE_DOE_3 =
            "433fe517dde2566e9c338333e59db8803fcc1e7f4b11ab21dbd55fab093fb88e";

    /** The same form for "UI7", with the ASCII capital I rather than U+0130. */
    private static final String UI7_JANE_DOE_3 =
            "00a45a34bd69c46074812313f82fefd8b7bf1e1d3b591bb3081af38e9adc5c74";

    /** The same form for a name with U+00EB, which is two bytes in UTF-8 and one in ISO-8859-1. */
    private static final String UA123_NON_ASCII_NAME_3 =
            "8b5d09fb9e1ec36b9d829075b4adbd8cc9461b673c94a0e598ca208256c02cee";

    /** The same form for "Test Passenger" followed by U+3000, which stays in. */
    private static final String UA123_IDEOGRAPHIC_SPACE_3 =
            "141d820219d47a3ffeefc284d7efac9174aa92e8a352a4fc62bdb382ee962e8a";

    @Test
    @DisplayName("the fingerprint is the SHA-256 of flight, name and seats joined by U+001F")
    void fingerprintIsTheSha256OfTheCanonicalForm() {
        assertThat(new BookingRequest("UA123", "Jane Doe", 3, "demo-1").fingerprint())
                .isEqualTo(UA123_JANE_DOE_3);
    }

    @Test
    @DisplayName("padding, the flight number's case and the key are not part of it")
    void paddingCaseAndKeyDoNotChangeIt() {
        assertThat(new BookingRequest(" ua123 ", " Jane Doe ", 3, "other-key").fingerprint())
                .isEqualTo(UA123_JANE_DOE_3);
    }

    @Test
    @DisplayName("a non-ASCII name is hashed as its UTF-8 bytes")
    void aNonAsciiNameIsHashedAsUtf8() {
        assertThat(new BookingRequest("UA123", "T\u00ebst Passenger", 3, "k").fingerprint())
                .isEqualTo(UA123_NON_ASCII_NAME_3);
    }

    /**
     * {@code trim()} removes only U+0000 to U+0020, so the ideographic space stays
     * in the hashed name. {@code strip()} would remove it and change the value.
     * Validation accepts such a name: U+3000 is not a control character, and the
     * rest of the name is not blank.
     */
    @Test
    @DisplayName("a trailing Unicode space is not trimmed from the name")
    void aTrailingUnicodeSpaceStaysInIt() {
        assertThat(new BookingRequest("UA123", "Test Passenger\u3000", 3, "k").fingerprint())
                .isEqualTo(UA123_IDEOGRAPHIC_SPACE_3);
    }

    /** Against this build's own value, so a field left out fails here and not only above. */
    @Test
    @DisplayName("a different seat count or name is a different fingerprint")
    void theSeatsAndTheNameAreInIt() {
        String threeSeats = new BookingRequest("UA123", "Jane Doe", 3, "demo-1").fingerprint();

        assertThat(new BookingRequest("UA123", "Jane Doe", 4, "demo-1").fingerprint())
                .isNotEqualTo(threeSeats);
        assertThat(new BookingRequest("UA123", "Jane Doex", 3, "demo-1").fingerprint())
                .isNotEqualTo(threeSeats);
    }

    /**
     * Turkish upper-cases {@code i} to U+0130, a dotted capital, so a server
     * whose default locale is tr-TR would store a different value for the same
     * request. No parallel execution is configured, so no other test runs while
     * the default is changed. Each category default is restored on its own, since
     * {@code setDefault(Locale)} overwrites all of them.
     */
    @Test
    @DisplayName("the server's default locale does not change it")
    void theDefaultLocaleDoesNotChangeIt() {
        Locale saved = Locale.getDefault();
        Locale savedFormat = Locale.getDefault(Locale.Category.FORMAT);
        Locale savedDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            assertThat(new BookingRequest("ui7", "Jane Doe", 3, "k").fingerprint())
                    .isEqualTo(UI7_JANE_DOE_3);
        } finally {
            Locale.setDefault(saved);
            Locale.setDefault(Locale.Category.FORMAT, savedFormat);
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay);
        }
    }
}
