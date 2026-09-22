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
 * Writes the application's own {@code {code, message, timestamp}} error body
 * straight to the servlet response.
 *
 * <p><b>Why this exists at all, and it is the most useful thing in this
 * package to understand:</b> {@code GlobalExceptionHandler} cannot handle 401
 * or 403. Spring Security runs as a servlet filter, which means it rejects a
 * request <em>before</em> {@code DispatcherServlet} ever sees it. There is no
 * handler method, no {@code HandlerExceptionResolver} and therefore no
 * {@code @RestControllerAdvice} in the picture — the {@code
 * AuthenticationException} is caught by {@code ExceptionTranslationFilter}
 * further up the chain and never reaches Spring MVC. Adding an
 * {@code @ExceptionHandler(AccessDeniedException.class)} to the advice looks
 * like the fix and silently does nothing for the anonymous case.
 *
 * <p>Left alone, Spring Security answers with a bare status line and an empty
 * body, so a caller would get {@code {"code":"FLIGHT_NOT_FOUND",...}} for one
 * failure and nothing at all for another. A client written against this API
 * would have to special-case two paths through its own error handling for no
 * reason. This class is the cost of keeping one shape for every error the
 * service can return.
 *
 * <p>It shares the container's {@link ObjectMapper}, so the {@code timestamp}
 * is serialised by exactly the same configuration that serialises every other
 * error body. Building a private mapper here is the subtle version of the bug
 * this class exists to prevent: the shape would match and the date format
 * would not. It shares the container's {@link Clock} for the same reason: two
 * error bodies from one request should not be able to disagree about the time.
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
     * @param code a stable machine-readable code, from the same vocabulary as
     *             {@code GlobalExceptionHandler}'s
     * @throws IOException if the client has already disconnected, which the
     *                     container logs and which nothing here can recover
     *                     from
     */
    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        // Guard, not decoration: the entry point can be invoked during an
        // error dispatch that has already started writing. Committing a second
        // status would throw IllegalStateException on some containers and be
        // silently ignored on others.
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of(code, message, clock.instant()));
    }
}
