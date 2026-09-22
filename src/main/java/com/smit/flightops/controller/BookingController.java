package com.smit.flightops.controller;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.ErrorResponse;
import com.smit.flightops.dto.ValidationErrorResponse;
import com.smit.flightops.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/bookings")
@Tag(name = "Bookings",
     description = "Seat reservations. Creating one is idempotent on `idempotencyKey`; "
                   + "cancelling one is idempotent on the booking\u0027s own state.")
public class BookingController {

    /**
     * What {@code ?sort=} may name on the list endpoint.
     *
     * <p>{@code idempotencyKey} is absent on purpose. It is already kept out of
     * {@link BookingDto} so a caller cannot read the keys of bookings it did not
     * make, and a sortable-but-invisible column gives the same information back
     * a comparison at a time. {@code flight} is absent because sorting by an
     * association sorts by its primary key, which is a number the API never
     * shows and nobody meant to ask for.
     */
    private static final java.util.Set<String> SORTABLE =
            java.util.Set.of("id", "createdAt", "passengerName", "seats", "cancelledAt");

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * 201 with a Location header on both the first call and every replay — a
     * retry is not an error, so it does not get an error status. The client
     * cannot tell the difference, which is exactly the point of idempotency.
     *
     * <p>That includes a replay that races the original: same key, original
     * still in-flight. {@link com.smit.flightops.service.BookingService#book}'s
     * own {@code findByIdempotencyKey} check only sees committed rows, so
     * both requests can pass it and reach the database at the same time —
     * but the loser doesn't surface that as an error. It recovers the
     * winner's booking and returns 201 too. See {@code BookingService.book}
     * and {@code BookingWriter} for the mechanism, and its Javadoc history
     * for the bug this fixed: the loser used to get 409, which broke the
     * "cannot tell the difference" claim this comment is now making truthfully.
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
            @ApiResponse(responseCode = "201", description =
                    "Booked, or an earlier booking replayed. `Location` points at the booking."),
            @ApiResponse(responseCode = "400", description =
                    "`VALIDATION_FAILED` — a field is missing or out of range; the response names each one.",
                    content = @Content(schema = @Schema(implementation = ValidationErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description =
                    "`FORBIDDEN` — authenticated, but without `flights:write`.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "`FLIGHT_NOT_FOUND`",
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
    @PostMapping
    public ResponseEntity<BookingDto> book(@Valid @RequestBody BookingRequest request) {
        BookingDto booking = bookingService.book(request);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.bookingId()))
                .body(booking);
    }

    /** The target of the Location header above. 404 if the id is unknown. */
    @GetMapping("/{bookingId}")
    public BookingDto get(@PathVariable Long bookingId) {
        return bookingService.findById(bookingId);
    }

    /**
     * Bookings on one flight, oldest first by default.
     *
     * <p>{@code @PageableDefault} rather than relying on Spring's own default
     * of 20: the sort is the part that matters. Without an explicit default
     * ordering, a paged query with no {@code ORDER BY} lets the database return
     * rows in whatever order it likes, and two requests for page 0 and page 1
     * can then overlap or skip rows entirely. The ordering used to be baked
     * into the repository query; moving to {@code Pageable} took it out, so it
     * is declared here instead of being silently lost.
     *
     * <p>{@code createdAt} alone did not finish the job, which is the second
     * half of the same bug: it is not unique, and twenty bookings made in the
     * same second have no order between them, so the overlap this Javadoc
     * claims to prevent came back at a smaller scale. {@link SortPolicy} adds
     * {@code id} as a tiebreaker and rejects a property this endpoint does not
     * offer — without it, {@code ?sort=nonsense} was a 500, because the
     * repository method declares its own {@code @Query} and Spring Data
     * therefore never resolved the property to complain about it.
     */
    @GetMapping
    public Page<BookingDto> byFlight(
            @RequestParam String flightNumber,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return bookingService.findByFlightNumber(flightNumber, SortPolicy.stable(pageable, SORTABLE));
    }

    /**
     * Cancels a booking and returns it, seats already credited back.
     *
     * <p>200 with the cancelled booking, not 204. A 204 would be defensible,
     * but this endpoint has something worth returning — {@code cancelledAt},
     * which on a retried call is the time of the *original* cancellation. That
     * is exactly what a client reconciling its own state needs, and it is
     * invisible behind an empty body.
     *
     * <p>Safe to retry: the second call finds the booking already cancelled,
     * releases nothing and returns the same record. See
     * {@code BookingWriter.cancelBooking}.
     */
    @Operation(
            summary = "Cancel a booking",
            description = """
                    Releases the seats and returns the cancelled booking.

                    Cancelling an already-cancelled booking is a 200 no-op rather than an \
                    error: the caller asked for a state the system is already in, and the \
                    seats are released exactly once. Nothing is deleted — the row keeps \
                    its `cancelledAt`, so the history survives.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description =
                    "Cancelled, or already cancelled. The body is the booking either way."),
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
