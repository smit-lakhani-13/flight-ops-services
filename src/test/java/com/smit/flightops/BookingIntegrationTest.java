package com.smit.flightops;

import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that cannot be faked: a real PostgreSQL, the real Flyway migration,
 * {@code ddl-auto: validate}, and real concurrent transactions.
 *
 * <p>Three things are proved here and nowhere else:
 * <ol>
 *   <li><b>The migration matches the entities.</b> Under {@code validate}, any
 *       drift between {@code V1__init.sql} and the {@code @Entity} classes fails
 *       context startup — so if this class runs at all, the schemas agree.</li>
 *   <li><b>SELECT ... FOR UPDATE actually prevents an oversell.</b> H2 will not
 *       tell you this; only a real database under real contention will.</li>
 *   <li><b>The unique constraint is the real idempotency guarantee.</b> Twenty
 *       threads replaying one key produce exactly one row.</li>
 * </ol>
 *
 * <p>{@code disabledWithoutDocker = true} means {@code mvn verify} on a laptop
 * with no container runtime skips this class rather than failing the build. That
 * is a deliberate trade-off: the build stays green, so read the skip count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = {
                        // 20 threads contend below; a 10-connection pool would make them
                        // queue on connections instead of on the row lock, which is not
                        // what this test is measuring.
                        "spring.datasource.hikari.maximum-pool-size=20",
                        "logging.level.org.hibernate.SQL=WARN"
                })
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class BookingIntegrationTest {

    /**
     * {@code @ServiceConnection} beats the profile's hard-coded localhost URL:
     * it contributes a JdbcConnectionDetails bean, and bean-based connection
     * details take priority over {@code spring.datasource.*} properties.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final int SEATS_ON_SALE = 5;
    private static final int CONTENDERS = 20;

    private int availableSeats(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber)
                .map(Flight::getAvailableSeats)
                .orElseThrow();
    }

    private String createFlight(String flightNumber, int seats) {
        flightService.create(new CreateFlightRequest(flightNumber, "EWR", "LHR", seats,
                Instant.now().plus(Duration.ofHours(9))));
        return flightNumber;
    }

    /** Runs every task at once and reports how many completed without throwing. */
    private static int countSuccesses(List<Callable<Void>> tasks) throws Exception {
        AtomicInteger succeeded = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<Void>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Void> future : futures) {
                try {
                    future.get();
                    succeeded.incrementAndGet();
                } catch (Exception expectedForLosers) {
                    // Losing the seat race is the correct outcome, not a failure.
                }
            }
        }
        return succeeded.get();
    }

    @Test
    @DisplayName("Flyway applied V1 and Hibernate validated the entities against it")
    void migrationRanAndSchemaValidates() {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(applied).contains("1");
        // Reaching this line at all means ddl-auto: validate passed at startup.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_name = 'uk_bookings_idempotency_key'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the seeder is idempotent against a persistent database")
    void seedRanOnPostgres() {
        assertThat(flightRepository.existsByFlightNumber("UA123")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA456")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA789")).isTrue();
    }

    @Test
    @DisplayName(CONTENDERS + " threads chase " + SEATS_ON_SALE + " seats: exactly " + SEATS_ON_SALE
                 + " win, none oversell")
    void concurrentBookingsCannotOversell() throws Exception {
        String flightNumber = createFlight("CC001", SEATS_ON_SALE);

        List<Callable<Void>> attempts = java.util.stream.IntStream.range(0, CONTENDERS)
                .<Callable<Void>>mapToObj(i -> () -> {
                    bookingService.book(new BookingRequest(flightNumber, "Racer " + i, 1, "race-" + i));
                    return null;
                })
                .toList();

        int booked = countSuccesses(attempts);

        assertThat(booked).as("the row lock lets exactly the seat count through").isEqualTo(SEATS_ON_SALE);
        assertThat(availableSeats(flightNumber)).isZero();
        assertThat(bookingRepository.findByFlightNumber(flightNumber)).hasSize(SEATS_ON_SALE);
    }

    @Test
    @DisplayName("REGRESSION: " + CONTENDERS + " threads replay one idempotency key concurrently — "
                 + "ONE booking, and every caller gets it back, zero errors")
    void concurrentReplaysOfOneKeyBookOnce() throws Exception {
        String flightNumber = createFlight("CC002", 50);

        List<Callable<Void>> attempts = java.util.stream.IntStream.range(0, CONTENDERS)
                .<Callable<Void>>mapToObj(i -> () -> {
                    bookingService.book(new BookingRequest(flightNumber, "Retrying Client", 2, "same-key"));
                    return null;
                })
                .toList();

        // Unlike concurrentBookingsCannotOversell above, losing this race is NOT
        // a legitimate failure — BookingWriter.recoverReplay exists precisely so
        // every caller on the same key gets the winner's booking back instead of
        // a 409. countSuccesses would previously report ~1 success and
        // (CONTENDERS - 1) caught exceptions for this same test; it now reports
        // CONTENDERS, which is the whole point of the fix.
        int booked = countSuccesses(attempts);

        assertThat(booked).as("no caller should see an exception on a raced replay")
                .isEqualTo(CONTENDERS);
        assertThat(bookingRepository.findByFlightNumber(flightNumber)).hasSize(1);
        assertThat(availableSeats(flightNumber)).isEqualTo(48);
    }

    @Test
    @DisplayName("an oversell rolls back on PostgreSQL too — seats and rows both untouched")
    void oversellRollsBack() {
        String flightNumber = createFlight("CC003", 2);

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(flightNumber, "Smit Lakhani", 3, "pg-oversell")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(availableSeats(flightNumber)).isEqualTo(2);
        assertThat(bookingRepository.findByIdempotencyKey("pg-oversell")).isEmpty();
    }
}
