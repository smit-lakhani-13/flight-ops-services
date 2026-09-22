package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.support.MetricsTestConfig;
import tools.jackson.databind.ObjectMapper;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The flight endpoints' HTTP contract with the service mocked: status codes,
 * headers, JSON bodies and which exception becomes which code.
 *
 * <p>{@code addFilters = false} because a {@code @WebMvcTest} slice does not load
 * {@code SecurityConfig}, and leaving the filters on would test Boot's default
 * chain instead of the application's. {@code SecurityRulesTest} tests the real
 * rules. {@code TimeConfig} is imported because the advice needs a {@code Clock}
 * and a slice loads no {@code @Configuration} of its own.
 */
@WebMvcTest(FlightController.class)
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class FlightControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FlightService flightService;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private FlightDto dto() {
        return new FlightDto("UA123", "EWR", "LHR", 180, 177, "SCHEDULED", DEPARTURE);
    }

    private String createBody(String flightNumber, String origin, String destination) {
        return objectMapper.writeValueAsString(
                new CreateFlightRequest(flightNumber, origin, destination, 100, DEPARTURE));
    }

    @Test
    void getReturns200AndTheFlight() throws Exception {
        when(flightService.findByNumber("UA123")).thenReturn(dto());

        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA123"))
                .andExpect(jsonPath("$.availableSeats").value(177))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("a missing flight is 404 with the FLIGHT_NOT_FOUND code, not a 500")
    void unknownFlightReturns404() throws Exception {
        when(flightService.findByNumber("XX999")).thenThrow(new FlightNotFoundException("XX999"));

        mockMvc.perform(get("/api/v1/flights/XX999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * The service normalises {@code " ua999 "} to {@code UA999}, and only that
     * form resolves, so the test follows the header instead of trusting it.
     */
    @Test
    @DisplayName("create is 201 and its Location header resolves, carrying the normalised number")
    void createReturns201WithAResolvableLocation() throws Exception {
        var created = new FlightDto("UA999", "EWR", "SFO", 200, 200, "SCHEDULED", DEPARTURE);
        when(flightService.create(any())).thenReturn(created);
        when(flightService.findByNumber("UA999")).thenReturn(created);

        String location = mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(" ua999 ", "EWR", "SFO")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/flights/UA999"))
                .andExpect(jsonPath("$.flightNumber").value("UA999"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA999"));
    }

    /**
     * A blank number must report {@code @NotBlank}'s message. The pattern's
     * {@code *} quantifier is what keeps it from failing as well, since the
     * handler keeps only one message per field.
     */
    @Test
    @DisplayName("Bean Validation failures come back as 400 with per-field messages")
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        // Blank number, 4-letter origin, zero seats, departure in the past.
        var request = new CreateFlightRequest("", "EWRX", "LHR", 0,
                                              Instant.now().minus(Duration.ofDays(1)));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.origin").exists())
                .andExpect(jsonPath("$.fieldErrors.totalSeats").exists())
                .andExpect(jsonPath("$.fieldErrors.departureTime").exists());
    }

    /**
     * The number becomes a path segment, where a space, {@code /}, {@code ?} or
     * {@code %} does not survive the round trip cleanly, and the newline would
     * also split the "Created flight" log line.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UA 12", "UA/12", "UA?9", "UA%41", "Q1\nFORGED"})
    @DisplayName("a flight number that is not letters and digits is refused before anything is created")
    void flightNumberMustBeLettersAndDigits(String flightNumber) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(flightNumber, "EWR", "LHR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must contain only letters and digits"));

        verify(flightService, never()).create(any());
    }

    /** {@code @Size} counts the padding and the service trims it, so " JF" would be stored as JF. */
    @Test
    @DisplayName("an airport code must be three letters, not two padded to three")
    void airportCodesMustBeLetters() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ1", " JF", "1@#")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.origin").value("must contain only letters"))
                .andExpect(jsonPath("$.fieldErrors.destination").value("must contain only letters"));

        verify(flightService, never()).create(any());
    }

    /** {@code existsByFlightNumber} cannot stop two concurrent creates, so the unique constraint does. */
    @Test
    @DisplayName("losing the flight-number race is 409 DUPLICATE_REQUEST, not 500")
    void flightNumberRaceReturns409() throws Exception {
        when(flightService.create(any()))
                .thenThrow(new DataIntegrityViolationException("uk_flights_flight_number"));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("UA999", "EWR", "SFO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    @Test
    void patchStatusReturns200() throws Exception {
        when(flightService.updateStatus("UA123", FlightStatus.BOARDING)).thenReturn(dto());

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isOk());

        verify(flightService).updateStatus("UA123", FlightStatus.BOARDING);
    }

    @Test
    @DisplayName("an unknown enum value is 400, not 500 — it fails inside Jackson, before validation")
    void unknownStatusValueReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/flights/UA123"))
                .andExpect(status().isNoContent());

        verify(flightService).cancel("UA123");
    }

    /**
     * Both writes update the flight row that a booking locks with
     * {@code SELECT ... FOR UPDATE}, so they can time out behind one, and the
     * OpenAPI document lists 503 for both.
     */
    @Test
    @DisplayName("a status change or cancellation that times out on the row lock is 503 LOCK_TIMEOUT with Retry-After")
    void flightWriteBehindARowLockReturns503() throws Exception {
        var timeout = new PessimisticLockingFailureException("lock timeout");
        when(flightService.updateStatus("UA123", FlightStatus.DELAYED)).thenThrow(timeout);
        doThrow(timeout).when(flightService).cancel("UA456");

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));

        mockMvc.perform(delete("/api/v1/flights/UA456"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));
    }

    /**
     * POST, because security lets it through to MVC. PUT and OPTIONS never get
     * this far in the real app: {@code denyAll()} answers them with 403, which
     * {@code SecurityRulesTest#unhandledVerbsStayDenied} pins. RFC 9110 requires
     * {@code Allow} on a 405, so the advice copies Spring's headers.
     */
    @Test
    @DisplayName("a verb the path does not map is 405 METHOD_NOT_ALLOWED with an Allow header")
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(post("/api/v1/flights/UA123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", allOf(containsString("GET"), containsString("DELETE"))))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("search returns a PagedModel: content plus a nested page object, no leaked pageable")
    void searchDelegatesWithPagingDefaults() throws Exception {
        when(flightService.search(eq("EWR"), eq("LHR"), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto())));

        mockMvc.perform(get("/api/v1/flights").param("origin", "EWR").param("destination", "LHR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].flightNumber").value("UA123"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    @DisplayName("page and size come off the query string; @PageableDefault fills in the rest")
    void searchAcceptsPagingParameters() throws Exception {
        var pageCaptor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(flightService.search(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/flights").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        verify(flightService).search(eq(null), eq(null), pageCaptor.capture());
        var pageable = pageCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("departureTime")).isNotNull();
    }
}
