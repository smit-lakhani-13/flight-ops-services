package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The advice's error timestamps against a pinned {@link Clock}. The pinned instant
 * carries microseconds, so a second clock, a {@code LocalDateTime} that drops the
 * {@code Z}, a format cut to milliseconds or dates written as epoch numbers each
 * changes the string.
 *
 * <p>A class of its own, because {@code FlightControllerTest} needs the real clock for
 * {@code @Future}. The slice is set up the same way: no filters, {@code TimeConfig} for
 * the validator's clock, and the metrics the advice takes.
 */
@WebMvcTest(FlightController.class)
@Import({TimeConfig.class, MetricsTestConfig.class, ErrorTimestampTest.PinnedClock.class})
@AutoConfigureMockMvc(addFilters = false)
class ErrorTimestampTest {

    private static final String PINNED = "2026-01-01T00:00:00.123456Z";

    /** Primary, so it wins over TimeConfig's system clock wherever a Clock is injected. */
    @TestConfiguration
    static class PinnedClock {
        @Bean
        @Primary
        Clock pinnedClock() {
            return Clock.fixed(Instant.parse(PINNED), ZoneOffset.UTC);
        }
    }

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;

    @Test
    @DisplayName("an advice error carries the clock's instant as an ISO-8601 UTC string")
    void anAdviceErrorTakesTheClocksTime() throws Exception {
        when(flightService.findByNumber("XX999")).thenThrow(new FlightNotFoundException("XX999"));

        mockMvc.perform(get("/api/v1/flights/XX999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").value(PINNED));
    }

    /**
     * The validation body is its own record, built without ErrorResponse#of. The request
     * is literal JSON, so a date setting on the mapper cannot change what is sent.
     */
    @Test
    @DisplayName("a validation failure carries the same instant in the same format")
    void aValidationFailureTakesTheClocksTime() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"","origin":"EWR","destination":"LHR","totalSeats":100,
                                 "departureTime":"2099-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must not be blank"))
                .andExpect(jsonPath("$.timestamp").value(PINNED));
    }
}
