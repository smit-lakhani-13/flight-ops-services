package com.smit.flightops;

import com.jayway.jsonpath.JsonPath;
import com.smit.flightops.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartResolver;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract through the whole application: the real security chain,
 * the real services, and H2 with the seeded demo flights. Most of these cases
 * need a layer a slice would mock, such as the stored fingerprint behind a key
 * conflict or the seat count behind a cancellation. The {@code FOR UPDATE}
 * contention runs on PostgreSQL in {@link BookingIntegrationTest}.
 * {@link LockTimeoutTest} pins the lock-timeout-to-503 mapping on H2, and
 * {@code LockTimeoutPostgresTest} covers PostgreSQL's own {@code lock_timeout}
 * firing in CI.
 *
 * <p>Its own database URL, because a second context on the shared in-memory
 * database would drop and recreate the tables under this one. The caller holds
 * both scopes, named by {@code SecurityConfig}'s constants so that renaming a
 * scope breaks compilation instead of turning every case into a 403.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:errorcontract;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser(authorities = {SecurityConfig.SCOPE_READ, SecurityConfig.SCOPE_WRITE})
class ErrorContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ApplicationContext context;

    // ------------------------------------------------------------------
    // Sorting and paging
    // ------------------------------------------------------------------

    @Test
    @DisplayName("?sort=<unknown property> is 400 with the offending name, not 500")
    void unknownSortPropertyIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/flights").param("sort", "deptime"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SORT_PROPERTY"))
                // The name the caller sent comes back; the entity and its property list do not.
                .andExpect(jsonPath("$.message").value(containsString("deptime")))
                .andExpect(jsonPath("$.message").value(not(containsString("Flight"))));
    }

    @Test
    @DisplayName("?sort=<unknown property> is 400 on the bookings list too, not 500")
    void unknownSortPropertyIsABadRequestOnBookingsAsWell() throws Exception {
        // Bookings sorts inside a declared @Query, where an unchecked property
        // would reach Hibernate's parser rather than Spring Data's property check.
        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "UA123")
                        .param("sort", "deptime"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SORT_PROPERTY"))
                .andExpect(jsonPath("$.message").value(containsString("deptime")))
                .andExpect(jsonPath("$.message").value(not(containsString("Booking"))));
    }

    @Test
    @DisplayName("a property the entity has but the endpoint does not offer is still 400")
    void idempotencyKeyIsNotSortable() throws Exception {
        // An entity property, so an entity-derived check would allow it, and
        // sorting by it reads other callers' keys one comparison at a time.
        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "UA123")
                        .param("sort", "idempotencyKey"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SORT_PROPERTY"));
    }

    /**
     * Half a second apart, so the two orders disagree: as text {@code 08:00:00Z}
     * sorts after {@code 08:00:00.500Z}, which is why the times are parsed.
     */
    @Test
    @DisplayName("sort=departureTime,desc returns the later departure first")
    void knownSortPropertyStillSorts() throws Exception {
        createFlight("ZZ200", "AMS", "CDG", "2099-03-01T08:00:00Z");
        createFlight("ZZ201", "AMS", "CDG", "2099-03-01T08:00:00.500Z");

        String json = mockMvc.perform(get("/api/v1/flights")
                        .param("origin", "AMS")
                        .param("destination", "CDG")
                        .param("sort", "departureTime,desc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> departures = JsonPath.read(json, "$.content[*].departureTime");
        assertThat(departures.stream().map(Instant::parse)).containsExactly(
                Instant.parse("2099-03-01T08:00:00.500Z"),
                Instant.parse("2099-03-01T08:00:00Z"));
    }

    /** Booked smallest first, so the default creation order is the opposite of the one asked for. */
    @Test
    @DisplayName("sort=seats,desc on a flight's bookings returns the largest booking first")
    void knownSortPropertyOnBookingsStillSorts() throws Exception {
        createFlight("ZZ300", "AMS", "FRA", "2099-03-01T08:00:00Z");
        book("ZZ300", "Ada Lovelace", 1, "contract-sort-1");
        book("ZZ300", "Grace Hopper", 3, "contract-sort-2");

        String json = mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "ZZ300")
                        .param("sort", "seats,desc"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(JsonPath.<List<Integer>>read(json, "$.content[*].seats")).containsExactly(3, 1);
    }

    /**
     * Spring Data accepts {@code ignorecase} on any property. The bookings query
     * is a declared {@code @Query}, which wraps the column in {@code lower()}
     * whatever its type, and Hibernate refused that for a number with a 500.
     * The flights search builds a criteria query, which never had the problem;
     * it is here so the two endpoints stay alike.
     */
    @Test
    @DisplayName("ignorecase on a number sorts normally, on a time it answers 200, on a name it still applies")
    void ignoreCaseOnANonTextPropertyIsDropped() throws Exception {
        createFlight("ZZ302", "AMS", "BRU", "2099-03-01T08:00:00Z");
        book("ZZ302", "ada Lovelace", 1, "contract-ic-1");
        book("ZZ302", "Grace Hopper", 3, "contract-ic-2");

        String bySeats = mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "ZZ302")
                        .param("sort", "seats,desc,ignorecase"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<Integer>>read(bySeats, "$.content[*].seats")).containsExactly(3, 1);

        // Case-sensitive, "G" sorts before "a". Ignoring case, "ada" comes first.
        String byName = mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "ZZ302")
                        .param("sort", "passengerName,asc,ignorecase"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(byName, "$.content[*].passengerName"))
                .containsExactly("ada Lovelace", "Grace Hopper");

        // For a time only the status is checked, not the order.
        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "ZZ302")
                        .param("sort", "createdAt,asc,ignorecase"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/flights")
                        .param("sort", "departureTime,desc,ignorecase"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the booking list is paged: two bookings at size=1 are two pages with different rows")
    void bookingListIsPaged() throws Exception {
        createFlight("ZZ301", "AMS", "MUC", "2099-03-01T08:00:00Z");
        book("ZZ301", "Ada Lovelace", 1, "contract-page-1");
        book("ZZ301", "Grace Hopper", 1, "contract-page-2");

        String first = bookingsPage("ZZ301", 0);
        String second = bookingsPage("ZZ301", 1);

        assertThat(JsonPath.<List<Object>>read(first, "$.content")).hasSize(1);
        assertThat(JsonPath.<Integer>read(first, "$.page.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(first, "$.page.totalPages")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(second, "$.content[0].bookingId"))
                .isNotEqualTo(JsonPath.<Integer>read(first, "$.content[0].bookingId"));
    }

    /**
     * Spring Data computes the row offset as an {@code int}, which a page past
     * {@code Integer.MAX_VALUE / size} overflows. At the default size of 20,
     * page 107374182 is the last one that fits.
     */
    @Test
    @DisplayName("a page whose offset overflows an int is 400 MALFORMED_REQUEST on both lists")
    void pagePastTheLastAddressableRowIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/flights")
                        .param("page", "2147483647")
                        .param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(containsString("page * size")));

        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "UA123")
                        .param("page", "2147483647"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(get("/api/v1/flights").param("page", "107374182"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Validation at the edge
    // ------------------------------------------------------------------

    @Test
    @DisplayName("origin == destination is 400 with a field error, not a 409 from the database")
    void sameOriginAndDestinationIsRejectedAtTheEdge() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ001","origin":"EWR","destination":"EWR",
                                 "totalSeats":100,"departureTime":"2099-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // A class-level violation has no field; the validator puts it on
                // destination so that it lands in this map.
                .andExpect(jsonPath("$.fieldErrors.destination").exists());
    }

    @Test
    @DisplayName("case differences do not smuggle a same-endpoint route past the validator")
    void lowerCaseOriginIsStillTheSameAirport() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ002","origin":"ewr","destination":"EWR",
                                 "totalSeats":100,"departureTime":"2099-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.destination").exists());
    }

    @Test
    @DisplayName("an idempotency key carrying a newline is refused at the edge, not logged")
    void idempotencyKeyCannotForgeALogLine() throws Exception {
        // The key is logged on every replay and every conflict, so a newline
        // in it would write a forged second line.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Mallory","seats":1,
                                 "idempotencyKey":"ok-1\\n2026-01-01 INFO Booked 400 seats"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey")
                        .value("must contain only letters, digits and . _ : -"));
    }

    /**
     * The empty airport code breaks {@code @NotBlank} and {@code @Size(min = 3)},
     * "1" breaks {@code @Size} and {@code @Pattern}, and the spaces break
     * {@code @NotBlank} and {@code @Pattern}. Hibernate Validator reports them in
     * no fixed order, so the handler ranks them. The empty key breaks only
     * {@code @NotBlank}, now that its pattern accepts an empty string. The rest of
     * each body changes from call to call.
     */
    @Test
    @DisplayName("a field that breaks two constraints gets the same message on every call")
    void aFieldThatBreaksTwoConstraintsGetsTheSameMessageEveryTime() throws Exception {
        Map<String, String> origins = Map.of("", "must not be blank", "1", "size must be between 3 and 3");
        for (int i = 0; i < 20; i++) {
            for (Map.Entry<String, String> origin : origins.entrySet()) {
                mockMvc.perform(post("/api/v1/flights")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"flightNumber":"ZZ%d","origin":"%s","destination":"LHR",
                                         "totalSeats":%d,"departureTime":"2099-01-01T10:00:00Z"}
                                        """.formatted(i, origin.getKey(), 100 + i)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.fieldErrors.origin").value(origin.getValue()));
            }
            for (String key : List.of("", "   ")) {
                mockMvc.perform(post("/api/v1/bookings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"flightNumber":"UA123","passengerName":"Test Passenger",
                                         "seats":%d,"idempotencyKey":"%s"}
                                        """.formatted(i % 9 + 1, key)))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.fieldErrors.idempotencyKey").value("must not be blank"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Idempotency and flight status
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same idempotency key with a different payload is 409, not someone else's booking")
    void reusingAKeyForADifferentBookingIsAConflict() throws Exception {
        String key = "contract-reuse-1";

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Ada Lovelace","seats":2,
                                 "idempotencyKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passengerName").value("Ada Lovelace"));

        // Same key, different passenger. Replaying Ada's booking here would
        // confirm a booking Grace never got.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Grace Hopper","seats":2,
                                 "idempotencyKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                // Nothing about the existing booking, or a guessed key reads out
                // someone else's reservation.
                .andExpect(jsonPath("$.message").value(not(containsString("Ada"))));
    }

    @Test
    @DisplayName("a real retry - same key, same payload - is still 201 with the same booking")
    void identicalRetryIsStillAReplay() throws Exception {
        String body = """
                {"flightNumber":"UA123","passengerName":"Alan Turing","seats":1,
                 "idempotencyKey":"contract-replay-1"}
                """;

        String first = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("whitespace and case in a retry do not turn it into a conflict")
    void normalisedRetryIsStillAReplay() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA456","passengerName":"Edsger Dijkstra","seats":1,
                                 "idempotencyKey":"contract-normalise-1"}
                                """))
                .andExpect(status().isCreated());

        // Lower-case number and padded name normalise to the same booking, so
        // this is a replay, not a conflict.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ua456","passengerName":"  Edsger Dijkstra  ","seats":1,
                                 "idempotencyKey":"contract-normalise-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passengerName").value("Edsger Dijkstra"));
    }

    @Test
    @DisplayName("a cancelled flight cannot be un-cancelled back into selling seats")
    void cancelledFlightCannotBeRevived() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ100","origin":"BOM","destination":"DEL",
                                 "totalSeats":50,"departureTime":"2099-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/flights/ZZ100"))
                .andExpect(status().isNoContent());

        // Back to SCHEDULED would make isBookable() true and put the seats on sale again.
        mockMvc.perform(patch("/api/v1/flights/ZZ100/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"SCHEDULED"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value(containsString("CANCELLED")));
    }

    @Test
    @DisplayName("cancelling an already-cancelled flight is a no-op, so DELETE stays safe to retry")
    void cancellingTwiceIsNotAConflict() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ101","origin":"BOM","destination":"MAA",
                                 "totalSeats":50,"departureTime":"2099-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/flights/ZZ101")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/flights/ZZ101")).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Booking cancellation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling a booking returns its seats, and a retried cancel does not return them twice")
    void cancellationReturnsSeatsExactlyOnce() throws Exception {
        // One seat stays sold throughout. Flight.releaseSeats clamps at
        // totalSeats, so on an otherwise empty flight a double credit would be
        // clamped away and the last assertion could not fail.
        book("UA789", "Ada Lovelace", 1, "contract-cancel-keep-1");
        int before = availableSeats("UA789");

        String created = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA789","passengerName":"Barbara Liskov","seats":3,
                                 "idempotencyKey":"contract-cancel-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        long bookingId = Long.parseLong(created.replaceAll(".*\"bookingId\":(\\d+).*", "$1"));
        assertThat(availableSeats("UA789")).isEqualTo(before - 3);

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").exists());
        assertThat(availableSeats("UA789")).isEqualTo(before);

        // The retry must not credit the seats again. With one seat still sold,
        // a second credit would leave more seats free than before.
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk());
        assertThat(availableSeats("UA789")).isEqualTo(before);
    }

    @Test
    @DisplayName("replaying the original key after a cancellation returns the cancelled booking, not a new one")
    void replayAfterCancellationDoesNotRebook() throws Exception {
        String body = """
                {"flightNumber":"UA789","passengerName":"Grace Murray","seats":1,
                 "idempotencyKey":"contract-cancel-replay-1"}
                """;

        String created = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long bookingId = Long.parseLong(created.replaceAll(".*\"bookingId\":(\\d+).*", "$1"));

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId)).andExpect(status().isOk());
        int afterCancel = availableSeats("UA789");

        // Cancellation is a timestamp, not a DELETE, so the row keeps its key
        // and the original request replays to it instead of booking again.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.cancelledAt").exists());

        assertThat(availableSeats("UA789")).isEqualTo(afterCancel);
    }

    /** Through DEPARTED either way, because the state machine refuses SCHEDULED to ARRIVED. */
    @ParameterizedTest
    @CsvSource({"DEPARTED, ZZ400", "ARRIVED, ZZ401"})
    @DisplayName("an active booking on a departed or arrived flight is 409 BOOKING_NOT_CANCELLABLE, and nothing changes")
    void cancellingOnAFlownFlightIsRefused(String flightStatus, String flightNumber) throws Exception {
        createFlight(flightNumber, "BOM", "GOI", "2099-01-01T10:00:00Z");
        long bookingId = book(flightNumber, "Ada Lovelace", 2, "contract-flown-" + flightStatus);
        changeStatus(flightNumber, "DEPARTED");
        changeStatus(flightNumber, flightStatus);

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_CANCELLABLE"))
                .andExpect(jsonPath("$.message").value(containsString(flightStatus)));

        // A 409 that still wrote would tell the client nothing changed when it had.
        assertThat(availableSeats(flightNumber)).isEqualTo(48);
        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    @Test
    @DisplayName("a booking cancelled before departure still answers 200 with its original cancelledAt after it")
    void cancellationRepeatedAfterDepartureIsStillANoOp() throws Exception {
        createFlight("ZZ402", "BOM", "GOI", "2099-01-01T10:00:00Z");
        long bookingId = book("ZZ402", "Ada Lovelace", 2, "contract-flown-repeat");

        String cancelled = mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String cancelledAt = JsonPath.read(cancelled, "$.cancelledAt");
        changeStatus("ZZ402", "DEPARTED");

        // Idempotency comes before the status check, so the retry is not refused.
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").value(cancelledAt));
        assertThat(availableSeats("ZZ402")).isEqualTo(50);
    }

    // ------------------------------------------------------------------
    // The envelope
    // ------------------------------------------------------------------

    @Test
    @DisplayName("/error answers in the documented envelope, not Boot's default map")
    void theErrorPathUsesTheDocumentedEnvelope() throws Exception {
        // Boot's own BasicErrorController would answer 500 with
        // {"status":999,"error":"None"}: another shape, and a server fault for
        // a path that is not part of the API.
        mockMvc.perform(get("/error"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("an unmapped path under the API is a 404 in the same envelope")
    void anUnmappedPathIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * The handlers set the error's Content-Type themselves, so a 406 is still
     * JSON. The YAML case matters because a YAML converter is registered:
     * without {@code produces} on the controller it would serve the flight.
     */
    @Test
    @DisplayName("an Accept header the API cannot serve is 406 REQUEST_REJECTED, in JSON")
    void aNonJsonAcceptIsNotAcceptableInJson() throws Exception {
        mockMvc.perform(get("/api/v1/flights/NOPE").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));

        mockMvc.perform(get("/api/v1/flights/UA123").accept(MediaType.APPLICATION_YAML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("REQUEST_REJECTED"));
    }

    /**
     * With a resolver present, {@code DispatcherServlet} parses any multipart
     * Content-Type before routing, and one with no boundary is a 500 on every path,
     * health included. MockMvc never parses a multipart body, so the missing
     * resolver is what this pins.
     */
    @Test
    @DisplayName("multipart parsing is off, so no request body reaches a multipart parser")
    void multipartParsingIsOff() {
        assertThat(context.getBeanNamesForType(MultipartResolver.class)).isEmpty();
    }

    private void createFlight(String flightNumber, String origin, String destination,
                              String departureTime) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"%s","origin":"%s","destination":"%s",
                                 "totalSeats":50,"departureTime":"%s"}
                                """.formatted(flightNumber, origin, destination, departureTime)))
                .andExpect(status().isCreated());
    }

    private long book(String flightNumber, String passengerName, int seats, String key) throws Exception {
        String created = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"%s","passengerName":"%s","seats":%d,
                                 "idempotencyKey":"%s"}
                                """.formatted(flightNumber, passengerName, seats, key)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(created.replaceAll(".*\"bookingId\":(\\d+).*", "$1"));
    }

    private void changeStatus(String flightNumber, String newStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/flights/" + flightNumber + "/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"%s"}""".formatted(newStatus)))
                .andExpect(status().isOk());
    }

    private String bookingsPage(String flightNumber, int page) throws Exception {
        return mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", flightNumber)
                        .param("size", "1")
                        .param("page", Integer.toString(page)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private int availableSeats(String flightNumber) throws Exception {
        String json = mockMvc.perform(get("/api/v1/flights/" + flightNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"availableSeats\":(\\d+).*", "$1"));
    }
}
