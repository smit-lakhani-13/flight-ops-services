package com.smit.flightops.exception;

import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.observability.RequestIdFilter;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * Answers {@code /error}, where the container forwards a failure that escaped both
 * {@link GlobalExceptionHandler} and the security handlers, in the same
 * {@code {code, message, timestamp}} envelope as every other error. Boot's
 * {@code BasicErrorController} would answer with its own keys. A request with no
 * forwarded status is a 404, because {@code /error} is not an endpoint of this API. The
 * path must match the literal {@code /error} that {@code SecurityConfig} permits.
 *
 * @see GlobalExceptionHandler
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    private final Clock clock;

    public ApiErrorController(Clock clock) {
        this.clock = clock;
    }

    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = resolveStatus(request);
        if (status.is5xxServerError()) {
            logFailure(request, response);
        }
        // Set rather than negotiated, so an Accept header asking for XML cannot turn
        // the error into an empty 406.
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ErrorResponse.of(codeFor(status), messageFor(status), clock.instant()));
    }

    /**
     * Logs the exception with the caller's request id. RequestIdFilter has already
     * cleared the MDC by the time the container forwards here, but the id is still on the
     * response header, and the 500 body tells the caller to quote it.
     */
    private static void logFailure(HttpServletRequest request, HttpServletResponse response) {
        if (!(request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure)) {
            return;
        }
        String requestId = response.getHeader(RequestIdFilter.HEADER);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(RequestIdFilter.MDC_KEY, requestId)) {
            log.error("Unhandled failure on {} {}", request.getMethod(),
                    request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI), failure);
        }
    }

    /**
     * The status the container recorded before forwarding here, or 404 when there is
     * none. Anything unparseable also lands on 404, because an exception thrown here
     * would be forwarded straight back to this handler.
     */
    private HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.NOT_FOUND;
    }

    private String codeFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            case UNAUTHORIZED -> "UNAUTHENTICATED";
            case FORBIDDEN -> "FORBIDDEN";
            case METHOD_NOT_ALLOWED -> "METHOD_NOT_ALLOWED";
            default -> status.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR";
        };
    }

    /**
     * Generic on purpose: the container's error message can carry an exception message,
     * which may hold a SQL fragment or an internal path. The detail is in the log line
     * that carries the same request id.
     */
    private String messageFor(HttpStatus status) {
        return status.is4xxClientError()
                ? "The request could not be served. Check the method and the path."
                : "The request failed. The X-Request-Id header identifies it in the logs.";
    }
}
