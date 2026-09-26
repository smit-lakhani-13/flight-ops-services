package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.service.BookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V9 to V11 make PostgreSQL enforce, checked against rows written the way
 * those migrations say matters: by plain SQL, bypassing the entities.
 *
 * <p>On H2 the schema comes from the entities, and {@code FlightRepositoryTest}
 * shows it refusing the same rows. {@code ddl-auto: validate} compares neither
 * check constraints nor nullability, so without this class nothing would notice
 * a migration that stopped enforcing either.
 *
 * <p>Skipped without a container runtime; CI runs it, and its "The PostgreSQL
 * tests ran" step fails if it skipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class SchemaConstraintsPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BookingService bookingService;

    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2099, 1, 1, 10, 0, 0, 0, ZoneOffset.UTC);

    /** A flight as a reference-data migration would write it, with no version column. */
    private void insertWithoutVersion(String flightNumber) {
        jdbcTemplate.update("""
                INSERT INTO flights (flight_number, origin, destination, total_seats,
                                     available_seats, status, departure_time)
                VALUES (?, 'EWR', 'LHR', 180, 180, 'SCHEDULED', ?)
                """, flightNumber, DEPARTURE);
    }

    @Test
    @DisplayName("Flyway applied V1 to V11, and V11 left a valid index in the default list's order")
    void migrationsAppliedAndIndexIsValid() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11");

        // V11 runs outside a transaction, so a failed build would leave an INVALID
        // index behind rather than roll back.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT i.indisvalid FROM pg_index i
                  JOIN pg_class c ON c.oid = i.indexrelid
                 WHERE c.relname = 'idx_flights_departure_time'
                """, Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT pg_get_indexdef('idx_flights_departure_time'::regclass)", String.class))
                .endsWith("USING btree (departure_time, id)");
    }

    @Test
    @DisplayName("ck_flights_status refuses a status that is not a FlightStatus constant, in any spelling")
    void statusOutsideFlightStatusIsRefused() {
        insertWithoutVersion("SC001");

        for (String status : List.of("CANCELED", "cancelled")) {
            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE flights SET status = ? WHERE flight_number = 'SC001'", status))
                    .as(status)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .satisfies(e -> {
                        SQLException sql = sqlExceptionIn(e);
                        assertThat(sql.getSQLState()).as("check_violation").isEqualTo("23514");
                        assertThat(sql.getMessage()).contains("ck_flights_status");
                    });
        }
    }

    /** So a constant added without the migration that widens the check fails here. */
    @Test
    @DisplayName("ck_flights_status accepts every FlightStatus constant")
    void everyFlightStatusIsAccepted() {
        insertWithoutVersion("SC002");

        for (FlightStatus status : FlightStatus.values()) {
            assertThat(jdbcTemplate.update(
                    "UPDATE flights SET status = ? WHERE flight_number = 'SC002'", status.name()))
                    .as(status.name())
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a flight inserted without a version starts at 0, and an explicit NULL is refused")
    void versionDefaultsToZeroAndIsNotNull() {
        insertWithoutVersion("SC003");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flights WHERE flight_number = 'SC003'", Long.class))
                .isZero();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE flights SET version = NULL WHERE flight_number = 'SC003'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    SQLException sql = sqlExceptionIn(e);
                    assertThat(sql.getSQLState()).as("not_null_violation").isEqualTo("23502");
                    assertThat(sql.getMessage()).contains("column \"version\"");
                });
    }

    /**
     * The failure V9 closes, end to end. Before it, this row's version was NULL,
     * Hibernate could not increment it at flush, and the booking was a 500.
     */
    @Test
    @DisplayName("a flight inserted by plain SQL, without a version, can be booked")
    void flightInsertedWithoutVersionCanBeBooked() {
        insertWithoutVersion("SC004");

        BookingDto booking = bookingService.book(new BookingRequest("SC004", "Jane Doe", 2, "sc-version-1"));

        assertThat(booking.bookingId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT available_seats FROM flights WHERE flight_number = 'SC004'", Integer.class))
                .isEqualTo(178);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM flights WHERE flight_number = 'SC004'", Long.class))
                .isEqualTo(1L);
    }

    /** The first SQLException in the cause chain. */
    private static SQLException sqlExceptionIn(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql;
            }
        }
        throw new AssertionError("no SQLException in the cause chain", thrown);
    }
}
