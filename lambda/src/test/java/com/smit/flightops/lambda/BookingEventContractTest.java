package com.smit.flightops.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The consumer's half of the {@code BookingCreated} contract. It reads
 * {@code contracts/booking-created-v1.json}, the file the producer's test of the
 * same name asserts it emits, and checks that every field this handler depends
 * on arrives populated and usable. Neither side imports the other's code.
 */
class BookingEventContractTest {

    /** The handler's own mapper, so a change to its configuration is tested here too. */
    private static final ObjectMapper MAPPER = BookingEventHandler.MAPPER;

    /** The build runs from {@code lambda/} and an IDE may run from the repository root. */
    private static String contractJson() throws IOException {
        for (Path candidate : List.of(Path.of("../contracts/booking-created-v1.json"),
                                      Path.of("contracts/booking-created-v1.json"))) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IllegalStateException(
                "contracts/booking-created-v1.json not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("the contract document deserialises into a fully populated BookingEvent")
    void theContractDeserialises() throws Exception {
        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(contractJson(), BookingEventHandler.BookingEvent.class);

        // A rename on the producer side fails in readValue: a missing string in
        // the record's constructor, a missing seats in FAIL_ON_NULL_FOR_PRIMITIVES.
        // A blank string or seats below 1 fails in that constructor too, so
        // these assertions restate what readValue has already checked.
        assertThat(event.bookingId()).as("sort key suffix").isNotBlank();
        assertThat(event.flightNumber()).as("DynamoDB partition key").isNotBlank();
        assertThat(event.seats()).isPositive();
        assertThat(event.timestamp()).as("sort key prefix").isNotBlank();
    }

    @Test
    @DisplayName("the contract's timestamp is parseable into the handler's sort key")
    void theContractProducesAUsableSortKey() throws Exception {
        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(contractJson(), BookingEventHandler.BookingEvent.class);

        String key = BookingEventHandler.sortKey(event);

        assertThat(key).isEqualTo("2026-09-15T10:00:00.000000Z#" + event.bookingId());
        // The '#' at a fixed offset is what makes keys sort in time order.
        assertThat(key.indexOf('#')).isEqualTo(27);
    }

    /**
     * The producer must be able to add a field and deploy before this Lambda
     * does. That rests on one line in the handler's mapper, and this test fails
     * if it goes.
     */
    @Test
    @DisplayName("a field this consumer has never heard of does not break it")
    void anAddedProducerFieldIsTolerated() throws Exception {
        String withNewField = contractJson().trim()
                .replaceFirst("\\}$", ",\"cabinClass\":\"ECONOMY\",\"loyaltyTier\":null}");

        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(withNewField, BookingEventHandler.BookingEvent.class);

        assertThat(event.flightNumber()).isEqualTo("UA123");
        assertThat(event.seats()).isEqualTo(3);
    }

    /** A timestamp that is not an instant cannot make a sort key, so the message must fail. */
    @Test
    @DisplayName("a malformed timestamp is rejected rather than stored under a nonsense key")
    void aMalformedTimestampIsRejected() throws Exception {
        String broken = contractJson().replace("2026-09-15T10:00:00.000000Z", "yesterday");

        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(broken, BookingEventHandler.BookingEvent.class);

        assertThatThrownBy(() -> BookingEventHandler.sortKey(event))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    /**
     * {@code Instant.parse} accepts expanded and negative years, which format
     * with a sign and sort ahead of every other item in the partition.
     */
    @Test
    @DisplayName("a year outside the fixed-width range is rejected, not written under a key that sorts first")
    void anExpandedYearIsRejected() throws Exception {
        for (String outOfRange : List.of("+12026-09-15T10:00:00Z", "-0044-03-15T10:00:00Z")) {
            String odd = contractJson().replace("2026-09-15T10:00:00.000000Z", outOfRange);

            BookingEventHandler.BookingEvent event =
                    MAPPER.readValue(odd, BookingEventHandler.BookingEvent.class);

            assertThatThrownBy(() -> BookingEventHandler.sortKey(event))
                    .as("%s formats to a different width and breaks the ordering guarantee", outOfRange)
                    .isInstanceOf(java.time.DateTimeException.class)
                    .hasMessageContaining("sort-key range");
        }
    }

    /**
     * Pins both ends of the accepted range, so it cannot be narrowed unnoticed.
     * Nothing tries year 999 or 10000, so a small widening would still pass.
     */
    @Test
    @DisplayName("the first and last four-digit years are accepted, and produce equal-width keys")
    void theFourDigitYearBoundariesAreAccepted() throws Exception {
        String first = keyFor("1000-01-01T00:00:00Z");
        String last = keyFor("9999-12-31T23:59:59.999999Z");

        assertThat(first).doesNotStartWith("+").doesNotStartWith("-");
        assertThat(first.indexOf('#'))
                .as("the '#' must sit at the same offset, which is what makes the key sortable")
                .isEqualTo(last.indexOf('#'));
        assertThat(first).isLessThan(last);
    }

    private static String keyFor(String timestamp) throws IOException {
        String json = contractJson().replace("2026-09-15T10:00:00.000000Z", timestamp);
        return BookingEventHandler.sortKey(
                MAPPER.readValue(json, BookingEventHandler.BookingEvent.class));
    }
}
