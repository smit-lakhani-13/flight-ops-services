package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
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
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which one message a field gets in {@code fieldErrors}: from a class-level rule,
 * from two rules of the same rank, for a value that is missing or null, and for
 * a name carrying both a control character and an unpaired surrogate. Set up as
 * {@link FlightControllerTest} is, for the reasons it gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class FieldErrorRulesTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static final String BOOKINGS = "/api/v1/bookings";
    private static final String FLIGHTS = "/api/v1/flights";
    private static final Instant DEPARTURE = Instant.parse("2099-01-01T10:00:00Z");

    private String bookingBody(String passengerName) {
        return objectMapper.writeValueAsString(
                new BookingRequest("UA123", passengerName, 1, "field-rules-1"));
    }

    private String flightBody(String origin, String destination) {
        return objectMapper.writeValueAsString(
                new CreateFlightRequest("UA999", origin, destination, 100, DEPARTURE));
    }

    /** The validator upper-cases both codes, as {@code FlightService} does before storing them. */
    @ParameterizedTest
    @CsvSource({"EWR, EWR", "ewr, EWR"})
    @DisplayName("a flight from an airport to itself is refused on destination, whatever the case")
    void theSameAirportTwiceIsRefused(String origin, String destination) throws Exception {
        mockMvc.perform(post(FLIGHTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(origin, destination)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.destination").value("must differ from origin"));

        verify(flightService, never()).create(any());
    }

    /**
     * The same route, but each code also breaks its own size or pattern rule.
     * doc/api.md puts both before any other rule, and {@code @DistinctEndpoints}
     * is one of those, so destination gets the message origin gets. For the size
     * row the rank alone decides: by message text the endpoints rule sorts first.
     */
    @ParameterizedTest
    @CsvSource({"E1R, e1r, must contain only letters", "EWRX, ewrx, size must be between 3 and 3"})
    @DisplayName("a code's own size or pattern rule outranks the rule that the endpoints must differ")
    void aFieldsOwnRuleOutranksDistinctEndpoints(String origin, String destination, String message)
            throws Exception {
        mockMvc.perform(post(FLIGHTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(origin, destination)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.origin").value(message))
                .andExpect(jsonPath("$.fieldErrors.destination").value(message));

        verify(flightService, never()).create(any());
    }

    /**
     * NUL and a lone surrogate break two {@code @Pattern} rules, which share a
     * rank, and Hibernate Validator reports the two in no fixed order from one
     * call to the next. The escapes are written into the JSON by hand, because
     * a Java string with a lone surrogate reaches the server as {@code ?}.
     */
    @Test
    @DisplayName("a name that breaks two rules of the same rank gets the same message on every call")
    void aTieBetweenTwoRulesAlwaysGetsTheSameMessage() throws Exception {
        String body = """
                {"flightNumber":"UA123","passengerName":"Test\\u0000\\uD800","seats":1,"idempotencyKey":"field-rules-2"}
                """;

        for (int call = 0; call < 30; call++) {
            mockMvc.perform(post(BOOKINGS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.fieldErrors.passengerName").value("must not contain control characters"));
        }

        verify(bookingService, never()).book(any());
    }

    /**
     * Left out or sent as null, a text field binds to null. {@code @Size},
     * {@code @Pattern} and {@code @DistinctEndpoints} all let null through, so
     * {@code @NotBlank} is the only rule that stops these requests.
     */
    @ParameterizedTest
    @CsvSource({
            "/api/v1/bookings, flightNumber,   left out",
            "/api/v1/bookings, flightNumber,   null",
            "/api/v1/bookings, passengerName,  left out",
            "/api/v1/bookings, passengerName,  null",
            "/api/v1/bookings, idempotencyKey, left out",
            "/api/v1/bookings, idempotencyKey, null",
            "/api/v1/flights,  flightNumber,   left out",
            "/api/v1/flights,  flightNumber,   null",
            "/api/v1/flights,  origin,         left out",
            "/api/v1/flights,  origin,         null",
            "/api/v1/flights,  destination,    left out",
            "/api/v1/flights,  destination,    null"
    })
    @DisplayName("a text field left out or sent as null is blank, and the request goes no further")
    void aMissingOrNullTextFieldIsBlank(String path, String field, String sent) throws Exception {
        ObjectNode body = objectMapper.readValue(
                path.equals(BOOKINGS) ? bookingBody("Test Passenger") : flightBody("EWR", "SFO"),
                ObjectNode.class);
        if (sent.equals("null")) {
            body.putNull(field);
        } else {
            body.remove(field);
        }

        mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors." + field).value("must not be blank"));

        verifyNoInteractions(bookingService, flightService);
    }

    /** Without {@code @NotNull}, the null would reach {@code Flight#updateStatus} and fail there as a 500. */
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"status\":null}"})
    @DisplayName("a status change with no status is 400 VALIDATION_FAILED, named in fieldErrors")
    void aMissingStatusIsRefused(String body) throws Exception {
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.status").value("must not be null"));

        verify(flightService, never()).updateStatus(any(), any());
    }
}
