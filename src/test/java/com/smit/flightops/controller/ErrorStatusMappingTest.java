package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * How the advice answers a lost database and an unknown flight on the paths
 * {@link FlightControllerTest} leaves out: a dropped connection as well as an
 * empty pool, and the booking endpoints as well as a flight read. The status
 * is the client's cue. A 503 with {@code Retry-After} says to try again, a 404
 * says the number is wrong, and a 500 in place of either says to give up.
 * Filters are off, and {@code TimeConfig} imported, for the reasons
 * {@link FlightControllerTest} gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class ErrorStatusMappingTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    /** A booking endpoint, and the service call behind it that the failure comes out of. */
    private record BookingCall(Supplier<RequestBuilder> request, Function<BookingService, Object> serviceCall) {}

    private static String bookingBody(String flightNumber) {
        return """
                {"flightNumber":"%s","passengerName":"Test Passenger","seats":1,"idempotencyKey":"status-mapping-1"}
                """.formatted(flightNumber);
    }

    /**
     * A connection lost mid-request, which Spring translates from Hibernate's
     * {@code JDBCConnectionException}, and its subclass for a connection that
     * could not be opened at all. {@link FlightControllerTest} throws only
     * {@code CannotCreateTransactionException}, the other type the handler lists.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a connection lost mid-request is 503 DATABASE_UNAVAILABLE with Retry-After, not 500")
    void aDroppedConnectionIsDatabaseUnavailable(RuntimeException failure) throws Exception {
        when(flightService.findByNumber("UA123")).thenThrow(failure);

        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    static Stream<Arguments> aDroppedConnectionIsDatabaseUnavailable() {
        return Stream.of(
                arguments(named("a connection reset",
                                new DataAccessResourceFailureException("connection reset"))),
                arguments(named("no JDBC connection",
                                new CannotGetJdbcConnectionException("no connection"))));
    }

    /**
     * No booking endpoint was checked with the database gone. The log line is a
     * WARN with no stack trace, as doc/api.md says: the fault is the database's,
     * and a trace for every caller would bury the log while it is down.
     */
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a lost database is 503 DATABASE_UNAVAILABLE on every booking endpoint, logged at WARN with no trace")
    void theBookingEndpointsAnswer503WhenTheDatabaseIsGone(BookingCall call, RuntimeException failure,
                                                          CapturedOutput output) throws Exception {
        when(call.serviceCall().apply(bookingService)).thenThrow(failure);

        mockMvc.perform(call.request().get())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        assertThat(output.getAll().lines())
                .anyMatch(line -> line.contains("WARN") && line.contains("Database unavailable"))
                .noneMatch(line -> line.stripLeading().startsWith("at "));
    }

    static Stream<Arguments> theBookingEndpointsAnswer503WhenTheDatabaseIsGone() {
        return Stream.of(
                        named("POST /api/v1/bookings", new BookingCall(
                                () -> post("/api/v1/bookings")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(bookingBody("UA123")),
                                service -> service.book(any()))),
                        named("GET /api/v1/bookings/1", new BookingCall(
                                () -> get("/api/v1/bookings/1"),
                                service -> service.findById(1L))),
                        named("GET /api/v1/bookings?flightNumber=UA123", new BookingCall(
                                () -> get("/api/v1/bookings").param("flightNumber", "UA123"),
                                service -> service.findByFlightNumber(eq("UA123"), any()))),
                        named("DELETE /api/v1/bookings/1", new BookingCall(
                                () -> delete("/api/v1/bookings/1"),
                                service -> service.cancel(1L))))
                .flatMap(call -> Stream.of(
                        arguments(call, named("an empty pool", new CannotCreateTransactionException(
                                "Could not open JPA EntityManager for transaction",
                                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")))),
                        arguments(call, named("a connection reset",
                                new DataAccessResourceFailureException("connection reset")))));
    }

    /**
     * {@link FlightControllerTest} checks this code on a GET only. The advice
     * passes the exception's message through as it is, padding and all, which
     * doc/api.md says it does for this exception.
     */
    @Test
    @DisplayName("booking an unknown flight is 404 FLIGHT_NOT_FOUND, naming the number as sent")
    void anUnknownFlightOnABookingIs404() throws Exception {
        when(bookingService.book(any())).thenThrow(new FlightNotFoundException(" nope9 "));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(" nope9 ")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Flight not found:  nope9 "));
    }
}
