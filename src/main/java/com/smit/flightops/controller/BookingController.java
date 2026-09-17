package com.smit.flightops.controller;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.service.BookingService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

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

    @GetMapping
    public List<BookingDto> byFlight(@RequestParam String flightNumber) {
        return bookingService.findByFlightNumber(flightNumber);
    }
}
