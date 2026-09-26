package com.smit.flightops.repository;

import com.smit.flightops.entity.Flight;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Derived-query names are checked only at startup or first execution, so these tests
 * run the flight-number lookups, the paged origin-and-destination search and the
 * locking query that the service calls. Two more show Hibernate's H2 schema
 * refusing the rows V9 and V10 make PostgreSQL refuse, which
 * {@code SchemaConstraintsPostgresTest} checks there.
 *
 * <p>{@code @DataJpaTest} rolls each test back and starts an embedded database,
 * so no test can see another's rows.
 */
@DataJpaTest
class FlightRepositoryTest {

    @Autowired private FlightRepository flightRepository;
    @Autowired private TestEntityManager entityManager;

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

        assertThat(flightRepository.findByOriginAndDestination("EWR", "LHR", PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(flightRepository.findByOriginAndDestination("EWR", "SFO", PageRequest.of(0, 10)).getContent()).hasSize(1);
        assertThat(flightRepository.findByOriginAndDestination("ORD", "LHR", PageRequest.of(0, 10)).getContent()).isEmpty();
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
    void selectForUpdateReturnsTheRow() {
        save("UA123", "EWR", "LHR", 180);

        assertThat(flightRepository.findByFlightNumberForUpdate("UA123"))
                .get()
                .extracting(Flight::getAvailableSeats)
                .isEqualTo(180);
    }

    /**
     * Why {@code Flight} repeats no {@code ck_flights_status}: Hibernate declares the
     * column on H2 as an ENUM of FlightStatus's constants, and 22030 is H2's refusal
     * of a value the ENUM does not list.
     */
    @Test
    @DisplayName("H2 refuses a status that is not a FlightStatus constant, as ck_flights_status does on PostgreSQL")
    void statusOutsideFlightStatusIsRefused() {
        Flight flight = save("UA123", "EWR", "LHR", 180);

        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("UPDATE flights SET status = 'CANCELED' WHERE id = :id")
                .setParameter("id", flight.getId())
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getSQLState()).isEqualTo("22030"));
    }

    @Test
    @DisplayName("on H2 too, a flight inserted without a version starts at 0, and an explicit NULL is refused")
    void versionDefaultsToZeroAndIsNotNull() {
        entityManager.getEntityManager().createNativeQuery("""
                INSERT INTO flights (flight_number, origin, destination, total_seats,
                                     available_seats, status, departure_time)
                VALUES ('UA999', 'EWR', 'LHR', 180, 180, 'SCHEDULED',
                        TIMESTAMP WITH TIME ZONE '2099-01-01 10:00:00+00')
                """).executeUpdate();

        Number version = (Number) entityManager.getEntityManager()
                .createNativeQuery("SELECT version FROM flights WHERE flight_number = 'UA999'")
                .getSingleResult();
        assertThat(version.longValue()).isZero();
        assertThatThrownBy(() -> entityManager.getEntityManager()
                .createNativeQuery("UPDATE flights SET version = NULL WHERE flight_number = 'UA999'")
                .executeUpdate())
                .isInstanceOf(PersistenceException.class)
                .rootCause()
                .isInstanceOfSatisfying(SQLException.class,
                        sql -> assertThat(sql.getSQLState()).as("NULL not allowed").isEqualTo("23502"));
    }
}
