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
 * response, for the 401s and 403s Spring Security produces. Those are decided in a
 * servlet filter before {@code DispatcherServlet} runs, so {@code GlobalExceptionHandler}
 * never sees them. It uses the container's {@link ObjectMapper} and {@link Clock}, so
 * the timestamp is formatted and taken the same way as in every other error body.
 *
 * @see JsonAuthenticationEntryPoint
 * @see JsonAccessDeniedHandler
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
     * @param code a stable machine-readable code, from {@code GlobalExceptionHandler}'s vocabulary
     * @throws IOException if the client has already disconnected
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
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, message, clock.instant()));
    }
}
