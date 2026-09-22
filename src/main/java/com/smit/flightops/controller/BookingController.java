package com.smit.flightops.controller;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.service.BookingService;
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
public class BookingController {

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
     */
    @GetMapping
    public Page<BookingDto> byFlight(
            @RequestParam String flightNumber,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.ASC)
            Pageable pageable) {
        return bookingService.findByFlightNumber(flightNumber, pageable);
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
    @DeleteMapping("/{bookingId}")
    public BookingDto cancel(@PathVariable Long bookingId) {
        return bookingService.cancel(bookingId);
    }
}
