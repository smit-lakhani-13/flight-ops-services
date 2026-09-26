package com.smit.flightops.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.observability.RequestIdFilter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The error dispatch in isolation, set up the way the container leaves it: the MDC
 * already cleared by {@link RequestIdFilter}, the id still on the response header. A
 * full MockMvc test cannot reach this state, because MockMvc never forwards to
 * {@code /error}.
 */
class ApiErrorControllerTest {

    private final ApiErrorController controller =
            new ApiErrorController(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiErrorController.class);

    /** Snapshots the MDC at append time; a plain ListAppender reads it later, once cleared. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    /**
     * The 500 body tells the caller to quote X-Request-Id, so the one ERROR line must
     * carry that id and the exception, and the MDC must be clean afterwards.
     */
    @Test
    @DisplayName("a failure that escaped the chain is logged once, with the caller's request id")
    void aFailureThatEscapedTheChainIsLoggedWithTheRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/flights/UA123");
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("filter blew up"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(RequestIdFilter.HEADER, "support-ticket-4471");

        ResponseEntity<ErrorResponse> answer = controller.handleError(request, response);

        assertThat(answer.getStatusCode().value()).isEqualTo(500);
        assertThat(answer.getBody().code()).isEqualTo("INTERNAL_ERROR");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, "support-ticket-4471");
            assertThat(event.getFormattedMessage()).contains("GET /api/v1/flights/UA123");
            assertThat(event.getThrowableProxy().getMessage()).isEqualTo("filter blew up");
        });
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /**
     * A 404 is a client mistake and is not logged, even with an exception
     * attached. The content type is set, not negotiated, so a caller asking for
     * XML still gets the JSON envelope, not an empty 406.
     */
    @Test
    @DisplayName("a forwarded 404 is not logged, and stays JSON when the caller asks for XML")
    void aClientErrorIsNotLoggedAndStaysJson() throws Exception {
        mockMvc.perform(get("/error")
                        .accept(MediaType.APPLICATION_XML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("not found")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(appender.list).isEmpty();
    }

    /**
     * The container's error message and the exception can name a table or an internal
     * class, so a 5xx answers with one fixed string and the detail goes to the log.
     */
    @Test
    @DisplayName("a forwarded 500 gets the fixed message, not the container's text or the exception's")
    void aServerErrorGetsTheFixedMessage() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "select * from bookings")
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret")))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message")
                        .value("The request failed. The X-Request-Id header identifies it in the logs."))
                .andExpect(content().string(not(containsString("select"))))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    @DisplayName("a forwarded 404 gets the fixed client message, not the container's text")
    void aClientErrorGetsTheFixedMessage() throws Exception {
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "No resource /WEB-INF/internal"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value("The request could not be served. Check the method and the path."))
                .andExpect(content().string(not(containsString("WEB-INF"))));
    }

    /**
     * The container forwards a status and nothing else, so the status alone picks the
     * code. 418 stands for any client error without a case of its own, and 503 for any
     * server error.
     */
    @ParameterizedTest
    @CsvSource({
            "405, METHOD_NOT_ALLOWED",
            "400, BAD_REQUEST",
            "401, UNAUTHENTICATED",
            "403, FORBIDDEN",
            "404, RESOURCE_NOT_FOUND",
            "418, BAD_REQUEST",
            "503, INTERNAL_ERROR"
    })
    @DisplayName("the forwarded status decides the code")
    void theStatusDecidesTheCode(int forwarded, String code) throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, forwarded))
                .andExpect(status().is(forwarded))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(code));
    }

    @Test
    @DisplayName("the timestamp is the injected clock's instant")
    void theTimestampComesFromTheClock() throws Exception {
        mockMvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404))
                .andExpect(jsonPath("$.timestamp").value("2026-01-01T00:00:00Z"));
    }
}
