package com.smit.flightops.observability;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filter in isolation, with a chain that reads the MDC, because what the MDC holds
 * during the request is the one thing a running app cannot easily show.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);

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

    /** Runs the filter and hands back both the response and what the MDC held mid-chain. */
    private record Run(MockHttpServletResponse response, String mdcDuringRequest) {}

    private Run run(String inboundHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");
        if (inboundHeader != null) {
            request.addHeader(RequestIdFilter.HEADER, inboundHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        String[] seen = new String[1];
        filter.doFilter(request, response,
                        (req, res) -> seen[0] = MDC.get(RequestIdFilter.MDC_KEY));
        return new Run(response, seen[0]);
    }

    /** Runs the filter around a chain that answers with {@code status}. */
    private MockHttpServletResponse answer(MockHttpServletRequest request, int status) throws Exception {
        request.addHeader(RequestIdFilter.HEADER, "ticket-4471");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(status));
        return response;
    }

    @Test
    @DisplayName("no inbound header: a UUID is generated, echoed, and visible in the MDC")
    void generatesOneWhenTheCallerSendsNone() throws Exception {
        Run run = run(null);

        String issued = run.response().getHeader(RequestIdFilter.HEADER);
        assertThat(issued).isNotNull();
        assertThat(UUID.fromString(issued)).isNotNull();   // throws if it is not a UUID
        assertThat(run.mdcDuringRequest()).isEqualTo(issued);
    }

    @Test
    @DisplayName("a sane inbound id is kept, so a caller's id survives into our logs")
    void honoursTheCallersId() throws Exception {
        Run run = run("checkout-service.7f3a:1");

        assertThat(run.response().getHeader(RequestIdFilter.HEADER))
                .isEqualTo("checkout-service.7f3a:1");
        assertThat(run.mdcDuringRequest()).isEqualTo("checkout-service.7f3a:1");
    }

    /**
     * A CRLF would forge a log line or a response header, and an over-long value bloats
     * every line for the request. Both are replaced, and the request still proceeds.
     */
    @Test
    @DisplayName("newlines, spaces and over-long values are replaced rather than echoed")
    void refusesToEchoSomethingDangerous() throws Exception {
        for (String hostile : new String[]{
                "abc\r\nX-Injected: yes",
                "has a space",
                "<script>alert(1)</script>",
                "x".repeat(129),
                ""}) {
            Run run = run(hostile);
            String issued = run.response().getHeader(RequestIdFilter.HEADER);

            assertThat(issued).as("input %s", hostile).isNotEqualTo(hostile);
            assertThat(UUID.fromString(issued)).isNotNull();
        }
        // The boundary itself is fine: 128 is accepted, 129 is not.
        assertThat(run("x".repeat(128)).response().getHeader(RequestIdFilter.HEADER))
                .isEqualTo("x".repeat(128));
    }

    @Test
    @DisplayName("the MDC key is cleared afterwards, even when the chain throws")
    void cleansUpAfterItself() throws Exception {
        run(null);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();

        MockHttpServletResponse response = new MockHttpServletResponse();
        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/boom"), response,
                            (req, res) -> { throw new IllegalStateException("downstream blew up"); });
        } catch (IllegalStateException expected) {
            // What matters is the MDC afterwards.
        }
        assertThat(MDC.get(RequestIdFilter.MDC_KEY))
                .as("a leaked key would label unrelated log lines with a dead request's id")
                .isNull();
    }

    /**
     * A 404, like a 401, logs nothing anywhere else, so without this line the id a
     * caller quotes would find nothing.
     */
    @Test
    @DisplayName("an answer of 400 or above logs one INFO line with the status, carrying the request id")
    void aClientErrorLogsOneLineWithTheRequestId() throws Exception {
        answer(new MockHttpServletRequest("GET", "/api/v1/flights/XX999"), 404);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).isEqualTo("GET /api/v1/flights/XX999 -> 404");
            assertThat(event.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, "ticket-4471");
        });
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("a success logs nothing here")
    void aSuccessLogsNothing() throws Exception {
        answer(new MockHttpServletRequest("GET", "/api/v1/flights/UA123"), 200);

        assertThat(appender.list).isEmpty();
    }

    /** Probes and scrapes: a readiness check that is DOWN would log every period. */
    @Test
    @DisplayName("nothing under /actuator/ is logged, whatever the status")
    void actuatorPathsAreNotLogged() throws Exception {
        answer(new MockHttpServletRequest("GET", "/actuator/prometheus"), 401);
        answer(new MockHttpServletRequest("GET", "/actuator/health/readiness"), 503);

        assertThat(appender.list).isEmpty();
    }

    /** A query string can carry a token or a passenger's name; the path cannot forge a line. */
    @Test
    @DisplayName("the line never carries the query string, and a CR or LF in the path is masked")
    void theLineCarriesNeitherTheQueryStringNorALineBreak() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/flights");
        request.setQueryString("origin=EWR&access_token=s3cret");
        answer(request, 400);
        answer(new MockHttpServletRequest("PUT", "/api/v1/flights\r\nFORGED line"), 403);

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getFormattedMessage())
                .isEqualTo("GET /api/v1/flights -> 400")
                .doesNotContain("access_token", "s3cret", "origin");
        assertThat(appender.list.get(1).getFormattedMessage())
                .doesNotContain("\r", "\n")
                .isEqualTo("PUT /api/v1/flights??FORGED?line -> 403");
    }

    /**
     * The chain does what Spring's ServerHttpObservationFilter, one step inside this
     * filter, does on the way out: it sets 500 and rethrows. The status alone would log
     * the failure here, but the container forwards it to /error, where
     * ApiErrorController logs it with the id, so a line here would be a second line
     * under that id for the one failure. EscapedFailureLogTest checks the same through
     * a running server.
     */
    @Test
    @DisplayName("an exception that escapes the chain is left to ApiErrorController, though the status reads 500")
    void anEscapingExceptionIsNotLoggedHere() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/boom"), response,
                (req, res) -> {
                    response.setStatus(500);
                    throw new IllegalStateException("downstream blew up");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(appender.list).isEmpty();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }
}
