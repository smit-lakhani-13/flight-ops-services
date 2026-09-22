package com.smit.flightops;

import com.smit.flightops.dto.BookingCreatedEvent;
import com.smit.flightops.dto.BookingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The producer's half of the {@code BookingCreated} contract.
 *
 * <p>This service and the Lambda are separate Maven builds with no shared module.
 * A renamed field on either side compiles and passes both suites, then shows up in
 * production as every message failing in the consumer and draining into the DLQ.
 * {@code contracts/booking-created-v1.json} is the agreement: this test asserts the
 * service produces it, and the consumer's test of the same name asserts the Lambda reads it.
 * It is a {@code @SpringBootTest} so the {@link ObjectMapper} is the container's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BookingEventContractTest {

    @Autowired private ObjectMapper objectMapper;

    /** Tries the module root and its parent, since an IDE may run one test from either. */
    private static JsonNode contract(ObjectMapper mapper) throws IOException {
        for (Path candidate : List.of(Path.of("contracts/booking-created-v1.json"),
                                      Path.of("../contracts/booking-created-v1.json"))) {
            if (Files.exists(candidate)) {
                return mapper.readTree(Files.readString(candidate));
            }
        }
        throw new IllegalStateException("contracts/booking-created-v1.json not found from " + Path.of("").toAbsolutePath());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Set equality, both ways. A missing field breaks the consumer at once; an extra
     * one is tolerated at runtime but leaves the contract file describing less than
     * the queue carries.
     */
    @Test
    @DisplayName("the serialised event has the contract's fields, no more and no fewer")
    void theWireFormatMatchesTheContractExactly() throws Exception {
        BookingDto booking = new BookingDto(1L, "UA123", "Smit Lakhani", 3,
                Instant.parse("2026-09-15T10:00:00Z"), null);

        JsonNode produced = objectMapper.readTree(
                objectMapper.writeValueAsString(BookingCreatedEvent.from(booking)));

        assertThat(fieldNames(produced))
                .as("adding or removing a field here is a contract change; update "
                        + "contracts/booking-created-v1.json and read its README on ordering")
                .isEqualTo(fieldNames(contract(objectMapper)));

        // The values too. The 'Z' in WIRE_TIME is a quoted literal, so a zone change
        // to systemDefault() keeps every shape assertion green while each event
        // shifts by the host's offset. Only the whole-document comparison sees it.
        assertThat(produced)
                .as("the serialised event must equal the contract document for the "
                        + "contract's own inputs - a zone, source-field or value change "
                        + "is invisible to every other assertion in this class")
                .isEqualTo(contract(objectMapper));
    }

    /**
     * The zone regression on its own, so the failure names the cause. The build pins
     * the test JVM to Asia/Kolkata through the Surefire argLine, so this bites on a
     * UTC CI runner too.
     */
    @Test
    @DisplayName("the wire timestamp is UTC, not the host's zone")
    void theWireTimestampIsUtcWhateverTheHostZone() {
        Instant noon = Instant.parse("2026-09-15T12:00:00Z");

        String rendered = BookingCreatedEvent.from(
                new BookingDto(1L, "UA123", "X", 1, noon, null)).timestamp();

        assertThat(rendered)
                .as("rendered in the host zone (%s) instead of UTC", java.time.ZoneId.systemDefault())
                .isEqualTo("2026-09-15T12:00:00.000000Z");
    }

    /**
     * {@code seats} as {@code "3"} has the right name and fails in the consumer's
     * deserialiser. {@code bookingId} is a string on the wire though a {@code Long}
     * in the database.
     */
    @Test
    @DisplayName("the JSON types match the contract too, not just the field names")
    void theWireTypesMatchTheContract() throws Exception {
        BookingDto booking = new BookingDto(42L, "UA456", "Smit Lakhani", 2,
                Instant.parse("2026-09-15T10:00:00Z"), null);

        JsonNode produced = objectMapper.readTree(
                objectMapper.writeValueAsString(BookingCreatedEvent.from(booking)));

        assertThat(produced.get("bookingId").isString()).as("bookingId is a string on the wire").isTrue();
        assertThat(produced.get("flightNumber").isString()).isTrue();
        assertThat(produced.get("seats").isInt()).as("seats is a number, not a string").isTrue();
        assertThat(produced.get("timestamp").isString()).isTrue();
    }

    /**
     * The consumer builds a DynamoDB sort key from this string, so its width is part
     * of the contract. {@code Instant.toString()} prints 0, 3, 6 or 9 fractional
     * digits, and a whole second then sorts after the next microsecond because
     * {@code Z} is greater than {@code .}.
     */
    @Test
    @DisplayName("the timestamp is fixed width: six fractional digits and a Z, whatever the instant")
    void theTimestampIsFixedWidth() {
        List<Instant> awkward = List.of(
                Instant.parse("2026-09-15T10:00:00Z"),          // no fractional part at all
                Instant.parse("2026-09-15T10:00:00.000001Z"),   // one microsecond later
                Instant.parse("2026-09-15T10:00:00.100Z"),      // three digits
                Instant.parse("2026-09-15T10:00:00.123456Z"));  // six

        List<String> rendered = awkward.stream()
                .map(at -> BookingCreatedEvent.from(
                        new BookingDto(1L, "UA123", "X", 1, at, null)).timestamp())
                .toList();

        assertThat(rendered).allMatch(t -> t.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"));
        assertThat(rendered.stream().map(String::length).distinct()).hasSize(1);
        // String order is instant order; this fails if the formatter goes back to Instant::toString.
        assertThat(rendered).isSorted();
    }

    /**
     * The contract file must be a document the producer could emit. A rounded
     * timestamp or placeholder id would let the consumer's test pass against
     * something the producer never sends.
     */
    @Test
    @DisplayName("the contract file is itself a document this service could have produced")
    void theContractFileIsRealistic() throws Exception {
        JsonNode contract = contract(objectMapper);

        assertThat(contract.get("timestamp").stringValue())
                .matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z");
        assertThat(contract.get("bookingId").stringValue()).matches("\\d+");
        assertThat(contract.get("seats").intValue()).isPositive();
        assertThat(contract.get("flightNumber").stringValue()).matches("[A-Z0-9]{2,10}");
    }
}
