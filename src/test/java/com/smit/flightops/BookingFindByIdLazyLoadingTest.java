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
 * A create-then-fetch through the real service on H2, guarding the
 * {@code @Transactional(readOnly = true)} on {@code BookingService.findById}.
 * The inherited {@code findById} has no {@code JOIN FETCH}, so {@code BookingDto.from}
 * reads the lazy {@code Booking.flight} and needs an open session. The class is not
 * {@code @Transactional}, because a test transaction would keep the session open and
 * hide a missing annotation.
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
                new BookingRequest("UA900", "Jane Doe", 2, "lazy-load-repro-1"));

        assertThatCode(() -> bookingService.findById(created.bookingId()))
                .as("BookingService.findById needs a transaction open while BookingDto.from " +
                    "touches the lazy Booking.flight proxy")
                .doesNotThrowAnyException();
    }
}
