package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.support.MetricsTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.transaction.CannotCreateTransactionException;

import java.io.EOFException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The fixed client messages the advice puts in place of exception, SQL and
 * pool text, and the cap on what it logs instead. The services are mocked so
 * that each test can throw the text a driver, Hibernate or the pool would, and
 * check that none of it reaches the body. Filters are off, and
 * {@code TimeConfig} imported, for the reasons {@link FlightControllerTest}
 * gives.
 */
@WebMvcTest({FlightController.class, BookingController.class})
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class FixedErrorMessagesTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FlightService flightService;
    @MockitoBean private BookingService bookingService;

    private static final String FLIGHT_BODY = """
            {"flightNumber":"FX601","origin":"EWR","destination":"LHR","totalSeats":100,
             "departureTime":"2099-01-01T10:00:00Z"}
            """;

    private static final String BOOKING_BODY = """
            {"flightNumber":"FX602","passengerName":"Test Passenger","seats":1,"idempotencyKey":"fixed-messages-1"}
            """;

    /** The internal text the exceptions below carry. None of it is for a client. */
    private static ResultMatcher leaksNothing() {
        return content().string(allOf(
                not(containsString("uk_flights")), not(containsString("com.smit")),
                not(containsString("canceling")), not(containsString("Hikari")),
                not(containsString("SQL"))));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("an exception nothing maps is 500 INTERNAL_ERROR with a fixed message, and its trace goes to the log")
    void anUnmappedExceptionIsAGeneric500(CapturedOutput output) throws Exception {
        when(flightService.findByNumber("FX601"))
                .thenThrow(new IllegalStateException("secret detail com.smit.flightops.x"));

        mockMvc.perform(get("/api/v1/flights/FX601"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().string(allOf(not(containsString("secret detail")),
                        not(containsString("IllegalStateException")), not(containsString("at com.")))))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(output.getAll().lines())
                .anyMatch(line -> line.contains("ERROR") && line.contains("Unhandled exception"));
        // The trace, not only the exception's text: a frame follows it on a line of its own.
        assertThat(output.getAll()).containsPattern(
                "java\\.lang\\.IllegalStateException: secret detail com\\.smit\\.flightops\\.x\\R\\s+at com\\.smit\\.");
    }

    /** PostgreSQL names the constraint and quotes the key, and Spring adds the statement. */
    @Test
    @DisplayName("losing the flight-number race is 409 with a fixed message, not the constraint or the SQL")
    void aDuplicateFlightRace() throws Exception {
        when(flightService.create(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint "
                + "\"uk_flights_flight_number\" Detail: Key (flight_number)=(FX601) already exists.] "
                + "[insert into flights (flight_number) values (?)]; SQL [insert into flights (flight_number) "
                + "values (?)]; constraint [uk_flights_flight_number]"));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FLIGHT_BODY))
                .andExpect(status().isConflict())
                .andExpect(leaksNothing())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("This request conflicts with an existing record. Please retry."));
    }

    /** Hibernate's text names the entity class and the row's id. */
    @Test
    @DisplayName("a write rejected by @Version is 409 with a fixed message, not the entity")
    void aStaleWrite() throws Exception {
        when(flightService.updateStatus("FX601", FlightStatus.DELAYED)).thenThrow(new OptimisticLockingFailureException(
                "Row was updated or deleted by another transaction (or unsaved-value mapping was incorrect): "
                + "[com.smit.flightops.entity.Flight#1]"));

        mockMvc.perform(patch("/api/v1/flights/FX601/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\"}"))
                .andExpect(status().isConflict())
                .andExpect(leaksNothing())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.message").value("The record changed while you were editing it. Please retry."));
    }

    @Test
    @DisplayName("a booking that times out on the row lock is 503 with a fixed message, not PostgreSQL's")
    void aLockTimeout() throws Exception {
        when(bookingService.book(any())).thenThrow(new PessimisticLockingFailureException(
                "JDBC exception executing SQL [select f1_0.id from flights f1_0 where f1_0.flight_number=? for update]",
                new SQLException("ERROR: canceling statement due to lock timeout", "55P03")));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BOOKING_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(leaksNothing())
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("That flight is busy right now. Please retry."));
    }

    /**
     * The text PgJDBC builds for a deadlock: the server's Detail, Hint and Where
     * each start a line, and the Detail has one line per process. One event is
     * one log line, so a search for it finds all of it, and no line of it can
     * pass for a log line of its own.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("PostgreSQL's multi-line lock message is logged on one line")
    void aMultiLineLockMessageIsLoggedOnOneLine(CapturedOutput output) throws Exception {
        when(bookingService.book(any())).thenThrow(new PessimisticLockingFailureException(
                "JDBC exception executing SQL [select f1_0.id from flights f1_0 where f1_0.flight_number=? for update]",
                new SQLException("""
                        ERROR: deadlock detected
                          Detail: Process 101 waits for ShareLock on transaction 7; blocked by process 102.
                        Process 102 waits for ShareLock on transaction 8; blocked by process 101.
                          Hint: See server log for query details.
                          Where: while locking tuple (0,1) in relation "flights\"""", "40P01")));

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BOOKING_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));

        assertThat(output.getAll().lines().filter(line -> line.contains("Lock acquisition failed:")))
                .singleElement(STRING)
                .endsWith("Lock acquisition failed: ERROR: deadlock detected?  Detail: Process 101 waits for "
                        + "ShareLock on transaction 7; blocked by process 102.?Process 102 waits for ShareLock on "
                        + "transaction 8; blocked by process 101.?  Hint: See server log for query details.?  "
                        + "Where: while locking tuple (0,1) in relation \"flights\"");
        assertThat(output.getAll().lines())
                .noneMatch(line -> line.startsWith("Process 102") || line.stripLeading().startsWith("Where:"));
    }

    @Test
    @DisplayName("an empty connection pool is 503 with a fixed message, not the pool's")
    void anEmptyPool() throws Exception {
        when(flightService.findByNumber("FX601")).thenThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        mockMvc.perform(get("/api/v1/flights/FX601"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(leaksNothing())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("The service cannot reach its database. Please retry."));
    }

    /**
     * Spring, Hibernate, Jackson and the JDK all throw this type, so its text
     * is for the log, and the request is still the client's to fix.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a stray IllegalArgumentException is 400 MALFORMED_REQUEST with a fixed message")
    void aStrayIllegalArgumentIsAFixedBadRequest(CapturedOutput output) throws Exception {
        when(bookingService.findById(1L)).thenThrow(new IllegalArgumentException("internal detail"));

        mockMvc.perform(get("/api/v1/bookings/1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("internal detail"))))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value("The request contained an invalid value."));

        assertThat(output.getAll())
                .contains("Rejected argument", "java.lang.IllegalArgumentException: internal detail");
    }

    /** Spring quotes the id it could not convert, and the id is whatever the client sent. */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a 5000-digit booking id is 400, and the log line quoting it is cut at 1000 characters")
    void aLongRejectedValueIsCappedInTheLog(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + "9".repeat(5000)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertThat(output.getAll().lines().filter(line -> line.contains("Malformed request:")))
                .singleElement(STRING)
                .endsWith("...")
                .doesNotContain("9".repeat(1000));
    }

    /**
     * A dropped socket reaches the handler as a root cause with no message.
     * Logging it must not throw, or the 503 becomes a 500.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a database failure whose root cause has no message is still 503, logged as null")
    void aNullCauseMessageLogsAsNull(CapturedOutput output) throws Exception {
        when(flightService.findByNumber("FX601")).thenThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction", new EOFException()));

        mockMvc.perform(get("/api/v1/flights/FX601"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));

        assertThat(output.getAll()).contains("Database unavailable: null");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a constraint violation with no message is still 409, logged as null")
    void aNullConstraintMessageLogsAsNull(CapturedOutput output) throws Exception {
        when(flightService.create(any())).thenThrow(new DataIntegrityViolationException(null));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FLIGHT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        assertThat(output.getAll()).contains("Constraint violation: null");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a 5000-character constraint message is logged as its first 1000 characters and an ellipsis")
    void aLongConstraintMessageIsCapped(CapturedOutput output) throws Exception {
        when(flightService.create(any())).thenThrow(new DataIntegrityViolationException("x".repeat(5000)));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FLIGHT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));

        assertThat(output.getAll())
                .contains("Constraint violation: " + "x".repeat(1000) + "...")
                .doesNotContain("x".repeat(1001));
    }
}
