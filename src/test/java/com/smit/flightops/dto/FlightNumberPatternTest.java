package com.smit.flightops.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link CreateFlightRequest#FLIGHT_NUMBER} on its own: what it accepts, and
 * that it stays linear on the input that made the earlier form quadratic.
 * Hibernate Validator runs every constraint, so the pattern sees whatever
 * the body limit lets through, whatever {@code @Size} said. Plain JUnit, like
 * {@code BookingFingerprintTest}.
 */
class FlightNumberPatternTest {

    private static final Pattern FLIGHT_NUMBER = Pattern.compile(CreateFlightRequest.FLIGHT_NUMBER);

    /** What {@code \s} matches in {@code java.util.regex} without flags. */
    private static final String PADDING = " \t\n\u000B\f\r";

    /** The rule in words: padding at either end, and only ASCII letters and digits between. */
    private static boolean expected(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && PADDING.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && PADDING.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end).chars()
                .allMatch(c -> c < 128 && Character.isLetterOrDigit(c));
    }

    @Test
    @DisplayName("accepts exactly the padded letters-and-digits strings, over every string up to seven characters")
    void acceptsExactlyThePaddedAlphanumericStrings() {
        char[] alphabet = {' ', '\t', 'a', 'Z', '7', '!', '/'};
        int checked = 0;
        for (int length = 0; length <= 7; length++) {
            int combinations = (int) Math.pow(alphabet.length, length);
            for (int k = 0; k < combinations; k++) {
                StringBuilder value = new StringBuilder();
                for (int i = 0, x = k; i < length; i++, x /= alphabet.length) {
                    value.append(alphabet[x % alphabet.length]);
                }
                String candidate = value.toString();
                assertThat(FLIGHT_NUMBER.matcher(candidate).matches())
                        .as("[%s]", candidate)
                        .isEqualTo(expected(candidate));
                checked++;
            }
        }
        assertThat(checked).isEqualTo(960_800);
    }

    /**
     * 100,000 spaces and a symbol is six times the body limit. The earlier
     * form took about 750 ms on 16,000 and grows with the square, so it would
     * need tens of seconds here; this one needs a few milliseconds.
     */
    @Test
    @DisplayName("refuses a long run of spaces that ends in a symbol in linear time")
    void refusesALongRunOfSpacesQuickly() {
        String value = " ".repeat(100_000) + "!";

        boolean matched = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> FLIGHT_NUMBER.matcher(value).matches());

        assertThat(matched).isFalse();
    }

    @Test
    @DisplayName("accepts a long run of letters with padding in linear time")
    void acceptsALongPaddedRunQuickly() {
        String value = " ".repeat(50_000) + "A".repeat(50_000) + " ".repeat(50_000);

        boolean matched = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> FLIGHT_NUMBER.matcher(value).matches());

        assertThat(matched).isTrue();
    }
}
