package com.smit.flightops.exception;

import com.smit.flightops.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * The last error path in the application, and the only one that was not
 * answering in the documented shape.
 *
 * <p>{@link GlobalExceptionHandler} covers everything thrown inside a
 * controller, and {@code ErrorResponseWriter} covers the 401 and 403 Spring
 * Security produces before a controller is reached. What neither covers is
 * {@code /error} itself — the path the servlet container forwards to when an
 * error escapes both, and the path a caller can simply request. Without this
 * class Spring Boot's {@code BasicErrorController} answers it, and answers it
 * with a different shape:
 *
 * <pre>
 *   GET /error
 *   500  {"timestamp":"...","status":999,"error":"None"}
 * </pre>
 *
 * <p>Three things are wrong with that for an API whose README, OpenAPI document
 * and SECURITY.md all state that every error is {@code {code, message,
 * timestamp}}. The keys are different, so a client that parses the documented
 * envelope throws instead of reporting the error. The status is {@code 999},
 * which is not an HTTP status and comes from Boot's placeholder for "no error
 * attributes were set". And the response code is 500, announcing a server fault
 * for what was a request to a path that is not part of the API.
 *
 * <p>So this returns the documented envelope, and reads the real status out of
 * the request attributes the container sets on a forward. A direct request,
 * which sets none of them, is a 404: {@code /error} is not an endpoint of this
 * API and asking for it is a client mistake, not a server failure.
 *
 * <p>The path is deliberately {@code /error} rather than
 * {@code ${server.error.path}} — {@code SecurityConfig} permits the literal
 * {@code /error}, and a property that moved one without the other would leave
 * the error page denied, which is the failure that rule exists to prevent.
 *
 * <p>{@code @Hidden} keeps it out of the OpenAPI document. Being a
 * {@code @RestController} makes springdoc describe it, and {@code /error} is
 * not an operation of this API: publishing it would invite a client to call it,
 * and {@code OpenApiTest} asserts the document lists exactly the five real
 * paths.
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

    private final Clock clock;

    public ApiErrorController(Clock clock) {
        this.clock = clock;
    }

    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request);
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(codeFor(status), messageFor(status), clock.instant()));
    }

    /**
     * The status the container recorded before forwarding here, or 404 when
     * there is none — which is the case for a direct {@code GET /error}.
     *
     * <p>The attribute is an {@code Integer} and can be absent or unparseable,
     * so every path that is not a known status lands on 404 rather than on an
     * exception thrown inside the error handler, which the container would
     * answer by forwarding here again.
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
     * Deliberately generic. The container's error message attribute can carry
     * an exception message, and an exception message is the one thing on an
     * error path that is written for an operator rather than for a caller —
     * echoing it here is how a stack frame, a SQL fragment or an internal path
     * reaches a client. The request id on the response is what ties this
     * response to the log line that does have the detail.
     */
    private String messageFor(HttpStatus status) {
        return status.is4xxClientError()
                ? "The request could not be served. Check the method and the path."
                : "The request failed. The X-Request-Id header identifies it in the logs.";
    }
}
