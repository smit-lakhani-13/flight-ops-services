package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Deliberately NOT {@code @Transactional} on this class: that is exactly what
 * would mask the bug. {@code BookingService.findById} has no transaction
 * boundary of its own, so {@code bookingRepository.findById} — the plain
 * inherited {@code JpaRepository} method, not the {@code JOIN FETCH} query
 * {@code findByIdempotencyKey} uses — opens and closes its own short session,
 * and {@code BookingDto.from} dereferences the lazy {@code Booking.flight}
 * proxy after that session is already closed. A real sequential
 * create-then-fetch, through the real service layer, against real H2 (the
 * default profile, no Testcontainers needed), reproduces it every time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BookingFindByIdLazyLoadingTest {

    @Autowired private BookingService bookingService;
    @Autowired private FlightRepository flightRepository;

    private static final Instant SOON = Instant.now().plus(Duration.ofHours(8)).truncatedTo(ChronoUnit.MICROS);

    @Test
    void sequentialCreateThenFetchByIdDoesNotThrow() {
        flightRepository.saveAndFlush(new Flight("UA900", "EWR", "LHR", 180, SOON));

        BookingDto created = bookingService.book(
                new BookingRequest("UA900", "Smit Lakhani", 2, "lazy-load-repro-1"));

        assertThatCode(() -> bookingService.findById(created.bookingId()))
                .as("BookingService.findById has no @Transactional; BookingDto.from touches " +
                    "the lazy Booking.flight proxy after the repository's own short session " +
                    "has already closed")
                .doesNotThrowAnyException();
    }
}
