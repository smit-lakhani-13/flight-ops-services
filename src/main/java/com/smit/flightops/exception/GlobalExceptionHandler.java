package com.smit.flightops.exception;

import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.ValidationErrorResponse;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * One place that decides what the client sees, so no controller needs a
 * try/catch.
 *
 * <p>There are exactly two response shapes, and the difference is deliberate:
 * <ul>
 *   <li>{@code {code, message, timestamp}} — {@link ErrorResponse}, for every
 *       error a client can only read and react to.</li>
 *   <li>{@code {code, fieldErrors, timestamp}} — {@link ValidationErrorResponse},
 *       for a 400 from bean validation. A map keyed by field name is what lets a
 *       caller attach each message to the input that caused it; flattening it
 *       into one {@code message} string would force clients to parse prose.</li>
 * </ul>
 * A client can tell them apart by {@code code}: {@code VALIDATION_FAILED} is the
 * only one that carries {@code fieldErrors}.
 *
 * <p>Spring picks the handler whose declared exception type is closest to the
 * thrown one, so the specific handlers below always win over the catch-all.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(FlightNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("FLIGHT_NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("BOOKING_NOT_FOUND", e.getMessage()));
    }

    /**
     * 409, not 400: the request was perfectly well formed, it just lost a race
     * with other passengers. 400 would tell the client to fix its input; 409
     * tells it the state of the resource is the problem.
     */
    @ExceptionHandler(InsufficientSeatsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientSeats(InsufficientSeatsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("INSUFFICIENT_SEATS", e.getMessage()));
    }

    /**
     * 409 as well, and for the same reason: the flight exists, the payload is
     * valid, the state of the resource is what refuses. Distinct from
     * INSUFFICIENT_SEATS because the client should NOT retry with fewer seats —
     * a cancelled flight has plenty and will still say no.
     */
    @ExceptionHandler(FlightNotBookableException.class)
    public ResponseEntity<ErrorResponse> handleNotBookable(FlightNotBookableException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("FLIGHT_NOT_BOOKABLE", e.getMessage()));
    }

    @ExceptionHandler(DuplicateFlightException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateFlight(DuplicateFlightException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_FLIGHT", e.getMessage()));
    }

    /**
     * The {@code @Version} column rejected a stale write. Retrying is usually
     * the right move, so the message says so instead of leaking JPA internals.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONCURRENT_MODIFICATION",
                                       "The record changed while you were editing it. Please retry."));
    }

    /**
     * A unique constraint fired. In practice that is
     * {@link com.smit.flightops.service.FlightService#create} losing the
     * {@code uk_flights_flight_number} race — its own Javadoc explains why
     * the courtesy check can't prevent it and lands here.
     *
     * <p>A booking that races on {@code idempotency_key} does <b>not</b> reach
     * this handler: {@link com.smit.flightops.service.BookingService#book}
     * catches that specific violation itself and recovers the winner's
     * booking, so the race loser also gets 201, not this 409. That recovery
     * is why this handler no longer needs to reason about idempotency keys at
     * all — see {@code BookingService.book} and {@code BookingWriter} for the
     * mechanism.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("Constraint violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_REQUEST",
                                       "This request conflicts with an existing record. Please retry."));
    }

    /** Bean Validation failures, reported per field so the client can fix them. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage(),
                        (first, second) -> first));
        return ResponseEntity.badRequest()
                .body(new ValidationErrorResponse("VALIDATION_FAILED", fieldErrors, Instant.now()));
    }

    /**
     * Unparseable body, unknown enum constant, or a path variable that will not
     * convert. Without this the catch-all below would answer 500 — e.g. a PATCH
     * carrying {@code {"status":"NOPE"}} fails inside Jackson, never reaches Bean
     * Validation, and a server error is the wrong story for a client mistake.
     *
     * <p>The message is deliberately generic: Jackson's text names internal
     * classes and echoes the payload back.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e) {
        log.warn("Malformed request: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST",
                                       "Request could not be read. Check the field names, types and enum values."));
    }

    /** An argument the domain rejects outright, e.g. reserving zero seats. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("MALFORMED_REQUEST", e.getMessage()));
    }

    /**
     * Spring MVC's own failures — unknown path (404), wrong method (405), missing
     * query parameter (400), unsupported content type (415).
     *
     * <p>This handler exists because a bare {@code @ExceptionHandler(Exception.class)}
     * catch-all swallows all of them and returns 500. Most of these exceptions
     * implement {@link org.springframework.web.ErrorResponse}, which carries the
     * status Spring already decided on, so the right status is simply read back
     * off the exception rather than re-derived.
     */
    @ExceptionHandler({ServletException.class, ErrorResponseException.class})
    public ResponseEntity<ErrorResponse> handleSpringWebError(Exception e) {
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String detail = errorResponse.getBody().getDetail();
            return ResponseEntity.status(status)
                    .body(ErrorResponse.of(codeFor(status),
                                           detail == null ? status.toString() : detail));
        }
        return handleUnexpected(e);
    }

    /**
     * Last resort. Log the stack trace, return nothing about it: exception text
     * leaks table names, SQL and file paths to whoever is probing the API.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "MALFORMED_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
        };
    }
}
