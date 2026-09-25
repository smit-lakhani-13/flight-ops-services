package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.support.MetricsTestConfig;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The booking endpoints' HTTP contract with the service mocked. Filters are off,
 * and {@code TimeConfig} imported, for the reasons {@link FlightControllerTest}
 * gives.
 */
@WebMvcTest(BookingController.class)
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class BookingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private BookingService bookingService;

    private static final String VALID_BODY = """
            {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":3,"idempotencyKey":"demo-1"}
            """;

    private String body(String flightNumber, String passengerName, int seats) {
        return objectMapper.writeValueAsString(
                new BookingRequest(flightNumber, passengerName, seats, "demo-1"));
    }

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
                .andExpect(jsonPath("$.message").value(containsString("UA123")));
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
                // INSUFFICIENT_SEATS would invite a retry with fewer seats, which cannot work here.
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_BOOKABLE"))
                .andExpect(jsonPath("$.message").value(containsString("CANCELLED")));
    }

    /**
     * Blank fields report {@code @NotBlank}'s message: the name and flight-number
     * patterns accept an empty string, so they do not compete for the one message
     * the handler keeps per field.
     */
    @Test
    @DisplayName("blank fields and a zero seat count are 400, each named with its own message")
    void invalidBookingReturns400() throws Exception {
        String body = """
                {"flightNumber":"","passengerName":"","seats":0,"idempotencyKey":""}
                """;

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.passengerName").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.seats").exists())
                .andExpect(jsonPath("$.fieldErrors.idempotencyKey").exists());
    }

    /**
     * PostgreSQL refuses NUL in a text column, which would be a 500, and a
     * newline forges a log line. The trailing newline checks that the pattern
     * is matched against the whole value. U+0085 is a C1 control, which Java's
     * {@code \p{Cntrl}} would let through.
     */
    @ParameterizedTest
    @ValueSource(strings = {"A\u0000B", "Ada\nLovelace", "Ada\n", "Ada\u0085Lovelace"})
    @DisplayName("a passenger name with a control character is refused at the edge")
    void controlCharactersInANameAreRejected(String passengerName) throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("UA123", passengerName, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.passengerName").value("must not contain control characters"));

        verify(bookingService, never()).book(any());
    }

    /**
     * The fingerprint joins the fields with U+001F. If either field could carry
     * it, these two different bookings would hash the same and the second would
     * be replayed as the first.
     */
    @Test
    @DisplayName("neither half of a fingerprint-separator collision gets past validation")
    void theFingerprintSeparatorCannotEnterAField() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("UA123", "X\u001f1", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.passengerName").exists());

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("UA123\u001fX", "1", 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must contain only letters and digits"));

        verify(bookingService, never()).book(any());
    }

    /**
     * {@code spring.jackson.deserialization.accept-float-as-int} is off, so 2.5
     * is refused instead of booking two seats. It goes by how the number is
     * written, so 2.0 and 1e0 are refused as well.
     * {@code spring.jackson.mapper.allow-coercion-of-scalars} is off, so a count
     * sent as a string is refused too. A primitive {@code int} cannot be null, so
     * a missing count fails in Jackson as well, before Bean Validation.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\"seats\":2.5,", "\"seats\":2.0,", "\"seats\":1e0,", "\"seats\":\"2\",",
                            "\"seats\":null,", ""})
    @DisplayName("a seat count with a decimal point or an exponent, a string, null or missing is 400 MALFORMED_REQUEST")
    void seatsMustBeAWholeNumber(String seats) throws Exception {
        String body = """
                {"flightNumber":"UA123","passengerName":"Smit Lakhani",%s"idempotencyKey":"demo-1"}
                """.formatted(seats);

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(bookingService, never()).book(any());
    }

    /** RFC 9110 lets a 415 say what it accepts, and Spring supplies that header. */
    @Test
    @DisplayName("an unsupported Content-Type is 415 with an Accept header naming JSON")
    void unsupportedMediaTypeReturns415WithAccept() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", containsString(MediaType.APPLICATION_JSON_VALUE)))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    /**
     * Spring's own text for this case is "Content-Type 'null' is not supported.",
     * which names nothing the client sent.
     */
    @Test
    @DisplayName("a write with no Content-Type is 415 with a message that says so")
    void missingContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/bookings"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("The request has no Content-Type. Send application/json."));

        mockMvc.perform(post("/api/v1/bookings").content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("The request has no Content-Type. Send application/json."));

        verify(bookingService, never()).book(any());
    }

    /** Spring leaves the media type null here as well, so the handler reads the header instead. */
    @Test
    @DisplayName("an unparseable Content-Type keeps Spring's message")
    void unparseableContentTypeKeepsSpringMessage() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .header("Content-Type", "garbage")
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Could not parse Content-Type."));
    }

    /**
     * A YAML reader sits on the classpath beside Jackson's JSON one, and none of
     * the {@code spring.jackson} settings reach it, so it would book 2 seats for
     * {@code seats: 2.5}. The write accepts JSON only.
     */
    @Test
    @DisplayName("a YAML body is 415 and never reaches the service")
    void yamlBodyReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType("application/yaml")
                        .content("flightNumber: UA123\npassengerName: Ada\nseats: 2.5\nidempotencyKey: k-1\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verify(bookingService, never()).book(any());
    }

    /** Follows the header rather than asserting its text, which is what shows it resolves. */
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
                .andExpect(jsonPath("$.message").value(containsString("999")));
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
                // {content, page}, from serialization-mode: via-dto. This catches that setting being dropped.
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

        // All three paths. The list matters most: it returns bookings the caller
        // did not make, so a key echoed there is someone else's to replay.
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
        // Clamped, not rejected, by spring.data.web.pageable.max-page-size.
        // Without it Spring Data's own cap of 2000 would apply.
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
