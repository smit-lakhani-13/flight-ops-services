package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error contract, end to end, through the real stack.
 *
 * <p>Every case here was previously either a 500 or a wrong success. They are
 * tested against the whole application rather than a {@code @WebMvcTest} slice
 * with a mocked service on purpose: each one is a bug in the seam <em>between</em>
 * layers, and mocking the layer underneath is mocking away the bug. The clearest
 * case is {@code ?sort=nonsense} — the exception comes from Spring Data
 * resolving the property against the entity, inside the repository proxy, which
 * a mocked service never reaches. A slice test could only assert that the advice
 * handles an exception somebody hand-constructed, which proves the advice works
 * and not that the endpoint does.
 *
 * <p>{@code @AutoConfigureMockMvc} is explicit here because Boot 4 no longer
 * implies it from {@code @SpringBootTest}. Without it, {@code MockMvc} simply
 * is not a bean and the context fails to start.
 *
 * <p>Runs on H2 with the seeded demo flights, so no Docker and no PostgreSQL —
 * these assertions are about HTTP status and response shape, not about SQL
 * dialects. The two places where PostgreSQL genuinely behaves differently
 * (the {@code FOR UPDATE} contention and the {@code lock_timeout}) are covered
 * in {@link BookingIntegrationTest} and {@link LockTimeoutTest} respectively.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ErrorContractTest {

    @Autowired private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // 400s that used to be 500s
    // ------------------------------------------------------------------

    @Test
    @DisplayName("?sort=<unknown property> is 400 with the offending name, not 500")
    void unknownSortPropertyIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/flights").param("sort", "deptime"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_SORT_PROPERTY"))
                // The property name is echoed because the client sent it. The
                // rest of the exception's text is not: it names the entity
                // class and lists its properties, which is a free schema dump.
                .andExpect(jsonPath("$.message").value(containsString("deptime")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("Flight"))));
    }

    @Test
    @DisplayName("a known sort property still works, so the fix did not just break sorting")
    void knownSortPropertyStillSorts() throws Exception {
        mockMvc.perform(get("/api/v1/flights").param("sort", "departureTime,desc"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("origin == destination is 400 with a field error, not a 409 from the database")
    void sameOriginAndDestinationIsRejectedAtTheEdge() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ001","origin":"EWR","destination":"EWR",
                                 "totalSeats":100,"departureTime":"2030-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // A class-level constraint's violation has no field by default.
                // The validator re-targets it at `destination` precisely so it
                // lands in this map instead of an empty object.
                .andExpect(jsonPath("$.fieldErrors.destination").exists());
    }

    @Test
    @DisplayName("case differences do not smuggle a same-endpoint route past the validator")
    void lowerCaseOriginIsStillTheSameAirport() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"ZZ002","origin":"ewr","destination":"EWR",
                                 "totalSeats":100,"departureTime":"2030-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.destination").exists());
    }

    // ------------------------------------------------------------------
    // 409s that used to be wrong successes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the same idempotency key with a different payload is 409, not somebody else's booking")
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

        // Same key, different passenger. This used to return 201 and Ada's
        // booking: no seats debited for the booking Grace thought she had made,
        // no error, and a confirmation naming somebody else.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Grace Hopper","seats":2,
                                 "idempotencyKey":"%s"}
                                """.formatted(key)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                // The message must not describe the existing booking: a
                // guessable key would otherwise read out other people's
                // reservations.
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(containsString("Ada"))));
    }

    @Test
    @DisplayName("a genuine retry - same key, same payload - is still 201 with the same booking")
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

        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);
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

        // Lower-case flight number, padded name. Both normalise to the same
        // booking, so this is a retry and not a different request. Comparing
        // the raw strings would have made this a 409 for an identical booking.
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
                                 "totalSeats":50,"departureTime":"2030-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/flights/ZZ100"))
                .andExpect(status().isNoContent());

        // The hole this closes: PATCH back to SCHEDULED made isBookable()
        // answer true again and put a cancelled flight's seats back on sale.
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
                                 "totalSeats":50,"departureTime":"2030-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(delete("/api/v1/flights/ZZ101")).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/flights/ZZ101")).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Cancellation, which is what gives releaseSeats a caller
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling a booking returns its seats, and a retried cancel does not return them twice")
    void cancellationReturnsSeatsExactlyOnce() throws Exception {
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
        org.assertj.core.api.Assertions.assertThat(availableSeats("UA789")).isEqualTo(before - 3);

        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").exists());
        org.assertj.core.api.Assertions.assertThat(availableSeats("UA789")).isEqualTo(before);

        // The retry. Without Booking.cancel's boolean return this would credit
        // three more seats - Flight.releaseSeats clamps at totalSeats, so the
        // clamp would hide it on a full flight and not on this one.
        mockMvc.perform(delete("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(availableSeats("UA789")).isEqualTo(before);
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

        // This is why cancellation is a timestamp and not a DELETE: the row
        // keeps the idempotency key, so the original request replays to the
        // cancelled booking instead of quietly booking a second seat.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.cancelledAt").exists());

        org.assertj.core.api.Assertions.assertThat(availableSeats("UA789")).isEqualTo(afterCancel);
    }

    @Test
    @DisplayName("the booking list is paged")
    void bookingListIsPaged() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "UA123")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(1))
                .andExpect(jsonPath("$.page.number").value(0));
    }

    private int availableSeats(String flightNumber) throws Exception {
        String json = mockMvc.perform(get("/api/v1/flights/" + flightNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return Integer.parseInt(json.replaceAll(".*\"availableSeats\":(\\d+).*", "$1"));
    }
}
