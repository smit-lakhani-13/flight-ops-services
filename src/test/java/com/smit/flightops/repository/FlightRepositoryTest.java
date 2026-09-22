package com.smit.flightops.repository;

import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Derived-query names and the native query are only checked at context startup
 * or first execution, so a typo in a repository method is invisible until
 * something exercises it. These tests are that something.
 *
 * <p>{@code @DataJpaTest} rolls each test back and starts an embedded database,
 * so no test can see another's rows.
 */
@DataJpaTest
class FlightRepositoryTest {

    @Autowired private FlightRepository flightRepository;

    private static final Instant SOON = Instant.now().plus(Duration.ofHours(8)).truncatedTo(ChronoUnit.MICROS);

    private Flight save(String number, String origin, String destination, int seats) {
        return flightRepository.saveAndFlush(new Flight(number, origin, destination, seats, SOON));
    }

    @Test
    void findByFlightNumberIsCaseSensitiveAndExact() {
        save("UA123", "EWR", "LHR", 180);

        assertThat(flightRepository.findByFlightNumber("UA123")).isPresent();
        assertThat(flightRepository.findByFlightNumber("ua123")).isEmpty();
        assertThat(flightRepository.existsByFlightNumber("UA123")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("XX999")).isFalse();
    }

    @Test
    @DisplayName("the unique constraint is what really stops duplicate flight numbers")
    void duplicateFlightNumberIsRejectedByTheDatabase() {
        save("UA123", "EWR", "LHR", 180);

        assertThatThrownBy(() -> save("UA123", "ORD", "SFO", 150))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findByOriginAndDestinationFiltersOnBothColumns() {
        save("UA123", "EWR", "LHR", 180);
        save("UA789", "EWR", "SFO", 200);

        assertThat(flightRepository.findByOriginAndDestination("EWR", "LHR")).hasSize(1);
        assertThat(flightRepository.findByOriginAndDestination("EWR", "SFO")).hasSize(1);
        assertThat(flightRepository.findByOriginAndDestination("ORD", "LHR")).isEmpty();
    }

    @Test
    void pagedSearchRespectsPageSize() {
        save("UA123", "EWR", "LHR", 180);
        save("UA124", "EWR", "LHR", 180);
        save("UA125", "EWR", "LHR", 180);

        var page = flightRepository.findByOriginAndDestination("EWR", "LHR", PageRequest.of(0, 2));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("findBookable skips full and non-bookable flights, and keeps DELAYED ones")
    void findBookableFiltersOnSeatsAndStatus() {
        save("UA123", "EWR", "LHR", 180);
        Flight full = save("UA124", "EWR", "LHR", 2);
        full.reserveSeats(2);
        Flight cancelled = save("UA125", "EWR", "LHR", 180);
        cancelled.cancel();
        // A delayed flight is still selling seats — FlightStatus.isBookable()
        // says so, and this query has to agree with it. It did not until the
        // JPQL stopped hardcoding status = 'SCHEDULED'.
        Flight delayed = save("UA126", "EWR", "LHR", 180);
        delayed.updateStatus(FlightStatus.DELAYED);
        flightRepository.flush();

        assertThat(flightRepository.findBookable(1))
                .extracting(Flight::getFlightNumber)
                .containsExactlyInAnyOrder("UA123", "UA126");
    }

    @Test
    void findByStatusAndDepartureWindow() {
        save("UA123", "EWR", "LHR", 180);

        assertThat(flightRepository.findByStatusAndDepartureTimeBetween(
                FlightStatus.SCHEDULED, SOON.minus(Duration.ofMinutes(1)), SOON.plus(Duration.ofMinutes(1))))
                .hasSize(1);
        assertThat(flightRepository.findByStatusAndDepartureTimeBetween(
                FlightStatus.DEPARTED, SOON.minus(Duration.ofMinutes(1)), SOON.plus(Duration.ofMinutes(1))))
                .isEmpty();
    }

    @Test
    @DisplayName("the native LIMIT query executes — it is not portable, so it needs a test")
    void nativeQueryRuns() {
        save("UA123", "EWR", "LHR", 180);

        assertThat(flightRepository.findNextTen("EWR")).hasSize(1);
    }

    @Test
    void selectForUpdateReturnsTheRow() {
        save("UA123", "EWR", "LHR", 180);

        assertThat(flightRepository.findByFlightNumberForUpdate("UA123"))
                .get()
                .extracting(Flight::getAvailableSeats)
                .isEqualTo(180);
    }
}
