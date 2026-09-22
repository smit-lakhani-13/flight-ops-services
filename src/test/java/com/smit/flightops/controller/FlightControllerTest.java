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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web layer only: the service is mocked, so these tests pin down the HTTP
 * contract — status codes, JSON shape, and which exception becomes which code.
 * That contract is what breaks clients, so it deserves its own tests.
 */
/**
 * {@code addFilters = false} — the security filter chain is deliberately out of
 * the way here, and the reason is worth stating because switching filters off
 * in a test usually is a smell.
 *
 * <p>A {@code @WebMvcTest} slice does not load {@code SecurityConfig}: it is a
 * {@code @Configuration} class, not a controller, so the slice filter excludes
 * it. What Boot puts there instead is its own default chain — every request
 * authenticated, CSRF on, form login available. Leaving the filters in place
 * would therefore have every test in this class authenticate against rules
 * that <em>are not the application's rules</em>, and pass. That is worse than
 * no coverage: it reads as though authorisation is tested and it tests a chain
 * that will never run in production.
 *
 * <p>So the split is explicit. This class tests one controller's HTTP contract
 * — status codes, headers, JSON bodies, error mapping. The real rules, against
 * the real {@code SecurityConfig}, with real credentials and the real 401/403
 * bodies, are {@code SecurityRulesTest}'s only job.
 */
@WebMvcTest(FlightController.class)
// GlobalExceptionHandler is a @RestControllerAdvice, so the slice picks it
// up, and it takes a Clock. A @WebMvcTest loads no @Configuration class of
// its own, so TimeConfig has to be named here. Importing the real one rather
// than stubbing a fixed clock keeps the slice honest: the error bodies these
// tests assert on are built by the same clock the application uses.
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
     * The request says {@code " ua999 "}; the service normalises it to
     * {@code UA999}. Location has to carry the normalised form, because that is
     * the only one {@code GET /api/v1/flights/{flightNumber}} resolves — hence
     * the header is built from the returned DTO, not from the request. The test
     * follows the header rather than trusting it.
     */
    @Test
    @DisplayName("create is 201 and its Location header resolves, carrying the normalised number")
    void createReturns201WithAResolvableLocation() throws Exception {
        var request = new CreateFlightRequest(" ua999 ", "EWR", "SFO", 200, DEPARTURE);
        var created = new FlightDto("UA999", "EWR", "SFO", 200, 200, "SCHEDULED", DEPARTURE);
        when(flightService.create(any())).thenReturn(created);
        when(flightService.findByNumber("UA999")).thenReturn(created);

        String location = mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/flights/UA999"))
                .andExpect(jsonPath("$.flightNumber").value("UA999"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA999"));
    }

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
                .andExpect(jsonPath("$.fieldErrors.flightNumber").exists())
                .andExpect(jsonPath("$.fieldErrors.origin").exists())
                .andExpect(jsonPath("$.fieldErrors.totalSeats").exists())
                .andExpect(jsonPath("$.fieldErrors.departureTime").exists());
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

    @Test
    @DisplayName("wrong HTTP method is 405 — proof the catch-all does not swallow Spring's own errors")
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(put("/api/v1/flights/UA123"))
                .andExpect(status().isMethodNotAllowed())
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
