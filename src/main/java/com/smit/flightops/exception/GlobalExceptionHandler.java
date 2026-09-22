package com.smit.flightops.exception;

import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.ValidationErrorResponse;
import jakarta.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Clock;
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
 *
 * <p>Every {@code timestamp} here comes from the injected {@link Clock} rather
 * than {@code Instant.now()}, which is what lets a test assert on the value
 * instead of merely on the field's presence, and keeps one source of time in
 * the application.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(FlightNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(FlightNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("FLIGHT_NOT_FOUND", e.getMessage(), clock.instant()));
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookingNotFound(BookingNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of("BOOKING_NOT_FOUND", e.getMessage(), clock.instant()));
    }

    /**
     * 409, not 400: the request was perfectly well formed, it just lost a race
     * with other passengers. 400 would tell the client to fix its input; 409
     * tells it the state of the resource is the problem.
     */
    @ExceptionHandler(InsufficientSeatsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientSeats(InsufficientSeatsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("INSUFFICIENT_SEATS", e.getMessage(), clock.instant()));
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
                .body(ErrorResponse.of("FLIGHT_NOT_BOOKABLE", e.getMessage(), clock.instant()));
    }

    @ExceptionHandler(DuplicateFlightException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateFlight(DuplicateFlightException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("DUPLICATE_FLIGHT", e.getMessage(), clock.instant()));
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
                                       "The record changed while you were editing it. Please retry.",
                                       clock.instant()));
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
                                       "This request conflicts with an existing record. Please retry.",
                                       clock.instant()));
    }

    /**
     * A status change the flight lifecycle forbids — un-cancelling a cancelled
     * flight, or reviving an arrived one.
     *
     * <p>409, not 400: the payload is valid and the target status is a real
     * status. What conflicts is the state the flight is in, which is the
     * definition of 409. The message names both statuses because "invalid
     * status" gives the client nothing to act on.
     */
    @ExceptionHandler(IllegalFlightTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalTransition(IllegalFlightTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("ILLEGAL_STATUS_TRANSITION", e.getMessage(), clock.instant()));
    }

    /**
     * The same idempotency key used for a different booking.
     *
     * <p>Distinct from {@code DUPLICATE_REQUEST} below, and the difference is
     * the one the client has to act on. {@code DUPLICATE_REQUEST} means a
     * constraint said this record already exists; this means the key is fine
     * but it is already spoken for by a different request, so the fix is a new
     * key. One generic 409 for both would leave a caller retrying forever with
     * the key that can never work.
     */
    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyKeyConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of("IDEMPOTENCY_KEY_REUSED", e.getMessage(), clock.instant()));
    }

    /**
     * {@code ?sort=<something that is not a property>}.
     *
     * <p>This was a 500. Spring Data resolves the sort property against the
     * entity when the query is built, which happens deep inside the repository
     * proxy — long after any controller validation and nowhere near Bean
     * Validation, so nothing else could have caught it. A caller typing
     * {@code ?sort=deptime} instead of {@code departureTime} got a server
     * error, which says "we are broken" about a request that is simply wrong.
     *
     * <p>{@code e.getPropertyName()} is echoed back deliberately, and it is
     * safe to: it is a string the client just sent. The rest of the exception's
     * text is not echoed — it names the entity class and lists its properties,
     * which is a free schema dump for anyone probing the API.
     */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleUnknownSortProperty(PropertyReferenceException e) {
        log.warn("Unknown sort property: {}", e.getPropertyName());
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("UNKNOWN_SORT_PROPERTY",
                                       "'%s' is not a sortable property.".formatted(e.getPropertyName()),
                                       clock.instant()));
    }

    /**
     * The row lock timed out, or the database killed this transaction to break
     * a deadlock.
     *
     * <p>503 with {@code Retry-After}, not 500. A lock timeout is a statement
     * about right now: the seat row is busy, and the same request a second
     * later will very likely succeed. 500 tells a client the request is
     * hopeless and well-behaved ones stop; 503 plus {@code Retry-After} is the
     * HTTP-level way of saying "try again shortly", and it is what makes the
     * {@code lock_timeout} in the postgres profile useful rather than just a
     * different way to fail.
     *
     * <p>Distinct from {@link #handleOptimisticLock} on purpose: that one means
     * somebody else committed a change to a row this transaction had already
     * read, which is a genuine conflict. This one means nobody got that far.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleLockTimeout(PessimisticLockingFailureException e) {
        log.warn("Lock acquisition failed: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(ErrorResponse.of("LOCK_TIMEOUT",
                                       "That flight is busy right now. Please retry.",
                                       clock.instant()));
    }

    /**
     * Bean Validation failures, reported per field so the client can fix them.
     *
     * <p>Global errors are merged in, and that is not defensive padding. A
     * class-level constraint — {@code @DistinctEndpoints} on
     * {@code CreateFlightRequest} is the one here — produces a violation with
     * no field attached, so a handler reading only {@code getFieldErrors()}
     * returns 400 with an empty {@code fieldErrors} object and tells the client
     * nothing whatsoever. The validator re-targets its violation at a property
     * node precisely so it lands in the map, but relying on every future
     * cross-field constraint to remember that is how this regresses. Global
     * errors are keyed by the object name, which is the honest answer when a
     * violation really is about the request as a whole.
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

        return ResponseEntity.badRequest()
                .body(new ValidationErrorResponse("VALIDATION_FAILED", fieldErrors, clock.instant()));
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
                                       "Request could not be read. Check the field names, types and enum values.",
                                       clock.instant()));
    }

    /**
     * A stray {@code IllegalArgumentException} — a backstop, not a business
     * path. The domain's own guard ({@link com.smit.flightops.entity.Flight#reserveSeats}
     * on a non-positive count) is unreachable over HTTP, because
     * {@code BookingRequest.seats} is {@code @Min(1) @Max(9)} and bean
     * validation answers first with {@code VALIDATION_FAILED}.
     *
     * <p>So the message is fixed rather than {@code e.getMessage()}. This
     * handler is bound to a JDK type that Spring, Hibernate, Jackson and the
     * JDK itself all throw, and echoing their text would leak internals for
     * exactly the reason {@link #handleUnexpected} says nothing. The operator
     * gets the detail from the log instead.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Rejected argument", e);
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of("MALFORMED_REQUEST", "The request contained an invalid value.", clock.instant()));
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
                                           detail == null ? status.toString() : detail,
                                           clock.instant()));
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
                .body(ErrorResponse.of("INTERNAL_ERROR", "An unexpected error occurred", clock.instant()));
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
