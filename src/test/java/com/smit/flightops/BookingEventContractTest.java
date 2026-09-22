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
 * <p>This service and the Lambda in {@code lambda/} are separate Maven builds
 * with no shared module, so the agreement between {@link BookingCreatedEvent}
 * and the consumer's {@code BookingEvent} record is checked by nothing. Rename
 * a field on either side and both projects compile, both suites stay green, and
 * the break arrives in production as a consumer writing rows with a null
 * partition key.
 *
 * <p>{@code contracts/booking-created-v1.json} is the agreement written down.
 * This test asserts the service produces it; the consumer's test of the same
 * name asserts the Lambda can read it. Neither imports the other's code — the
 * file is the only thing they share, which is what lets the two deploy
 * independently while still failing fast when one of them moves.
 *
 * <p>A {@code @SpringBootTest} rather than a plain unit test, because the
 * {@link ObjectMapper} has to be the container's. A privately constructed one
 * would serialise the same field names with different conventions the moment
 * anyone configures Jackson, and this test would keep passing while the wire
 * format changed underneath it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BookingEventContractTest {

    @Autowired private ObjectMapper objectMapper;

    /**
     * The build runs from the module root, but a developer running one test
     * from an IDE may not. Trying both keeps the failure "the contract does not
     * match" rather than "file not found", which is a much less interesting
     * thing to debug.
     */
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
     * Both directions, and both matter.
     *
     * <p>A missing field breaks the consumer immediately. An <em>extra</em>
     * field is the more interesting failure: the consumer tolerates it at
     * runtime, so nothing breaks today, and the contract file silently stops
     * describing what is actually on the queue. The next person to write a
     * consumer reads the file, believes it, and is wrong. Asserting set
     * equality rather than "contains" is what keeps the document true.
     */
    @Test
    @DisplayName("the serialised event has exactly the contract's fields — no more, no fewer")
    void theWireFormatMatchesTheContractExactly() throws Exception {
        BookingDto booking = new BookingDto(1L, "UA123", "Smit Lakhani", 3, "demo-1",
                Instant.parse("2026-09-15T10:00:00Z"), null);

        JsonNode produced = objectMapper.readTree(
                objectMapper.writeValueAsString(BookingCreatedEvent.from(booking)));

        assertThat(fieldNames(produced))
                .as("adding or removing a field here is a contract change; update "
                        + "contracts/booking-created-v1.json and read its README on ordering")
                .isEqualTo(fieldNames(contract(objectMapper)));
    }

    /**
     * Names alone are not the contract. {@code seats} arriving as
     * {@code "3"} instead of {@code 3} has the right field name, passes the
     * test above, and fails in the consumer's deserialiser — and
     * {@code bookingId} is deliberately a string here even though it is a
     * {@code Long} in the database, so getting that one backwards is an easy
     * mistake to make in the right direction.
     */
    @Test
    @DisplayName("the JSON types match the contract too, not just the field names")
    void theWireTypesMatchTheContract() throws Exception {
        BookingDto booking = new BookingDto(42L, "UA456", "Smit Lakhani", 2, "demo-2",
                Instant.parse("2026-09-15T10:00:00Z"), null);

        JsonNode produced = objectMapper.readTree(
                objectMapper.writeValueAsString(BookingCreatedEvent.from(booking)));

        assertThat(produced.get("bookingId").isString()).as("bookingId is a string on the wire").isTrue();
        assertThat(produced.get("flightNumber").isString()).isTrue();
        assertThat(produced.get("seats").isInt()).as("seats is a number, not a string").isTrue();
        assertThat(produced.get("timestamp").isString()).isTrue();
    }

    /**
     * The consumer builds a DynamoDB sort key by concatenating this string, so
     * its width is part of the contract even though JSON has no notion of one.
     * {@code Instant.toString()} prints 0, 3, 6 or 9 fractional digits, and
     * strings of different lengths do not sort the way the instants do — a
     * whole second sorts <em>after</em> the microsecond that follows it,
     * because {@code Z} is greater than {@code .}.
     */
    @Test
    @DisplayName("the timestamp is fixed width — six fractional digits and a Z, whatever the instant")
    void theTimestampIsFixedWidth() {
        List<Instant> awkward = List.of(
                Instant.parse("2026-09-15T10:00:00Z"),          // no fractional part at all
                Instant.parse("2026-09-15T10:00:00.000001Z"),   // one microsecond later
                Instant.parse("2026-09-15T10:00:00.100Z"),      // three digits
                Instant.parse("2026-09-15T10:00:00.123456Z"));  // six

        List<String> rendered = awkward.stream()
                .map(at -> BookingCreatedEvent.from(
                        new BookingDto(1L, "UA123", "X", 1, "k", at, null)).timestamp())
                .toList();

        assertThat(rendered).allMatch(t -> t.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}Z"));
        assertThat(rendered.stream().map(String::length).distinct()).hasSize(1);
        // The property that actually matters downstream: string order is instant
        // order. This is the assertion that fails if anyone "simplifies" the
        // formatter back to Instant::toString.
        assertThat(rendered).isSorted();
    }

    /**
     * The contract file has to be a document the producer could actually have
     * emitted. Left unchecked it drifts into an illustration — a rounded
     * timestamp, a placeholder id — and then the consumer's test is passing
     * against something the producer never sends.
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
