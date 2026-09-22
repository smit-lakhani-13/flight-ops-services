package com.smit.flightops.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter in isolation, with a chain that reads the MDC, because what the MDC holds
 * during the request is the one thing a running app cannot easily show.
 */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

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
}
