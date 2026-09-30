package com.smit.flightops.security;

import com.smit.flightops.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;

/**
 * Writes the {@code {code, message, timestamp}} error body straight to the servlet
 * response, for the 401s and 403s Spring Security produces and the 413s
 * {@link RequestBodyLimitFilter} decides itself. Those are decided in a servlet filter
 * before {@code DispatcherServlet} runs, so {@code GlobalExceptionHandler} never sees
 * them. It uses the container's {@link ObjectMapper} and {@link Clock}, so the
 * timestamp is formatted and taken the same way as in every other error body.
 *
 * <p>The {@code Content-Type} is {@code application/json} with no {@code charset},
 * the value {@code GlobalExceptionHandler} and {@code ApiErrorController} send, so a
 * client sees one spelling on every error envelope. The media type defines no charset
 * parameter (RFC 8259, section 11): JSON on the wire is UTF-8, and Jackson writes
 * these bytes as UTF-8 to the output stream. Setting the response's character
 * encoding would only make Tomcat append {@code ;charset=UTF-8}.
 *
 * @see JsonAuthenticationEntryPoint
 * @see JsonAccessDeniedHandler
 * @see RequestBodyLimitFilter
 */
@Component
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ErrorResponseWriter(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param code a stable machine-readable code from the error-code table in doc/api.md:
     *        {@code UNAUTHENTICATED} from the entry point, {@code FORBIDDEN} from the
     *        access-denied handler, {@code PAYLOAD_TOO_LARGE} from the body limit
     * @throws IOException if the servlet output stream cannot be obtained. A write to
     *         a client that has disconnected can fail with Jackson 3's unchecked
     *         {@code JacksonIOException} instead
     */
    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        // The entry point can run during an error dispatch that has already written;
        // a second status would throw on some containers and be ignored on others.
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, message, clock.instant()));
    }
}
