package com.smit.flightops.controller;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.support.MetricsTestConfig;
import tools.jackson.databind.ObjectMapper;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.DuplicateFlightException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.data.core.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * The flight endpoints' HTTP contract with the service mocked: status codes,
 * headers, JSON bodies and which exception becomes which code.
 *
 * <p>{@code addFilters = false} because a {@code @WebMvcTest} slice does not load
 * {@code SecurityConfig}, and leaving the filters on would test Boot's default
 * chain instead of the application's. {@code SecurityRulesTest} tests the real
 * rules. {@code TimeConfig} is imported because the advice needs a {@code Clock}
 * and a slice loads no {@code @Configuration} of its own.
 */
@WebMvcTest(FlightController.class)
@Import({TimeConfig.class, MetricsTestConfig.class})
@AutoConfigureMockMvc(addFilters = false)
class FlightControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FlightService flightService;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private FlightDto dto() {
        return new FlightDto("UA123", "EWR", "LHR", 180, 177, "SCHEDULED", DEPARTURE);
    }

    private String createBody(String flightNumber, String origin, String destination) {
        return objectMapper.writeValueAsString(
                new CreateFlightRequest(flightNumber, origin, destination, 100, DEPARTURE));
    }

    @Test
    void getReturns200AndTheFlight() throws Exception {
        when(flightService.findByNumber("UA123")).thenReturn(dto());

        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA123"))
                .andExpect(jsonPath("$.availableSeats").value(177))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("a missing flight is 404 with the FLIGHT_NOT_FOUND code, not a 500")
    void unknownFlightReturns404() throws Exception {
        when(flightService.findByNumber("XX999")).thenThrow(new FlightNotFoundException("XX999"));

        mockMvc.perform(get("/api/v1/flights/XX999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * The service normalises {@code " ua999 "} to {@code UA999}, and only that
     * form resolves, so the test follows the header instead of trusting it.
     */
    @Test
    @DisplayName("create is 201 and its Location header resolves, carrying the normalised number")
    void createReturns201WithAResolvableLocation() throws Exception {
        var created = new FlightDto("UA999", "EWR", "SFO", 200, 200, "SCHEDULED", DEPARTURE);
        when(flightService.create(any())).thenReturn(created);
        when(flightService.findByNumber("UA999")).thenReturn(created);

        String location = mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(" ua999 ", "EWR", "SFO")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/flights/UA999"))
                .andExpect(jsonPath("$.flightNumber").value("UA999"))
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA999"));
    }

    /**
     * A blank number must report {@code @NotBlank}'s message. The pattern's
     * {@code *} quantifier is what keeps it from failing as well, since the
     * handler keeps only one message per field.
     */
    @Test
    @DisplayName("Bean Validation failures come back as 400 with per-field messages")
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        // Blank number, 4-letter origin, zero seats, departure in the past.
        var request = new CreateFlightRequest("", "EWRX", "LHR", 0,
                                              Instant.now().minus(Duration.ofDays(1)));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must not be blank"))
                .andExpect(jsonPath("$.fieldErrors.origin").exists())
                .andExpect(jsonPath("$.fieldErrors.totalSeats").exists())
                .andExpect(jsonPath("$.fieldErrors.departureTime").exists());
    }

    /**
     * The number becomes a path segment, where a space, {@code /}, {@code ?} or
     * {@code %} does not survive the round trip cleanly, and the newline would
     * also split the "Created flight" log line.
     */
    @ParameterizedTest
    @ValueSource(strings = {"UA 12", "UA/12", "UA?9", "UA%41", "Q1\nFORGED"})
    @DisplayName("a flight number that is not letters and digits is refused before anything is created")
    void flightNumberMustBeLettersAndDigits(String flightNumber) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(flightNumber, "EWR", "LHR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.flightNumber").value("must contain only letters and digits"));

        verify(flightService, never()).create(any());
    }

    /** {@code @Size} counts the padding and the service trims it, so " JF" would be stored as JF. */
    @Test
    @DisplayName("an airport code must be three letters, not two padded to three")
    void airportCodesMustBeLetters() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("ZZ1", " JF", "1@#")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.origin").value("must contain only letters"))
                .andExpect(jsonPath("$.fieldErrors.destination").value("must contain only letters"));

        verify(flightService, never()).create(any());
    }

    /** {@code existsByFlightNumber} cannot stop two concurrent creates, so the unique constraint does. */
    @Test
    @DisplayName("losing the flight-number race is 409 DUPLICATE_REQUEST, not 500")
    void flightNumberRaceReturns409() throws Exception {
        when(flightService.create(any()))
                .thenThrow(new DataIntegrityViolationException("uk_flights_flight_number"));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("UA999", "EWR", "SFO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    /** The ordinary case, caught by {@code existsByFlightNumber} before the insert. */
    @Test
    @DisplayName("creating a flight number that already exists is 409 DUPLICATE_FLIGHT")
    void existingFlightNumberReturns409() throws Exception {
        when(flightService.create(any())).thenThrow(new DuplicateFlightException("UA999"));

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("UA999", "EWR", "SFO")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FLIGHT"));
    }

    /** Both writes accept JSON only, for the reason {@code BookingControllerTest#yamlBodyReturns415} gives. */
    @Test
    @DisplayName("a YAML body is 415 on both flight writes")
    void yamlBodyReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType("application/yaml")
                        .content("flightNumber: UA999\norigin: EWR\ndestination: SFO\ntotalSeats: 100.7\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType("application/yaml")
                        .content("status: BOARDING\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verify(flightService, never()).create(any());
        verify(flightService, never()).updateStatus(any(), any());
    }

    @Test
    @DisplayName("a flight write with no Content-Type is 415 with a message that says so")
    void missingContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/flights"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.message").value("The request has no Content-Type. Send application/json."));
        mockMvc.perform(patch("/api/v1/flights/UA123/status").content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("The request has no Content-Type. Send application/json."));

        verify(flightService, never()).create(any());
        verify(flightService, never()).updateStatus(any(), any());
    }

    @Test
    void patchStatusReturns200() throws Exception {
        when(flightService.updateStatus("UA123", FlightStatus.BOARDING)).thenReturn(dto());

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isOk());

        verify(flightService).updateStatus("UA123", FlightStatus.BOARDING);
    }

    @Test
    @DisplayName("an unknown enum value is 400, not 500 — it fails inside Jackson, before validation")
    void unknownStatusValueReturns400() throws Exception {
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TELEPORTED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /**
     * Jackson reads a number as the enum constant at that position, so without
     * {@code fail-on-numbers-for-enums} a status of 4 would cancel the flight.
     */
    @ParameterizedTest
    @ValueSource(strings = {"4", "\"4\""})
    @DisplayName("a status sent as a number is 400, not the constant at that position")
    void statusAsANumberReturns400(String status) throws Exception {
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":" + status + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(flightService, never()).updateStatus(any(), any());
    }

    /**
     * Jackson reads a number as epoch seconds, so epoch milliseconds would be a
     * departure in the year 58971 that {@code @Future} accepts.
     */
    @ParameterizedTest
    @ValueSource(strings = {"1798797600000", "\"1798797600000\"", "1798797600.5"})
    @DisplayName("a departure time sent as a number is 400; only an ISO-8601 string is read")
    void departureTimeMustBeAnIsoString(String departureTime) throws Exception {
        String body = """
                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,"departureTime":%s}
                """.formatted(departureTime);

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(flightService, never()).create(any());
    }

    /**
     * {@code @NotNull} answers these, not Jackson, so they are a
     * {@code VALIDATION_FAILED} that names the field.
     */
    @ParameterizedTest
    @ValueSource(strings = {"", ",\"departureTime\":null"})
    @DisplayName("a missing or null departure time is 400 VALIDATION_FAILED, named in fieldErrors")
    void missingDepartureTimeFailsValidation(String departureTime) throws Exception {
        String body = """
                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100%s}
                """.formatted(departureTime);

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.departureTime").value("must not be null"));

        verify(flightService, never()).create(any());
    }

    /**
     * Jackson quotes the rejected value in its message, and the handler logs
     * that message. A newline in the value would start a line of its own.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("a rejected value with a newline in it stays on one log line")
    void rejectedValueCannotForgeALogLine(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,
                                 "departureTime":"x\\nFORGED"}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"x\\nFORGED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/flights").param("sort", "x\nFORGED"))
                .andExpect(status().isBadRequest());
        // PostgreSQL's constraint detail and Spring Data's property name carry client text too.
        when(flightService.create(any())).thenThrow(new DataIntegrityViolationException("x\nFORGED"));
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,
                                 "departureTime":"2099-01-01T10:00:00Z"}
                                """))
                .andExpect(status().isConflict());
        when(flightService.search(any(), any(), any())).thenThrow(
                new PropertyReferenceException("x\nFORGED", TypeInformation.of(Flight.class), List.of()));
        mockMvc.perform(get("/api/v1/flights"))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll()).contains("Malformed request", "Unknown sort property",
                "Constraint violation", "unresolved by Spring Data");
        assertThat(output.getAll().lines()).noneMatch(line -> line.startsWith("FORGED"));
    }

    /** The offset form is still ISO-8601, and it is stored as the same instant in UTC. */
    @Test
    @DisplayName("a departure time with an offset is read as the same instant")
    void departureTimeWithAnOffsetIsRead() throws Exception {
        when(flightService.create(any())).thenReturn(dto());

        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,
                                 "departureTime":"2099-01-01T15:30:00+05:30"}
                                """))
                .andExpect(status().isCreated());

        verify(flightService).create(argThat(r -> r.departureTime().equals(Instant.parse("2099-01-01T10:00:00Z"))));
    }

    /**
     * {@code Instant.parse} reads years up to a billion and {@code @Future} is
     * happy with any of them. PostgreSQL stores nothing after 294276 AD, and the
     * failed insert reached the client as a 409 that said to retry.
     */
    @Test
    @DisplayName("a departure after the year 9999 is 400, not a database error")
    void departureTimeAfterYear9999IsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA999","origin":"EWR","destination":"SFO","totalSeats":100,
                                 "departureTime":"+300000-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        verify(flightService, never()).create(any());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/api/v1/flights/UA123"))
                .andExpect(status().isNoContent());

        verify(flightService).cancel("UA123");
    }

    /**
     * Both writes update the flight row that a booking locks with
     * {@code SELECT ... FOR UPDATE}, so they can time out behind one, and the
     * OpenAPI document lists 503 for both.
     */
    @Test
    @DisplayName("a status change or cancellation that times out on the row lock is 503 LOCK_TIMEOUT with Retry-After")
    void flightWriteBehindARowLockReturns503() throws Exception {
        var timeout = new PessimisticLockingFailureException("lock timeout");
        when(flightService.updateStatus("UA123", FlightStatus.DELAYED)).thenThrow(timeout);
        doThrow(timeout).when(flightService).cancel("UA456");

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));

        mockMvc.perform(delete("/api/v1/flights/UA456"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));
    }

    /**
     * Both writes load the flight without the row lock, so a booking can commit
     * between their read and their write. {@code @Version} rejects the stale
     * write instead of letting it put back the seats the booking took.
     */
    @Test
    @DisplayName("a stale status change or cancellation rejected by @Version is 409 CONCURRENT_MODIFICATION")
    void staleFlightWriteReturns409() throws Exception {
        var stale = new OptimisticLockingFailureException("stale");
        when(flightService.updateStatus("UA123", FlightStatus.DELAYED)).thenThrow(stale);
        doThrow(stale).when(flightService).cancel("UA456");

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELAYED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));

        mockMvc.perform(delete("/api/v1/flights/UA456"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    /**
     * What a full pool looks like to the controller: Hikari gives up after its
     * connection-timeout and the transaction cannot begin. Any endpoint can hit
     * it, reads included, so a GET stands in for all of them.
     */
    @Test
    @DisplayName("no database connection is 503 DATABASE_UNAVAILABLE with Retry-After, not 500")
    void noDatabaseConnectionReturns503() throws Exception {
        when(flightService.findByNumber("UA123")).thenThrow(new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));

        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }

    /**
     * POST, because security lets it through to MVC. PUT and OPTIONS never get
     * this far in the real app: {@code denyAll()} answers them with 403, which
     * {@code SecurityRulesTest#unhandledVerbsStayDenied} pins. RFC 9110 requires
     * {@code Allow} on a 405, so the advice copies Spring's headers.
     */
    @Test
    @DisplayName("a verb the path does not map is 405 METHOD_NOT_ALLOWED with an Allow header")
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(post("/api/v1/flights/UA123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", allOf(containsString("GET"), containsString("DELETE"))))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("search returns a PagedModel: content plus a nested page object, no leaked pageable")
    void searchDelegatesWithPagingDefaults() throws Exception {
        when(flightService.search(eq("EWR"), eq("LHR"), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(dto())));

        mockMvc.perform(get("/api/v1/flights").param("origin", "EWR").param("destination", "LHR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].flightNumber").value("UA123"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    @DisplayName("page and size come off the query string; @PageableDefault fills in the rest")
    void searchAcceptsPagingParameters() throws Exception {
        var pageCaptor = org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        when(flightService.search(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/flights").param("page", "2").param("size", "5"))
                .andExpect(status().isOk());

        verify(flightService).search(eq(null), eq(null), pageCaptor.capture());
        var pageable = pageCaptor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(5);
        assertThat(pageable.getSort().getOrderFor("departureTime")).isNotNull();
    }
}
