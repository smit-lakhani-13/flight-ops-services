package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which {@code departureTime} strings a flight create reads.
 * {@code IsoInstantDeserializer} takes what {@code Instant.parse} takes, an
 * instant with {@code Z} or an offset, up to the end of the year 9999, and
 * refuses the rest with the fixed MALFORMED_REQUEST message. Filters are off,
 * and {@code TimeConfig} imported, for the reasons {@link FlightControllerTest}
 * gives.
 */
@WebMvcTest(FlightController.class)
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class DepartureTimeFormatTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;

    private static final String FIXED_MESSAGE =
            "Request could not be read. Check the field names, types and enum values.";

    private ResultActions createWithDeparture(String departureTime) throws Exception {
        String body = """
                {"flightNumber":"UA916","origin":"EWR","destination":"SFO","totalSeats":100,"departureTime":"%s"}
                """.formatted(departureTime);
        return mockMvc.perform(post("/api/v1/flights")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * A time with no zone is the usual client mistake. Read as UTC or as the
     * server's zone, it would move the departure by the client's offset
     * without a word.
     */
    @ParameterizedTest
    @ValueSource(strings = {"2099-01-01T10:00:00", "2099-01-01", "tomorrow", "2099-01-01 10:00:00Z"})
    @DisplayName("a departure time Instant.parse refuses is 400 MALFORMED_REQUEST, not read as UTC")
    void aTimeInstantParseRefusesIsMalformed(String departureTime) throws Exception {
        createWithDeparture(departureTime)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(FIXED_MESSAGE));

        verify(flightService, never()).create(any());
    }

    /**
     * {@code Instant.parse} reads it, so only the deserialiser's bound refuses
     * it. The WARN line names that bound, the last microsecond of 9999.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("the first instant of the year 10000 is 400 MALFORMED_REQUEST")
    void aTimeAfterYear9999IsMalformed(CapturedOutput output) throws Exception {
        createWithDeparture("+10000-01-01T00:00:00Z")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(FIXED_MESSAGE));

        verify(flightService, never()).create(any());
        assertThat(output.getAll()).contains("no later than 9999-12-31T23:59:59.999999Z");
    }

    /**
     * The bound is the last microsecond of 9999, so a check that refused the
     * bound itself, or one that dropped the fraction, fails the second case.
     */
    @ParameterizedTest
    @ValueSource(strings = {"9999-12-31T23:59:59Z", "9999-12-31T23:59:59.999999Z"})
    @DisplayName("the last second and the last microsecond of the year 9999 are read as sent")
    void theLastInstantOf9999IsAccepted(String departureTime) throws Exception {
        Instant sent = Instant.parse(departureTime);
        when(flightService.create(any())).thenReturn(
                new FlightDto("UA916", "EWR", "SFO", 100, 100, "SCHEDULED", sent));
        var request = ArgumentCaptor.forClass(CreateFlightRequest.class);

        createWithDeparture(departureTime).andExpect(status().isCreated());

        verify(flightService).create(request.capture());
        assertThat(request.getValue().departureTime()).isEqualTo(sent);
    }
}
