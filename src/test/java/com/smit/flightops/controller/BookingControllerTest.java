package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.support.MetricsTestConfig;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

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
@WebMvcTest(BookingController.class)
// GlobalExceptionHandler is a @RestControllerAdvice, so the slice picks it
// up, and it takes a Clock. A @WebMvcTest loads no @Configuration class of
// its own, so TimeConfig has to be named here. Importing the real one rather
// than stubbing a fixed clock keeps the slice honest: the error bodies these
// tests assert on are built by the same clock the application uses.
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class BookingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private BookingService bookingService;

    private static final String VALID_BODY = """
            {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":3,"idempotencyKey":"demo-1"}
            """;

    private BookingDto dto() {
        return new BookingDto(1L, "UA123", "Smit Lakhani", 3,
                              Instant.parse("2026-09-15T10:00:00Z"), null);
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
                .andExpect(jsonPath("$.passengerName").value("Smit Lakhani"));
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
    @DisplayName("the list is a page, not a bare array — content plus page metadata")
    void listByFlightReturnsAPageOfBookings() throws Exception {
        when(bookingService.findByFlightNumber(eq("UA123"), any()))
                .thenReturn(new PageImpl<>(List.of(dto()), Pageable.ofSize(20), 1));

        mockMvc.perform(get("/api/v1/bookings").param("flightNumber", "UA123"))
                .andExpect(status().isOk())
                // PagedModel, because spring.data.web.pageable.serialization-mode
                // is via-dto: a stable {content, page} envelope rather than
                // PageImpl's own fields. Asserting the shape here is what would
                // catch that setting being dropped.
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].bookingId").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(1));
    }

    @Test
    @DisplayName("no response body carries the caller's idempotency key")
    void theIdempotencyKeyIsNeverReturned() throws Exception {
        when(bookingService.book(any())).thenReturn(dto());
        when(bookingService.findById(1L)).thenReturn(dto());
        when(bookingService.findByFlightNumber(eq("UA123"), any()))
                .thenReturn(new PageImpl<>(List.of(dto()), Pageable.ofSize(20), 1));

        // All three read paths, because the list is the one that matters most:
        // it returns bookings the caller did not make, so a key echoed there is
        // somebody else's key, and replaying against it is the whole point of
        // the field.
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());

        mockMvc.perform(get("/api/v1/bookings/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist());

        mockMvc.perform(get("/api/v1/bookings").param("flightNumber", "UA123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].idempotencyKey").doesNotExist());
    }

    @Test
    @DisplayName("a page size larger than the cap is clamped to 100, not honoured")
    void pageSizeIsCappedAtOneHundred() throws Exception {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(bookingService.findByFlightNumber(eq("UA123"), any()))
                .thenReturn(new PageImpl<>(List.of(dto()), Pageable.ofSize(20), 1));

        mockMvc.perform(get("/api/v1/bookings")
                        .param("flightNumber", "UA123")
                        .param("size", "5000"))
                .andExpect(status().isOk());

        verify(bookingService).findByFlightNumber(eq("UA123"), pageable.capture());
        // Clamped, not rejected: Spring Data's resolver caps the value and the
        // request still succeeds. Without spring.data.web.pageable.max-page-size
        // this would be 2000 - the framework default, not unlimited, but two
        // thousand rows is still one curl away.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("cancelling a booking is 200 with the cancelled record")
    void cancelReturnsTheCancelledBooking() throws Exception {
        BookingDto cancelled = new BookingDto(1L, "UA123", "Smit Lakhani", 3,
                                              Instant.parse("2026-09-15T10:00:00Z"),
                                              Instant.parse("2026-09-15T11:00:00Z"));
        when(bookingService.cancel(1L)).thenReturn(cancelled);

        mockMvc.perform(delete("/api/v1/bookings/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledAt").value("2026-09-15T11:00:00Z"));
    }

    @Test
    @DisplayName("cancelling an unknown booking is 404")
    void cancelUnknownBookingReturns404() throws Exception {
        when(bookingService.cancel(999L)).thenThrow(new BookingNotFoundException(999L));

        mockMvc.perform(delete("/api/v1/bookings/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
    }

    @Test
    @DisplayName("a missing required query parameter is 400 — Spring's own error, mapped not swallowed")
    void missingQueryParameterReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/bookings"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }
}
