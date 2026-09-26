package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The length limits on the text fields of both writes, from each side of the
 * edge. Each {@code @Size} matches its column: the key and the name are
 * VARCHAR(255), the flight number VARCHAR(10) and the airport codes VARCHAR(3).
 * Without the annotation an over-long value fails the insert instead (SQLState
 * 22001 on H2 and PostgreSQL), and the client gets the generic 400
 * MALFORMED_REQUEST from {@code GlobalExceptionHandler#handleDataIntegrity},
 * with no {@code fieldErrors} entry naming the field or its limit. Set up as
 * {@link FlightControllerTest} is, for the reasons it gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class FieldLengthLimitsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static final Instant DEPARTURE = Instant.parse("2099-01-01T10:00:00Z");

    private String bookingBody(String flightNumber, String passengerName, String idempotencyKey) {
        return objectMapper.writeValueAsString(
                new BookingRequest(flightNumber, passengerName, 1, idempotencyKey));
    }

    private String flightBody(String flightNumber, String destination) {
        return objectMapper.writeValueAsString(
                new CreateFlightRequest(flightNumber, "EWR", destination, 100, DEPARTURE));
    }

    private BookingDto booking(String flightNumber) {
        return new BookingDto(1L, flightNumber, "Test Passenger", 1,
                              Instant.parse("2026-09-15T10:00:00Z"), null);
    }

    @Test
    @DisplayName("an idempotency key of 256 characters is 400 VALIDATION_FAILED and never reaches the service")
    void aKeyOver255CharactersIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody("UA123", "Test Passenger", "k".repeat(256))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").value("size must be between 0 and 255"));

        verify(bookingService, never()).book(any());
    }

    @Test
    @DisplayName("an idempotency key of 255 characters is booked, and reaches the service whole")
    void aKeyOf255CharactersIsAccepted() throws Exception {
        String key = "k".repeat(255);
        when(bookingService.book(any())).thenReturn(booking("UA123"));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody("UA123", "Test Passenger", key)))
                .andExpect(status().isCreated());

        verify(bookingService).book(argThat(request -> request.idempotencyKey().equals(key)));
    }

    /**
     * {@code @Size} counts UTF-16 units, and each of these is one. A no-break or
     * zero-width space passes {@code @NotBlank}, so its blank message comes from
     * a pattern, which ranks below size: api.md promises such a name past 255
     * characters the size message. Plain spaces are left out, because
     * {@code @NotBlank} catches them and outranks size.
     */
    @ParameterizedTest
    @ValueSource(strings = {"a", "\u00A0", "\u200B"})
    @DisplayName("a passenger name of 256 units gets the size message, even an invisible one")
    void aNameOver255UnitsGetsTheSizeMessage(String unit) throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody("UA123", unit.repeat(256), "name-limit-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.passengerName").value("size must be between 0 and 255"));

        verify(bookingService, never()).book(any());
    }

    @Test
    @DisplayName("a passenger name of 255 characters is booked, and reaches the service whole")
    void aNameOf255IsAccepted() throws Exception {
        String name = "a".repeat(255);
        when(bookingService.book(any())).thenReturn(booking("UA123"));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody("UA123", name, "name-limit-2")))
                .andExpect(status().isCreated());

        verify(bookingService).book(argThat(request -> request.passengerName().equals(name)));
    }

    /**
     * The pattern allows padding, which the service trims, but {@code @Size}
     * counts it, so the padded number is eleven characters as sent.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UA12345678X", " UA1234567 "})
    @DisplayName("an eleven character flight number is 400 VALIDATION_FAILED on both writes")
    void anElevenCharacterFlightNumberIsRefused(String flightNumber) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(flightNumber, "LHR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("size must be between 0 and 10"));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(flightNumber, "Test Passenger", "number-limit-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("size must be between 0 and 10"));

        verify(flightService, never()).create(any());
        verify(bookingService, never()).book(any());
    }

    @Test
    @DisplayName("a ten character flight number is accepted on both writes")
    void aTenCharacterFlightNumberIsAccepted() throws Exception {
        String flightNumber = "UA12345678";
        when(flightService.create(any())).thenReturn(
                new FlightDto(flightNumber, "EWR", "LHR", 100, 100, "SCHEDULED", DEPARTURE));
        when(bookingService.book(any())).thenReturn(booking(flightNumber));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(flightNumber, "LHR")))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(flightNumber, "Test Passenger", "number-limit-2")))
                .andExpect(status().isCreated());

        verify(flightService).create(argThat(request -> request.flightNumber().equals(flightNumber)));
        verify(bookingService).book(argThat(request -> request.flightNumber().equals(flightNumber)));
    }

    /**
     * Only a destination that breaks the pattern was tested before. Blank ranks
     * above size, so the empty code gets the blank message api.md names.
     */
    @ParameterizedTest
    @CsvSource({
            "'',   must not be blank",
            "LHRX, size must be between 3 and 3",
            "LH,   size must be between 3 and 3",
            "L1R,  must contain only letters"
    })
    @DisplayName("a destination is refused on its own blank, length and letters rules")
    void destinationHasItsOwnRules(String destination, String message) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody("ZZ1", destination)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.destination").value(message));

        verify(flightService, never()).create(any());
    }
}
