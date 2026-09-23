package com.smit.flightops.controller;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.ValidationErrorResponse;
import com.smit.flightops.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Set;

/**
 * HTTP adapter for bookings, JSON only for the reason {@link FlightController}
 * gives. Both writes are safe to retry; their {@code @Operation} descriptions
 * say how.
 */
@RestController
@RequestMapping(path = "/api/v1/bookings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Bookings",
     description = "Seat reservations. Creating one is idempotent on `idempotencyKey`; "
                   + "cancelling one is idempotent on the booking's own state.")
public class BookingController {

    /**
     * {@code idempotencyKey} is left out because {@link BookingDto} hides it, and
     * sorting by it would give the keys back a comparison at a time.
     * {@code flight} is left out because it would sort by an id the API never shows.
     */
    private static final Set<String> SORTABLE =
            Set.of("id", "createdAt", "passengerName", "seats", "cancelledAt");

    /** The sortable properties that are strings: the only ones {@code ignorecase} applies to. */
    private static final Set<String> TEXTUAL = Set.of("passengerName");

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 201 on the first call and on every replay, including a replay that races
     * the original: {@code BookingService#book} recovers the winner's booking
     * for the loser, so the client cannot tell which attempt did the work.
     */
    @Operation(
            summary = "Create a booking",
            description = """
                    Reserves seats on a flight, or replays the booking a previous call \
                    with this `idempotencyKey` created.

                    **A replay answers 201, not 200.** The body is byte-for-byte the \
                    original booking, so a client that retried after a timeout cannot \
                    tell whether it or its earlier attempt did the work — which is the \
                    property idempotency exists to provide. Re-using a key with a \
                    *different* body is a client bug rather than a retry, and is refused \
                    with `IDEMPOTENCY_KEY_REUSED`.

                    Seats are taken under `SELECT … FOR UPDATE` on the flight row, so \
                    two requests for the last seat serialise instead of overselling. A \
                    holder that outlasts the three-second `lock_timeout` surfaces as 503 \
                    with `Retry-After`, not as a 500.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booked, or an earlier booking replayed.",
                    headers = @Header(name = "Location", description = "`/api/v1/bookings/{bookingId}`",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = """
                    `VALIDATION_FAILED` — a field is blank, out of range or has the wrong characters; \
                    `fieldErrors` names each one. `MALFORMED_REQUEST` — the body is empty, not valid \
                    JSON or not a JSON object, a field has the wrong JSON type or is too large for its \
                    type, or `seats` is missing, null, a string, or written with a decimal point or an \
                    exponent (`2.0` included). This one has the `{code, message, timestamp}` shape.""",
                    content = @Content(schema = @Schema(oneOf = {ValidationErrorResponse.class, ErrorResponse.class}))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description =
                    "`FORBIDDEN` — authenticated, but without `flights:write`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`FLIGHT_NOT_FOUND`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "415", description =
                    "`UNSUPPORTED_MEDIA_TYPE` — the `Content-Type` is missing or is not `application/json`. YAML is refused too.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = """
                    `INSUFFICIENT_SEATS` — fewer seats remain than requested, and a \
                    smaller request can succeed. `FLIGHT_NOT_BOOKABLE` — the flight is \
                    cancelled, departed or arrived, and no retry will ever succeed. \
                    `IDEMPOTENCY_KEY_REUSED` — the key is known and was used for a \
                    different request.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description =
                    "`LOCK_TIMEOUT` — the flight row was held past `lock_timeout`. Carries `Retry-After`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BookingDto> book(@Valid @RequestBody BookingRequest request) {
        BookingDto booking = bookingService.book(request);
        URI location = UriComponentsBuilder.fromPath("/api/v1/bookings/{bookingId}")
                .build(booking.bookingId());
        return ResponseEntity.created(location).body(booking);
    }

    /** The target of the {@code Location} header above. */
    @Operation(summary = "Get a booking")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The booking, cancelled or not."),
            @ApiResponse(responseCode = "400", description = "`MALFORMED_REQUEST` — the id is not a number.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:read` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`BOOKING_NOT_FOUND`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{bookingId}")
    public BookingDto get(@PathVariable Long bookingId) {
        return bookingService.findById(bookingId);
    }

    /**
     * Oldest first by default. {@link SortPolicy} appends {@code id}, because
     * {@code createdAt} is not unique and pages would otherwise overlap or skip
     * rows, and rejects a property the endpoint does not offer. The repository
     * method declares its own {@code @Query}, so Spring Data never checks it.
     */
    @Operation(summary = "List the bookings on a flight")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = """
                    A page of bookings, oldest first unless `sort` says otherwise. An unknown \
                    flight number is an empty page."""),
            @ApiResponse(responseCode = "400", description = """
                    `UNKNOWN_SORT_PROPERTY` — `sort` names a property this endpoint does not offer. \
                    `MALFORMED_REQUEST` — `flightNumber` is missing, or `page` times `size` is \
                    larger than 2147483647.""",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:read` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public Page<BookingDto> byFlight(
            @RequestParam String flightNumber,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return bookingService.findByFlightNumber(flightNumber, SortPolicy.stable(pageable, SORTABLE, TEXTUAL));
    }

    /**
     * 200 with the cancelled booking rather than 204, because {@code cancelledAt}
     * on a retried call is the time of the original cancellation, which a client
     * reconciling its own state needs. See {@code BookingWriter.cancelBooking}.
     */
    @Operation(
            summary = "Cancel a booking",
            description = """
                    Releases the seats and returns the cancelled booking.

                    Cancelling an already-cancelled booking is a 200 no-op rather than an \
                    error: the caller asked for a state the system is already in, and the \
                    seats are released only once. Nothing is deleted — the row keeps \
                    its `cancelledAt`, so the history survives.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description =
                    "Cancelled, or already cancelled. The body is the booking either way."),
            @ApiResponse(responseCode = "400", description = "`MALFORMED_REQUEST` — the id is not a number.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "`FORBIDDEN` — `flights:write` is required.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description =
                    "`BOOKING_NOT_FOUND` — its own code, so a 404 here never claims the flight is missing.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "`LOCK_TIMEOUT`, with `Retry-After`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{bookingId}")
    public BookingDto cancel(@PathVariable Long bookingId) {
        return bookingService.cancel(bookingId);
    }
}
