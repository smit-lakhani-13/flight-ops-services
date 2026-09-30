package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requests that fail before binding finishes: a body Jackson cannot read, an
 * empty or null body, a structured value where the record declares a scalar,
 * a number or a boolean where it declares text, and a booking id that is not a
 * {@code Long}. Each is 400 MALFORMED_REQUEST
 * with the one fixed message doc/api.md promises, because Jackson's text names
 * internal classes and quotes the payload, and Spring's names the handler
 * method. Filters are off, and {@code TimeConfig} imported, for the reasons
 * {@link FlightControllerTest} gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class MalformedRequestTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static final String FIXED_MESSAGE =
            "Request could not be read. Check the field names, types and enum values.";

    private static final String BOOKINGS = "/api/v1/bookings";
    private static final String FLIGHTS = "/api/v1/flights";
    private static final String STATUS = "/api/v1/flights/UA123/status";

    /** Each structured-value case alters one field of these, and the control test reads them as sent. */
    private static final String BOOKING = """
            {"flightNumber":"UA123","passengerName":"Test Passenger","seats":1,"idempotencyKey":"malformed-1"}""";
    private static final String FLIGHT = """
            {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,\
            "departureTime":"2099-01-01T10:00:00Z"}""";

    private static MockHttpServletRequestBuilder json(HttpMethod method, String path, String body) {
        return request(method, path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertMalformed(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(FIXED_MESSAGE))
                .andExpect(content().string(allOf(
                        not(containsString("com.smit")), not(containsString("tools.jackson")),
                        not(containsString("FlightStatus")), not(containsString("java.lang")),
                        not(containsString("Exception")))));

        verifyNoInteractions(flightService, bookingService);
    }

    /**
     * LANDED is a plausible status that does not exist; ARRIVED is the real
     * one. The YAML is read as JSON and fails, because the header, not the
     * body, picks the converter. A real name with padding is not a name:
     * Jackson's enum reader would trim it and apply the status.
     */
    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"LANDED\"}", "{\"status\":2}", "{\"status\":\"BOARDING\"",
                            "status: BOARDING", "{\"status\":\" BOARDING\"}",
                            "{\"status\":\"\\u0000CANCELLED\\t\"}"})
    @DisplayName("an unreadable status change is 400 with the fixed message, not Jackson's text")
    void unreadableStatusBodiesGetTheFixedMessage(String body) throws Exception {
        assertMalformed(json(HttpMethod.PATCH, STATUS, body));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"flightNumber\":\"UA123\",\"passengerName\":\"Test Passenger\",\"seats\":2.5,"
                    + "\"idempotencyKey\":\"malformed-1\"}",
            "{\"flightNumber\":\"UA123\",\"passengerName\":",
            // "Seats" does not fill "seats": names match by case, so the seat count is missing, not 1.
            "{\"flightNumber\":\"UA123\",\"passengerName\":\"Test Passenger\",\"Seats\":1,"
                    + "\"idempotencyKey\":\"malformed-1\"}",
            // The second "seats" is spelt with a JSON escape, so only a check on decoded names sees the repeat.
            "{\"flightNumber\":\"UA123\",\"passengerName\":\"Test Passenger\",\"seats\":1,\"se\\u0061ts\":2,"
                    + "\"idempotencyKey\":\"malformed-1\"}"})
    @DisplayName("an unreadable booking is 400 with the fixed message, not Jackson's text")
    void unreadableBookingBodiesGetTheFixedMessage(String body) throws Exception {
        assertMalformed(json(HttpMethod.POST, BOOKINGS, body));
    }

    /**
     * Spring's text for both is "Required request body is missing:" and the
     * handler's full signature. A non-required body would hand the controller
     * a null instead.
     */
    @ParameterizedTest(name = "{0} {1} with the body [{2}]")
    @CsvSource({
            "PATCH, " + STATUS + ", ''",
            "PATCH, " + STATUS + ", null",
            "POST, " + FLIGHTS + ", ''",
            "POST, " + FLIGHTS + ", null",
            "POST, " + BOOKINGS + ", ''",
            "POST, " + BOOKINGS + ", null"})
    @DisplayName("an empty body or the JSON literal null is 400 with the fixed message on every write")
    void anEmptyOrNullJsonBodyIsMalformed(HttpMethod method, String path, String body) throws Exception {
        assertMalformed(json(method, path, body));
    }

    /** 9223372036854775808 is one past Long.MAX_VALUE, so it matches any digits-only mapping and still fails. */
    @ParameterizedTest(name = "{0} /api/v1/bookings/{1}")
    @CsvSource({"GET, not-a-number", "DELETE, not-a-number",
                "GET, 9223372036854775808", "DELETE, 9223372036854775808"})
    @DisplayName("a booking id that is not a Long is 400 on a read and on a cancellation")
    void aBookingIdThatIsNotALongIsMalformed(HttpMethod method, String id) throws Exception {
        assertMalformed(request(method, BOOKINGS + "/" + id));
    }

    /**
     * Jackson refuses the arrays only while single-value arrays stay wrapped.
     * Unwrapped, ["UA123"] would book UA123 and [1] one seat, where the
     * OpenAPI document refuses a text field sent as an array or an object and
     * a seat count of the wrong JSON type.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("structuredValuesForScalarFields")
    @DisplayName("an array or an object where a field takes a single value is 400 with the fixed message")
    void aStructuredValueForAScalarFieldIsMalformed(HttpMethod method, String path, String body) throws Exception {
        assertMalformed(json(method, path, body));
    }

    static Stream<Arguments> structuredValuesForScalarFields() {
        return Stream.of(
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"UA123\"", "[\"UA123\"]")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"Test Passenger\"", "[\"Test Passenger\"]")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"Test Passenger\"", "{\"first\":\"Test\"}")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"malformed-1\"", "[\"malformed-1\"]")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"seats\":1", "\"seats\":[1]")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"seats\":1", "\"seats\":{}")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"seats\":1", "\"seats\":true")),
                arguments(HttpMethod.POST, BOOKINGS, "[" + BOOKING + "]"),
                arguments(HttpMethod.POST, FLIGHTS, FLIGHT.replace("\"EWR\"", "[\"EWR\"]")),
                arguments(HttpMethod.PATCH, STATUS, "{\"status\":[\"BOARDING\"]}"),
                arguments(HttpMethod.PATCH, STATUS, "{\"status\":{}}"));
    }

    /**
     * Jackson's own reader takes each of these as text: 123 as a valid flight
     * number, 42 as a passenger name, true as an idempotency key. The OpenAPI
     * document types every one of these fields as a string.
     */
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("numbersAndBooleansForTextFields")
    @DisplayName("a number or a boolean where a field takes text is 400 with the fixed message")
    void aNumberOrABooleanForATextFieldIsMalformed(HttpMethod method, String path, String body) throws Exception {
        assertMalformed(json(method, path, body));
    }

    static Stream<Arguments> numbersAndBooleansForTextFields() {
        return Stream.of(
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"UA123\"", "123")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"Test Passenger\"", "42")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"Test Passenger\"", "false")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"malformed-1\"", "true")),
                arguments(HttpMethod.POST, BOOKINGS, BOOKING.replace("\"malformed-1\"", "1.5")),
                arguments(HttpMethod.POST, FLIGHTS, FLIGHT.replace("\"UA999\"", "999")),
                arguments(HttpMethod.POST, FLIGHTS, FLIGHT.replace("\"EWR\"", "-1e3")),
                arguments(HttpMethod.POST, FLIGHTS, FLIGHT.replace("\"SFO\"", "true")));
    }

    /** Without this, a typo in the shared bodies would make every refusal above pass for the wrong reason. */
    @Test
    @DisplayName("the unaltered bodies are read and reach the services")
    void theUnalteredBodiesAreRead() throws Exception {
        Instant departure = Instant.parse("2099-01-01T10:00:00Z");
        when(bookingService.book(any())).thenReturn(new BookingDto(1L, "UA123", "Test Passenger", 1,
                                                                   Instant.parse("2026-09-15T10:00:00Z"), null));
        when(flightService.create(any())).thenReturn(
                new FlightDto("UA999", "EWR", "SFO", 100, 100, "SCHEDULED", departure));
        when(flightService.updateStatus("UA123", FlightStatus.BOARDING)).thenReturn(
                new FlightDto("UA123", "EWR", "SFO", 100, 100, "BOARDING", departure));

        mockMvc.perform(json(HttpMethod.POST, BOOKINGS, BOOKING)).andExpect(status().isCreated());
        mockMvc.perform(json(HttpMethod.POST, FLIGHTS, FLIGHT)).andExpect(status().isCreated());
        mockMvc.perform(json(HttpMethod.PATCH, STATUS, "{\"status\":\"BOARDING\"}")).andExpect(status().isOk());

        verify(bookingService).book(any());
        verify(flightService).create(any());
        verify(flightService).updateStatus("UA123", FlightStatus.BOARDING);
    }
}
