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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The flight status machine over HTTP: PATCH and DELETE on
 * {@code /api/v1/flights} through the real controller, service and entity on
 * H2, so a rule dropped in {@code FlightService} fails here even when
 * {@code FlightTest} still passes. The refusals assert the stored status as
 * well as the 409, because the harm is a flight left at the wrong status.
 *
 * <p>Its own database URL, for the reason {@link ErrorContractTest} gives.
 * Rows survive each test, so every method uses flight numbers no other method
 * creates. The messages asserted come from the project's own exceptions, which
 * doc/api.md says reach the client unchanged: a refusal names both statuses,
 * as {@code IllegalFlightTransitionException} promises, and a 404 names the
 * number that was sent.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:flightstatuscontract;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@WithMockUser(authorities = {SecurityConfig.SCOPE_READ, SecurityConfig.SCOPE_WRITE})
class FlightStatusContractTest {

    @Autowired private MockMvc mockMvc;

    // ------------------------------------------------------------------
    // Refused moves
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a departed flight cannot go back to BOARDING, and stays unbookable")
    void departedCannotGoBack() throws Exception {
        createFlight("FS001");
        patchStatus("FS001", "DEPARTED").andExpect(status().isOk());

        // BOARDING is bookable, so this move would sell seats on an aircraft in the air.
        patchStatus("FS001", "BOARDING")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value("Flight FS001 cannot go from DEPARTED to BOARDING"));

        assertThat(statusOf("FS001")).isEqualTo("DEPARTED");
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"FS001","passengerName":"Test Passenger","seats":1,
                                 "idempotencyKey":"flight-status-001"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"));
    }

    @Test
    @DisplayName("DELETE on a departed or arrived flight is 409, and the status does not change")
    void aFlownFlightCannotBeCancelled() throws Exception {
        createFlight("FS002");
        patchStatus("FS002", "DEPARTED").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/flights/FS002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value(containsString("cannot go from DEPARTED to CANCELLED")));
        assertThat(statusOf("FS002")).isEqualTo("DEPARTED");

        createFlight("FS003");
        patchStatus("FS003", "DEPARTED").andExpect(status().isOk());
        patchStatus("FS003", "ARRIVED").andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/flights/FS003"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value(containsString("cannot go from ARRIVED to CANCELLED")));
        assertThat(statusOf("FS003")).isEqualTo("ARRIVED");
    }

    // ------------------------------------------------------------------
    // Allowed moves
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"FS004, DELAYED", "FS005, BOARDING"})
    @DisplayName("a DELAYED or BOARDING flight can be cancelled with DELETE")
    void delayedAndBoardingFlightsCanBeCancelled(String flightNumber, String flightStatus) throws Exception {
        createFlight(flightNumber);
        patchStatus(flightNumber, flightStatus).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/flights/{flightNumber}", flightNumber))
                .andExpect(status().isNoContent());

        assertThat(statusOf(flightNumber)).isEqualTo("CANCELLED");
    }

    @ParameterizedTest
    @CsvSource({"FS006, DELAYED", "FS007, BOARDING"})
    @DisplayName("a retried PATCH to the status the flight already has is 200 both times")
    void aRetriedPatchIsNotAConflict(String flightNumber, String flightStatus) throws Exception {
        // The client that timed out cannot tell whether its first PATCH landed.
        createFlight(flightNumber);

        for (int attempt = 1; attempt <= 2; attempt++) {
            patchStatus(flightNumber, flightStatus)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(flightStatus))
                    .andExpect(jsonPath("$.availableSeats").value(50));
        }

        assertThat(statusOf(flightNumber)).isEqualTo(flightStatus);
    }

    // ------------------------------------------------------------------
    // PATCH to CANCELLED: a second way to cancel, bound by the same rules
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH to CANCELLED cancels a scheduled flight and stops its sales")
    void patchToCancelledCancelsTheFlight() throws Exception {
        createFlight("FS008");

        patchStatus("FS008", "CANCELLED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(statusOf("FS008")).isEqualTo("CANCELLED");
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"FS008","passengerName":"Test Passenger","seats":1,
                                 "idempotencyKey":"flight-status-008"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"));
        // Already cancelled, so the DELETE is the documented no-op.
        mockMvc.perform(delete("/api/v1/flights/FS008")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("PATCH to CANCELLED on a departed flight is 409, as DELETE is")
    void patchCannotCancelADepartedFlight() throws Exception {
        createFlight("FS009");
        patchStatus("FS009", "DEPARTED").andExpect(status().isOk());

        patchStatus("FS009", "CANCELLED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_STATUS_TRANSITION"));

        assertThat(statusOf("FS009")).isEqualTo("DEPARTED");
    }

    // ------------------------------------------------------------------
    // Unknown flights
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH on a flight that does not exist is 404 FLIGHT_NOT_FOUND, not 500")
    void patchOnAnUnknownFlightIsNotFound() throws Exception {
        patchStatus("NOPE9", "BOARDING")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Flight not found: NOPE9"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("DELETE on a flight that does not exist is 404, not a 204 for nothing cancelled")
    void deleteOnAnUnknownFlightIsNotFound() throws Exception {
        // A 204 here would tell the caller a mistyped flight had been cancelled.
        mockMvc.perform(delete("/api/v1/flights/NOPE1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(containsString("NOPE1")));
    }

    private void createFlight(String flightNumber) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"%s","origin":"BOM","destination":"DEL",
                                 "totalSeats":50,"departureTime":"2099-01-01T10:00:00Z"}
                                """.formatted(flightNumber)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    private ResultActions patchStatus(String flightNumber, String newStatus) throws Exception {
        return mockMvc.perform(patch("/api/v1/flights/{flightNumber}/status", flightNumber)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"status":"%s"}""".formatted(newStatus)));
    }

    private String statusOf(String flightNumber) throws Exception {
        String json = mockMvc.perform(get("/api/v1/flights/{flightNumber}", flightNumber))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.status");
    }
}
