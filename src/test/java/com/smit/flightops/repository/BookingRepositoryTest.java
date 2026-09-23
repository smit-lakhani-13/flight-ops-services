package com.smit.flightops.repository;

import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The unique index on {@code idempotency_key} exists (the backstop for one key on two
 * flights), and the {@code JOIN FETCH} avoids the lazy load.
 */
@DataJpaTest
class BookingRepositoryTest {

    @Autowired private BookingRepository bookingRepository;
    @Autowired private FlightRepository flightRepository;
    @Autowired private TestEntityManager entityManager;

    private static final Instant SOON = Instant.now().plus(Duration.ofHours(8)).truncatedTo(ChronoUnit.MICROS);
    private static final Instant CREATED_AT = Instant.parse("2026-09-20T11:00:00Z");

    private Flight flight(String number) {
        return flightRepository.saveAndFlush(new Flight(number, "EWR", "LHR", 180, SOON));
    }

    @Test
    void findByIdempotencyKeyReturnsTheBooking() {
        Flight flight = flight("UA123");
        bookingRepository.saveAndFlush(new Booking(flight, "Smit Lakhani", 3, "demo-1", null, CREATED_AT));

        assertThat(bookingRepository.findByIdempotencyKey("demo-1"))
                .get()
                .extracting(Booking::getSeats)
                .isEqualTo(3);
        assertThat(bookingRepository.findByIdempotencyKey("demo-999")).isEmpty();
    }

    @Test
    @DisplayName("REGRESSION: JOIN FETCH on findByIdempotencyKey too, because the caller reads it after BookingService.book's transaction has closed")
    void findByIdempotencyKeyAlsoJoinFetchesTheFlight() {
        Flight flight = flight("UA123");
        bookingRepository.saveAndFlush(new Booking(flight, "Smit Lakhani", 3, "demo-1", null, CREATED_AT));
        entityManager.clear();

        Booking found = bookingRepository.findByIdempotencyKey("demo-1").orElseThrow();

        assertThat(Hibernate.isInitialized(found.getFlight()))
                .as("BookingService.book is not @Transactional; an unfetched proxy here " +
                    "would throw LazyInitializationException the moment BookingDto.from touches it")
                .isTrue();
        assertThat(found.getFlight().getFlightNumber()).isEqualTo("UA123");
    }

    @Test
    @DisplayName("the unique index rejects a second row with the same key, whatever the service checked")
    void duplicateIdempotencyKeyIsRejectedByTheDatabase() {
        Flight flight = flight("UA123");
        bookingRepository.saveAndFlush(new Booking(flight, "Smit Lakhani", 3, "demo-1", null, CREATED_AT));

        assertThatThrownBy(() ->
                bookingRepository.saveAndFlush(new Booking(flight, "Someone Else", 1, "demo-1", null, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("JOIN FETCH loads the flight eagerly, so listing bookings has no N+1")
    void joinFetchInitialisesTheFlight() {
        Flight flight = flight("UA123");
        bookingRepository.saveAndFlush(new Booking(flight, "Smit Lakhani", 3, "demo-1", null, CREATED_AT));
        bookingRepository.saveAndFlush(new Booking(flight, "Someone Else", 1, "demo-2", null, CREATED_AT));
        // Detach everything, so the flight can only be present if the query fetched it.
        entityManager.clear();

        List<Booking> bookings = bookingRepository.findByFlightNumber("UA123", Pageable.unpaged())
                .getContent();

        assertThat(bookings).hasSize(2);
        assertThat(bookings)
                .extracting(Booking::getPassengerName)
                .containsExactlyInAnyOrder("Smit Lakhani", "Someone Else");
        assertThat(Hibernate.isInitialized(bookings.get(0).getFlight()))
                .as("@ManyToOne is LAZY, so an initialised proxy proves the JOIN FETCH ran")
                .isTrue();
        assertThat(bookings.get(0).getFlight().getFlightNumber()).isEqualTo("UA123");
    }

    @Test
    void findByFlightNumberIgnoresOtherFlights() {
        bookingRepository.saveAndFlush(
                new Booking(flight("UA123"), "Smit Lakhani", 3, "demo-1", null, CREATED_AT));
        bookingRepository.saveAndFlush(
                new Booking(flight("UA456"), "Someone Else", 1, "demo-2", null, CREATED_AT));

        assertThat(bookingRepository.findByFlightNumber("UA123", Pageable.unpaged())).hasSize(1);
        assertThat(bookingRepository.findByFlightNumber("XX999", Pageable.unpaged())).isEmpty();
    }
}
