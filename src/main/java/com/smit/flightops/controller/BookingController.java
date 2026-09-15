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
