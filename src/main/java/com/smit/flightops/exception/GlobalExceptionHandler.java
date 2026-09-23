package com.smit.flightops.exception;

import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.observability.BookingMetrics;
import com.smit.flightops.dto.ValidationErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedRuntimeException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.util.StringUtils;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns every exception a controller lets through into one of two JSON shapes:
 * {@link ErrorResponse} {@code {code, message, timestamp}}, or
 * {@link ValidationErrorResponse} {@code {code, fieldErrors, timestamp}} for a
 * Bean Validation 400, so a client can attach each message to its input.
 *
 * <p>Every response presets {@code Content-Type: application/json}. Without it
 * Spring negotiates the error body against {@code Accept}, and a client asking
 * for XML gets an empty 406 and a server-side stack trace instead of the error.
 * Timestamps come from the injected {@link Clock}, so a test can pin them. The
 * README tables every code.
 *
 * @see ApiErrorController
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Pattern NOT_PRINTABLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");

    private final Clock clock;
    private final BookingMetrics metrics;

    public GlobalExceptionHandler(Clock clock, BookingMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
    }

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(FlightNotFoundException e) {
        return json(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("FLIGHT_NOT_FOUND", e.getMessage(), clock.instant()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException e) {
        return json(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("BOOKING_NOT_FOUND", e.getMessage(), clock.instant()));
    }

    /** 409, not 400: the request is well formed and lost to other passengers. */
    @ExceptionHandler(InsufficientSeatsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientSeats(InsufficientSeatsException e) {
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("INSUFFICIENT_SEATS", e.getMessage(), clock.instant()));
    }

    /** A separate code from INSUFFICIENT_SEATS because retrying with fewer seats can never work. */
    @ExceptionHandler(FlightNotBookableException.class)
    public ResponseEntity<ErrorResponse> handleNotBookable(FlightNotBookableException e) {
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("FLIGHT_NOT_BOOKABLE", e.getMessage(), clock.instant()));
    }

    @ExceptionHandler(DuplicateFlightException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateFlight(DuplicateFlightException e) {
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_FLIGHT", e.getMessage(), clock.instant()));
    }

    /** {@code @Version} rejected a stale write. The message says to retry and leaks no JPA detail. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        log.warn("Optimistic lock conflict: {}", e.getMessage());
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("CONCURRENT_MODIFICATION",
                                       "The record changed while you were editing it. Please retry.",
                                       clock.instant()));
    }

    /**
     * A unique constraint fired: in practice {@code FlightService#create} losing
     * the {@code uk_flights_flight_number} race. A booking that races on its
     * idempotency key never gets here, because {@code BookingService#book}
     * recovers the winner's booking and answers 201.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        // PostgreSQL's detail quotes the values the client sent.
        log.warn("Constraint violation: {}", printable(e.getMostSpecificCause().getMessage()));
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_REQUEST",
                                       "This request conflicts with an existing record. Please retry.",
                                       clock.instant()));
    }

    @ExceptionHandler(IllegalFlightTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalFlightTransitionException e) {
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ILLEGAL_STATUS_TRANSITION", e.getMessage(), clock.instant()));
    }

    /**
     * Not DUPLICATE_REQUEST: the fix here is a new key, and one generic 409 for
     * both would leave a client retrying with a key that can never work.
     */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyKeyConflictException e) {
        return json(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", e.getMessage(), clock.instant()));
    }

    /**
     * Echoes the property name, which the client sent, and nothing else about
     * the entity: listing its real properties would be a free schema dump.
     */
    @ExceptionHandler(UnknownSortPropertyException.class)
    public ResponseEntity<ErrorResponse> handleUnknownSortProperty(UnknownSortPropertyException e) {
        log.warn("Unknown sort property: {}", printable(e.getPropertyName()));
        return json(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("UNKNOWN_SORT_PROPERTY", e.getMessage(), clock.instant()));
    }

    /**
     * The same 400 when Spring Data rejects the property while building a
     * derived query. {@code SortPolicy} catches it first on both list endpoints;
     * this is the backstop for a repository call that sorts without it.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleUnresolvedSortProperty(PropertyReferenceException e) {
        log.warn("Unknown sort property (unresolved by Spring Data): {}", printable(e.getPropertyName()));
        return json(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("UNKNOWN_SORT_PROPERTY",
                                       "'%s' is not a sortable property.".formatted(e.getPropertyName()),
                                       clock.instant()));
    }

    /**
     * The row lock timed out, or the database broke a deadlock. 503 with
     * {@code Retry-After}, because the same request a moment later will very
     * likely succeed, and a 500 tells a well-behaved client to give up. That is
     * what makes the postgres profile's {@code lock_timeout} useful.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLockTimeout(PessimisticLockingFailureException e) {
        // Counted here because the driver's exception is translated on the way
        // out of the transaction, and this is the first place that knows its type.
        metrics.lockTimedOut();
        log.warn("Lock acquisition failed: {}", e.getMostSpecificCause().getMessage());
        return json(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ErrorResponse.of("LOCK_TIMEOUT",
                                       "That flight is busy right now. Please retry.",
                                       clock.instant()));
    }

    /**
     * No connection: the pool stayed empty for its whole connection-timeout, or
     * the database did not answer. 503 with {@code Retry-After} for the lock
     * timeout's reason: the request was valid, and the same request later can
     * succeed. As a 500 it also logged a stack trace per caller, at ERROR, for
     * a condition that is not a bug in this code.
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ErrorResponse> handleDatabaseUnavailable(NestedRuntimeException e) {
        log.warn("Database unavailable: {}", printable(e.getMostSpecificCause().getMessage()));
        return json(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ErrorResponse.of("DATABASE_UNAVAILABLE",
                                       "The service cannot reach its database. Please retry.",
                                       clock.instant()));
    }

    /**
     * Bean Validation failures, one message per field. Global errors are merged
     * in under the object name, so a class-level constraint that forgets to
     * target a field still reaches the client instead of an empty map.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        org.springframework.validation.FieldError::getField,
                        fe -> fe.getDefaultMessage() == null ? "is invalid" : fe.getDefaultMessage(),
                        (first, second) -> first));

        e.getBindingResult().getGlobalErrors().forEach(ge ->
                fieldErrors.putIfAbsent(ge.getObjectName(),
                        ge.getDefaultMessage() == null ? "is invalid" : ge.getDefaultMessage()));

        return json(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorResponse("VALIDATION_FAILED", fieldErrors, clock.instant()));
    }

    /**
     * A body Jackson cannot bind, or a path variable that will not convert. That
     * covers malformed JSON, and an unknown enum constant or one sent as a number.
     * It also covers a missing, null or quoted {@code int}, one written with a
     * decimal point or an exponent, and a time that is not an ISO-8601 instant.
     * None of these reach Bean Validation. The message is generic because
     * Jackson's names internal classes and echoes the payload.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleMalformed(Exception e) {
        log.warn("Malformed request: {}", printable(e.getMessage()));
        return json(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST",
                                       "Request could not be read. Check the field names, types and enum values.",
                                       clock.instant()));
    }

    /**
     * A backstop, not a business path: the domain's own guard on a non-positive
     * seat count is unreachable over HTTP because validation answers first. The
     * message is fixed because Spring, Hibernate, Jackson and the JDK all throw
     * this type, and their text is for the log.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Rejected argument", e);
        return json(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("MALFORMED_REQUEST", "The request contained an invalid value.", clock.instant()));
    }

    /**
     * Spring MVC's own failures, such as 404, 405, 406 and 415, which a bare
     * catch-all would turn into 500. Each carries the status Spring chose and the
     * headers that go with it: {@code Allow} on a 405, {@code Accept} on a 415.
     */
    @ExceptionHandler({ServletException.class, ErrorResponseException.class})
    public ResponseEntity<ErrorResponse> handleSpringWebError(Exception e, HttpServletRequest request) {
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            String detail = errorResponse.getBody().getDetail();
            // Spring's text for a missing header is "Content-Type 'null' is not supported.".
            // The header is read, not the exception's media type, which an unparseable
            // header leaves null too, and that case keeps "Could not parse Content-Type.".
            if (e instanceof HttpMediaTypeNotSupportedException && !StringUtils.hasLength(request.getContentType())) {
                detail = "The request has no Content-Type. Send application/json.";
            }
            return ResponseEntity.status(status)
                    .headers(errorResponse.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorResponse.of(codeFor(status),
                                           detail == null ? status.toString() : detail,
                                           clock.instant()));
        }
        return handleUnexpected(e);
    }

    /** Last resort: log the stack trace, and return nothing about it. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", clock.instant()));
    }

    /**
     * The rule the Lambda's {@code BookingEventHandler.printable} applies: control,
     * format and line-separator characters become {@code ?}, and the length is
     * capped. Jackson quotes rejected input in full, and a newline in it would
     * start a forged log line.
     */
    private static String printable(String value) {
        if (value == null) {
            return "null";
        }
        String oneLine = NOT_PRINTABLE.matcher(value).replaceAll("?");
        return oneLine.length() <= 1000 ? oneLine : oneLine.substring(0, 1000) + "...";
    }

    private static ResponseEntity.BodyBuilder json(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
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
