package com.smit.flightops;

import com.smit.flightops.config.DataSeeder;
import com.smit.flightops.dto.BookingDto;
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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tests that need a real PostgreSQL: the Flyway migrations,
 * {@code ddl-auto: validate}, and concurrent transactions.
 *
 * <ol>
 *   <li><b>The migrations match the entities.</b> Under {@code validate}, a table or
 *       column an {@code @Entity} expects but the migrations lack, or a column of the
 *       wrong type, fails context startup. It does not compare check constraints or
 *       indexes.</li>
 *   <li><b>{@code SELECT ... FOR UPDATE} prevents an oversell</b> under real contention.</li>
 *   <li><b>One key books once.</b> Twenty threads replaying one key produce one row,
 *       under the {@code FOR UPDATE} lock and the in-lock re-read; the unique
 *       constraint is the backstop for one key on two flights.</li>
 * </ol>
 *
 * <p>{@code disabledWithoutDocker = true} skips the class on a machine with no
 * container runtime, so read the skip count.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
                properties = {
                        // 20 threads contend below, and they should queue on the row
                        // lock, not on a 10-connection pool.
                        "spring.datasource.hikari.maximum-pool-size=20",
                        "logging.level.org.hibernate.SQL=WARN"
                })
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class BookingIntegrationTest {

    /**
     * {@code @ServiceConnection} contributes a {@code JdbcConnectionDetails} bean,
     * which outranks the profile's localhost URL. PostgreSQL 17, the major version
     * {@code compose.yaml} and {@code deploy/aws/data.yaml} pin, because this is
     * where the migrations are accepted or refused.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSeeder seeder;

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

    /**
     * Runs every task at once and returns what each one that completed without
     * throwing returned. Only {@code allowedFailure} counts as a losing contender;
     * any other cause, such as pool starvation or a lock timeout, fails the test
     * with its stack trace.
     */
    private static <T> List<T> resultsOfSuccesses(List<Callable<T>> tasks,
                                                  Class<? extends Throwable> allowedFailure) throws Exception {
        List<T> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<T> future : futures) {
                try {
                    results.add(future.get());
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (allowedFailure == null || !allowedFailure.isInstance(cause)) {
                        throw new AssertionError(
                                "a contender failed for the wrong reason: expected "
                                        + (allowedFailure == null ? "no failure at all"
                                                                  : allowedFailure.getSimpleName())
                                        + ", got " + (cause == null ? "null" : cause.getClass().getName()),
                                cause);
                    }
                    // Losing the seat race is the correct outcome, not a failure.
                }
            }
        }
        return results;
    }

    /** How many tasks completed without throwing, under the rules of {@link #resultsOfSuccesses}. */
    private static int countSuccesses(List<Callable<Void>> tasks,
                                      Class<? extends Throwable> allowedFailure) throws Exception {
        return resultsOfSuccesses(tasks, allowedFailure).size();
    }

    @Test
    @DisplayName("Flyway applied V1-V8 and Hibernate validated the entities against them")
    void migrationRanAndSchemaValidates() throws Exception {
        List<String> applied = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);

        assertThat(applied).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
        // A file on the classpath that Flyway did not pick up, such as a misnamed one.
        assertThat(applied).hasSameSizeAs(new PathMatchingResourcePatternResolver()
                .getResources("classpath:db/migration/V*.sql"));
        // Reaching this line at all means ddl-auto: validate passed at startup.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE constraint_name = 'uk_bookings_idempotency_key'", Integer.class))
                .isEqualTo(1);
    }

    /**
     * A second seeder run, as a second application start would make it. This matters
     * on PostgreSQL only: H2 is {@code create-drop}. A seeder that inserted a
     * flight again would fail on {@code uk_flights_flight_number}, so the run
     * itself is part of the check.
     */
    @Test
    @DisplayName("running the seeder twice inserts nothing the second time")
    void seedRanOnPostgres() {
        assertThat(flightRepository.existsByFlightNumber("UA123")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA456")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA789")).isTrue();

        long before = flightRepository.count();

        seeder.run(new DefaultApplicationArguments());

        assertThat(flightRepository.count())
                .as("a second seeder run must insert nothing")
                .isEqualTo(before);
        // The symptom a duplicate would cause: Optional lookups still work.
        assertThat(flightRepository.findByFlightNumber("UA123")).isPresent();
    }

    @Test
    @DisplayName(CONTENDERS + " threads chase " + SEATS_ON_SALE + " seats: " + SEATS_ON_SALE
                 + " win, none oversell")
    void concurrentBookingsCannotOversell() throws Exception {
        String flightNumber = createFlight("CC001", SEATS_ON_SALE);

        List<Callable<Void>> attempts = java.util.stream.IntStream.range(0, CONTENDERS)
                .<Callable<Void>>mapToObj(i -> () -> {
                    bookingService.book(new BookingRequest(flightNumber, "Racer " + i, 1, "race-" + i));
                    return null;
                })
                .toList();

        // Losing the seat race is expected; any other failure is a bug.
        int booked = countSuccesses(attempts, InsufficientSeatsException.class);

        assertThat(booked).as("the row lock lets the seat count through, no more and no fewer").isEqualTo(SEATS_ON_SALE);
        assertThat(availableSeats(flightNumber)).isZero();
        assertThat(bookingRepository.findByFlightNumber(flightNumber, Pageable.unpaged()))
                .hasSize(SEATS_ON_SALE);
    }

    @Test
    @DisplayName("REGRESSION: " + CONTENDERS + " threads replay one idempotency key concurrently: "
                 + "ONE booking, and every caller gets it back, zero errors")
    void concurrentReplaysOfOneKeyBookOnce() throws Exception {
        String flightNumber = createFlight("CC002", 50);

        List<Callable<BookingDto>> attempts = java.util.stream.IntStream.range(0, CONTENDERS)
                .<Callable<BookingDto>>mapToObj(i -> () ->
                        bookingService.book(new BookingRequest(flightNumber, "Retrying Client", 2, "same-key")))
                .toList();

        // null: no failure is legitimate. The winner gets its own booking back.
        // Every other caller gets the winner's, from the pre-check in
        // BookingService.book or through BookingWriter.recoverReplay.
        List<BookingDto> results = resultsOfSuccesses(attempts, null);

        assertThat(results).as("no caller should see an exception on a raced replay")
                .hasSize(CONTENDERS)
                .doesNotContainNull();
        assertThat(results.stream().map(BookingDto::bookingId).distinct())
                .as("every caller gets back the same booking id")
                .singleElement()
                .isNotNull();
        assertThat(bookingRepository.findByFlightNumber(flightNumber, Pageable.unpaged())).hasSize(1);
        assertThat(availableSeats(flightNumber)).isEqualTo(48);
    }

    @Test
    @DisplayName("an oversell is refused on PostgreSQL too, before any seat is debited or row written")
    void oversellRollsBack() {
        String flightNumber = createFlight("CC003", 2);

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest(flightNumber, "Jane Doe", 3, "pg-oversell")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(availableSeats(flightNumber)).isEqualTo(2);
        assertThat(bookingRepository.findByIdempotencyKey("pg-oversell")).isEmpty();
    }
}
