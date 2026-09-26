package com.smit.flightops;

import com.jayway.jsonpath.JsonPath;
import com.smit.flightops.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A flight's bookings on either side of a cancellation, through the whole
 * application on H2. Cancelling a flight and cancelling a booking are separate
 * operations: the first touches no booking and credits no seat, the second still
 * refunds on a cancelled flight, and a cancelled booking keeps its row and its
 * place in the flight's list. The list's flight number is trimmed and upper-cased
 * as doc/api.md says, and H2 compares it case-sensitively, so only that
 * normalisation finds the rows.
 *
 * <p>Its own database URL for the reason {@link ErrorContractTest} gives. Each
 * case books on a flight of its own, so none reads another's rows.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flightbookingscontract;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser(authorities = {SecurityConfig.SCOPE_READ, SecurityConfig.SCOPE_WRITE})
class FlightBookingsContractTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private Clock clock;

    @Test
    @DisplayName("a booking on a cancelled flight can still be cancelled, and its seats come back")
    void aBookingOnACancelledFlightCanStillBeCancelled() throws Exception {
        createFlight("ZZ400");
        long refunded = book("ZZ400", "Test Passenger", 2, "fb-400-a");
        long kept = book("ZZ400", "Jane Doe", 1, "fb-400-b");
        assertThat(availableSeats("ZZ400")).isEqualTo(47);

        mockMvc.perform(delete("/api/v1/flights/ZZ400")).andExpect(status().isNoContent());

        // The refund path. doc/api.md keeps this DELETE open on a cancelled
        // flight: its 409 BOOKING_NOT_CANCELLABLE is only for a flight that has
        // departed or arrived (FlightStatus#acceptsCancellations).
        mockMvc.perform(delete("/api/v1/bookings/" + refunded))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").exists());
        // The kept seat stays sold, so a flight cancellation that had already
        // freed every seat reads 50 here instead of hiding behind the clamp.
        assertThat(availableSeats("ZZ400")).isEqualTo(49);
        mockMvc.perform(get("/api/v1/bookings/" + kept))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
    }

    @Test
    @DisplayName("cancelling a flight credits no seats and leaves its bookings active and listed")
    void cancellingAFlightLeavesItsBookingsAlone() throws Exception {
        createFlight("ZZ402");
        long bookingId = book("ZZ402", "Test Passenger", 3, "fb-402-a");

        mockMvc.perform(delete("/api/v1/flights/ZZ402")).andExpect(status().isNoContent());

        // Seats come back one booking at a time, through the booking's own
        // DELETE and the once-only guard on its cancelledAt.
        mockMvc.perform(get("/api/v1/flights/ZZ402"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.availableSeats").value(47));
        mockMvc.perform(get("/api/v1/bookings/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist());
        list("flightNumber=ZZ402")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(bookingId))
                .andExpect(jsonPath("$.content[0].cancelledAt").doesNotExist());
    }

    @Test
    @DisplayName("a cancelled booking stays in the flight's list and its count, with cancelledAt set")
    void aCancelledBookingStaysListed() throws Exception {
        createFlight("ZZ303");
        long cancelled = book("ZZ303", "Test Passenger", 1, "fb-303-a");
        long active = book("ZZ303", "Jane Doe", 1, "fb-303-b");
        mockMvc.perform(delete("/api/v1/bookings/" + cancelled)).andExpect(status().isOk());

        list("flightNumber=ZZ303&sort=id,asc")
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].bookingId").value(cancelled))
                .andExpect(jsonPath("$.content[0].cancelledAt").exists())
                .andExpect(jsonPath("$.content[1].bookingId").value(active))
                .andExpect(jsonPath("$.content[1].cancelledAt").doesNotExist());

        // Spring Data skips the count query while the first page is not full, so
        // only a full page shows what the count query itself counts.
        list("flightNumber=ZZ303&size=1")
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }

    @Test
    @DisplayName("the list finds a flight's bookings from a lower-case flight number")
    void theListLookupIgnoresCase() throws Exception {
        createFlight("ZZ304");
        book("ZZ304", "Test Passenger", 1, "fb-304-a");

        list("flightNumber=zz304")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].flightNumber").value("ZZ304"));
    }

    @Test
    @DisplayName("the list trims a padded flight number, and an unknown one is an empty page, not a 404")
    void theListLookupIgnoresPaddingAndAnUnknownNumberIsEmpty() throws Exception {
        createFlight("ZZ305");
        book("ZZ305", "Jane Doe", 1, "fb-305-a");

        list("flightNumber=%20zz305%20")
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].flightNumber").value("ZZ305"));

        list("flightNumber=QQ999")
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    private void createFlight(String flightNumber) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"%s","origin":"AMS","destination":"OSL",
                                 "totalSeats":50,"departureTime":"%s"}
                                """.formatted(flightNumber, clock.instant().plus(Duration.ofHours(8)))))
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
        return JsonPath.<Number>read(created, "$.bookingId").longValue();
    }

    private int availableSeats(String flightNumber) throws Exception {
        String json = mockMvc.perform(get("/api/v1/flights/" + flightNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.availableSeats");
    }

    /** A URI, not a template, which would encode the % of an escape a second time. */
    private ResultActions list(String query) throws Exception {
        return mockMvc.perform(get(URI.create("/api/v1/bookings?" + query)))
                .andExpect(status().isOk());
    }
}
