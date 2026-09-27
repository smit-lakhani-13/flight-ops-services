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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

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
 * The upper seat bounds on both writes, from each side of the edge. Bean
 * Validation is the only place they live: neither {@code Flight#reserveSeats}
 * nor the database CHECKs set an upper limit, so without {@code @Max} one
 * booking could take every seat on a flight and a flight could be any size.
 * Set up as {@link FlightControllerTest} is, for the reasons it gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class SeatLimitsTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static final Instant DEPARTURE = Instant.parse("2099-01-01T10:00:00Z");

    private String bookingBody(int seats) {
        return objectMapper.writeValueAsString(
                new BookingRequest("UA123", "Test Passenger", seats, "seat-limit-1"));
    }

    private String flightBody(int totalSeats) {
        return objectMapper.writeValueAsString(
                new CreateFlightRequest("UA999", "EWR", "SFO", totalSeats, DEPARTURE));
    }

    /** 180 is UA123's whole inventory in the seeded demo data. */
    @ParameterizedTest
    @ValueSource(ints = {10, 180, Integer.MAX_VALUE})
    @DisplayName("a booking for more than nine seats is 400 VALIDATION_FAILED and never reaches the service")
    void aBookingAboveNineSeatsIsRefused(int seats) throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(seats)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.seats").value("must be less than or equal to 9"));

        verify(bookingService, never()).book(any());
    }

    @Test
    @DisplayName("nine seats is the largest booking, and it reaches the service unchanged")
    void nineSeatsIsTheLargestBooking() throws Exception {
        ArgumentCaptor<BookingRequest> request = ArgumentCaptor.forClass(BookingRequest.class);
        when(bookingService.book(any())).thenReturn(new BookingDto(1L, "UA123", "Test Passenger", 9,
                                                                   Instant.parse("2026-09-15T10:00:00Z"), null));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(9)))
                .andExpect(status().isCreated());

        verify(bookingService).book(request.capture());
        assertThat(request.getValue().seats()).isEqualTo(9);
    }

    @ParameterizedTest
    @ValueSource(ints = {851, 10000})
    @DisplayName("a flight with more than 850 seats is 400 VALIDATION_FAILED and is never created")
    void aFlightAboveEightHundredFiftySeatsIsRefused(int totalSeats) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(totalSeats)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.totalSeats").value("must be less than or equal to 850"));

        verify(flightService, never()).create(any());
    }

    @Test
    @DisplayName("850 seats is the largest flight, and it reaches the service unchanged")
    void eightHundredFiftySeatsIsTheLargestFlight() throws Exception {
        ArgumentCaptor<CreateFlightRequest> request = ArgumentCaptor.forClass(CreateFlightRequest.class);
        when(flightService.create(any())).thenReturn(
                new FlightDto("UA999", "EWR", "SFO", 850, 850, "SCHEDULED", DEPARTURE));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(850)))
                .andExpect(status().isCreated());

        verify(flightService).create(request.capture());
        assertThat(request.getValue().totalSeats()).isEqualTo(850);
    }
}
