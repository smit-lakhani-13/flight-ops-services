package com.smit.flightops.controller;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BookingService bookingService;

    private static final String VALID_BODY = """
            {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":3,"idempotencyKey":"demo-1"}
            """;

    private BookingDto dto() {
        return new BookingDto(1L, "UA123", "Smit Lakhani", 3, "demo-1", Instant.parse("2026-09-15T10:00:00Z"));
    }

    @Test
    @DisplayName("a booking is 201 with a Location header")
    void bookReturns201WithLocation() throws Exception {
        when(bookingService.book(any())).thenReturn(dto());

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/bookings/1"))
                .andExpect(jsonPath("$.bookingId").value(1))
                .andExpect(jsonPath("$.seats").value(3));
    }

    @Test
    @DisplayName("a replay is also 201 — a retry is not a client error")
    void replayIsAlso201() throws Exception {
        when(bookingService.book(any())).thenReturn(dto());

        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(post("/api/v1/bookings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.bookingId").value(1));
        }
    }

    @Test
    void oversellReturns409() throws Exception {
        when(bookingService.book(any())).thenThrow(new InsufficientSeatsException("UA123", 3, 1));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SEATS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UA123")));
    }

    @Test
    @DisplayName("booking a cancelled flight is 409 FLIGHT_NOT_BOOKABLE, distinct from INSUFFICIENT_SEATS")
    void cancelledFlightReturns409() throws Exception {
        when(bookingService.book(any()))
                .thenThrow(new FlightNotBookableException("UA123", FlightStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                // A separate code on purpose: INSUFFICIENT_SEATS invites the
                // client to retry with fewer seats, which can never succeed here.
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CANCELLED")));
    }

    @Test
    @DisplayName("losing the unique-key race is 409 DUPLICATE_REQUEST, not 500")
    void concurrentDuplicateKeyReturns409() throws Exception {
        when(bookingService.book(any()))
                .thenThrow(new DataIntegrityViolationException("uk_bookings_idempotency_key"));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    @Test
    @DisplayName("seats must be at least 1 and the idempotency key is mandatory")
    void invalidBookingReturns400() throws Exception {
        String body = """
                {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":0,"idempotencyKey":""}
                """;

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.seats").exists())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").exists());
    }

    /**
     * The test that would have caught the defect this endpoint was added to fix:
     * it does not assume the Location header is right, it follows it. For most of
     * this project's life POST returned {@code Location: /api/v1/bookings/1}
     * pointing at a URL that answered 404, and nothing failed — every existing
     * test asserted the header's *string value* and stopped there.
     */
    @Test
    @DisplayName("the Location header from a POST actually resolves — followed, not just asserted")
    void locationHeaderResolves() throws Exception {
        when(bookingService.book(any())).thenReturn(dto());
        when(bookingService.findById(1L)).thenReturn(dto());

        String location = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(1))
                .andExpect(jsonPath("$.idempotencyKey").value("demo-1"));
    }

    @Test
    @DisplayName("an unknown booking id is 404 BOOKING_NOT_FOUND, distinct from FLIGHT_NOT_FOUND")
    void unknownBookingReturns404() throws Exception {
        when(bookingService.findById(999L)).thenThrow(new BookingNotFoundException(999L));

        mockMvc.perform(get("/api/v1/bookings/999"))
                .andExpect(status().isNotFound())
                // Its own code, not FLIGHT_NOT_FOUND: a client that gets 404 from
                // a booking URL should not be told the flight is missing.
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("999")));
    }

    @Test
    @DisplayName("a non-numeric booking id is 400, not 500 — it fails in path-variable conversion")
    void nonNumericBookingIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void listByFlightReturnsTheBookings() throws Exception {
        when(bookingService.findByFlightNumber("UA123")).thenReturn(List.of(dto()));

        mockMvc.perform(get("/api/v1/bookings").param("flightNumber", "UA123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].idempotencyKey").value("demo-1"));
    }

    @Test
    @DisplayName("a missing required query parameter is 400 — Spring's own error, mapped not swallowed")
    void missingQueryParameterReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
