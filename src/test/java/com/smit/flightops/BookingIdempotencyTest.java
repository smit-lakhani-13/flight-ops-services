package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.LoggingEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The README's headline claim, proved end to end on H2 — real transactions, real
 * commits, no mocks and no Docker. Everything from the controller down is the
 * production wiring; only the database and the event transport differ.
 *
 * <p>Deliberately NOT {@code @Transactional}: a rolled-back test would let the
 * replay read uncommitted state from the same transaction and the test would
 * pass for the wrong reason. Because rows therefore survive each test, the
 * methods are ordered and use disjoint flight numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingIdempotencyTest {

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private EventPublisher eventPublisher;

    private int availableSeats(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber)
                .map(Flight::getAvailableSeats)
                .orElseThrow();
    }

    @Test
    @Order(1)
    @DisplayName("the seeder created the three demo flights the README curls against")
    void seederRan() {
        assertThat(flightRepository.findByFlightNumber("UA123"))
                .get()
                .satisfies(f -> {
                    assertThat(f.getTotalSeats()).isEqualTo(180);
                    assertThat(f.getAvailableSeats()).isEqualTo(180);
                    assertThat(f.getOrigin()).isEqualTo("EWR");
                    assertThat(f.getDestination()).isEqualTo("LHR");
                });
        assertThat(flightRepository.existsByFlightNumber("UA456")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA789")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("with no AWS configured the log publisher is wired, not the SQS one")
    void logPublisherIsActiveByDefault() {
        assertThat(eventPublisher).isInstanceOf(LoggingEventPublisher.class);
    }

    @Test
    @Order(3)
    @DisplayName("booking 3 seats then replaying the same key 5 times leaves 177 seats and ONE booking")
    void replayingTheSameKeyBooksOnce() {
        BookingRequest request = new BookingRequest("UA123", "Smit Lakhani", 3, "demo-1");

        BookingDto first = bookingService.book(request);
        assertThat(availableSeats("UA123")).isEqualTo(177);

        for (int attempt = 0; attempt < 5; attempt++) {
            BookingDto replay = bookingService.book(request);
            assertThat(replay.bookingId()).isEqualTo(first.bookingId());
            assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        }

        assertThat(availableSeats("UA123")).as("no seats lost to retries").isEqualTo(177);
        assertThat(bookingRepository.findByFlightNumber("UA123")).hasSize(1);
    }

    @Test
    @Order(4)
    @DisplayName("a different key on the same flight is a genuinely new booking")
    void differentKeyBooksAgain() {
        int before = availableSeats("UA456");

        bookingService.book(new BookingRequest("UA456", "Passenger A", 2, "key-a"));
        bookingService.book(new BookingRequest("UA456", "Passenger B", 2, "key-b"));

        assertThat(availableSeats("UA456")).isEqualTo(before - 4);
        assertThat(bookingRepository.findByFlightNumber("UA456")).hasSize(2);
    }

    @Test
    @Order(5)
    @DisplayName("overselling rolls the whole transaction back — no seats debited, no booking row")
    void oversellLeavesNoTrace() {
        flightService.create(new CreateFlightRequest("UA001", "EWR", "LHR", 2,
                Instant.now().plus(Duration.ofHours(6))));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("UA001", "Smit Lakhani", 3, "oversell-1")))
                .isInstanceOf(InsufficientSeatsException.class)
                .hasMessageContaining("UA001");

        assertThat(availableSeats("UA001")).isEqualTo(2);
        assertThat(bookingRepository.findByIdempotencyKey("oversell-1")).isEmpty();
        assertThat(bookingRepository.findByFlightNumber("UA001")).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("lower-case input still finds the flight, and the booking still reserves seats")
    void flightNumberIsNormalised() {
        flightService.create(new CreateFlightRequest("ua002", "ewr", "sfo", 10,
                Instant.now().plus(Duration.ofHours(6))));

        bookingService.book(new BookingRequest(" ua002 ", "Smit Lakhani", 1, "norm-1"));

        assertThat(availableSeats("UA002")).isEqualTo(9);
    }
}
