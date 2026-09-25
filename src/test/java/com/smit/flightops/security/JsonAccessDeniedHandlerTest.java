package com.smit.flightops.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 403 handler on its own, with the request built directly so that its path can
 * carry a raw CR and LF. MockMvc builds the path from a URI and would percent-encode
 * them.
 */
class JsonAccessDeniedHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonAccessDeniedHandler handler = new JsonAccessDeniedHandler(
            new ErrorResponseWriter(MAPPER, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));

    private final Logger logger = (Logger) LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("a CR or LF in the path cannot start a second log line")
    void lineBreaksInThePathCannotForgeALogLine() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/flights\r\nFORGED WARN line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = MAPPER.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("FORBIDDEN");
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                    .doesNotContain("\r", "\n")
                    .contains("PUT /api/v1/flights??FORGED?WARN?line");
        });
    }

    @Test
    @DisplayName("an ordinary path is logged unchanged")
    void anOrdinaryPathIsLoggedUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/flights/UA123");

        handler.handle(request, new MockHttpServletResponse(), new AccessDeniedException("denied"));

        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("DELETE /api/v1/flights/UA123 for an authenticated caller"));
    }
}
