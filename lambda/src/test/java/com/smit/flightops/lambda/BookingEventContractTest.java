package com.smit.flightops.lambda;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
 * The consumer's half of the {@code BookingCreated} contract.
 *
 * <p>Reads {@code contracts/booking-created-v1.json} — the same file the
 * producer's test of this name asserts it emits — and proves this Lambda can
 * actually do something useful with it. Neither test imports the other side's
 * code; the file is the whole of the shared surface, which is what lets these
 * two builds deploy on their own schedules and still find out immediately when
 * one of them moves.
 *
 * <p>This is the consumer-driven half in the literal sense: it is written from
 * the consumer's needs. It does not check that the producer sent something
 * well-formed, it checks that every field this handler actually depends on
 * arrives populated and usable.
 */
class BookingEventContractTest {

    /** Configured exactly as {@code BookingEventHandler.MAPPER} is. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * The build runs from {@code lambda/}, an IDE might run from the repository
     * root. Trying both keeps a failure here meaning "the contract broke"
     * rather than "the path was wrong".
     */
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

        // Every field asserted individually rather than by comparing whole
        // records. A rename on the producer side leaves Jackson with nothing to
        // bind, so the field comes back null (or 0 for the int) and the record
        // still constructs - the failure is silent unless something looks.
        assertThat(event.bookingId()).as("partition key input").isNotBlank();
        assertThat(event.flightNumber()).as("DynamoDB partition key").isNotBlank();
        assertThat(event.seats()).as("0 would mean the field did not bind").isPositive();
        assertThat(event.timestamp()).as("sort key input").isNotBlank();
    }

    @Test
    @DisplayName("the contract's timestamp is parseable into the handler's sort key")
    void theContractProducesAUsableSortKey() throws Exception {
        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(contractJson(), BookingEventHandler.BookingEvent.class);

        String key = BookingEventHandler.sortKey(event);

        assertThat(key).isEqualTo("2026-09-15T10:00:00.000000Z#" + event.bookingId());
        // Fixed width up to the '#'. Two keys of different lengths do not sort
        // the way their instants do, which is the defect this format exists to
        // prevent - see sortKey's Javadoc. The trailing Z is part of it: the
        // separator must land at the same offset for every event ever written.
        assertThat(key.indexOf('#')).isEqualTo(27);
    }

    /**
     * Forward compatibility, pinned rather than assumed.
     *
     * <p>The producer must be able to add a field and deploy without this
     * Lambda being redeployed first — otherwise the queue between them has
     * bought nothing and the two are coupled again. That works only because
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is disabled, which is one line in the
     * handler that reads like tidying up. This test is what makes removing it
     * a red build instead of an outage on the next producer release.
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

    /**
     * The other direction is not tolerated, and should not be. A message whose
     * timestamp is missing or malformed cannot produce a sort key, and the
     * handler lets that throw so the message becomes a batch item failure and
     * ends up in the DLQ. Writing a row with an unsortable key and reporting
     * success would be the quiet version of losing the event.
     */
    @Test
    @DisplayName("a malformed timestamp is rejected rather than stored under a nonsense key")
    void aMalformedTimestampIsRejected() throws Exception {
        String broken = contractJson().replace("2026-09-15T10:00:00.000000Z", "yesterday");

        BookingEventHandler.BookingEvent event =
                MAPPER.readValue(broken, BookingEventHandler.BookingEvent.class);

        assertThatThrownBy(() -> BookingEventHandler.sortKey(event))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }
}
