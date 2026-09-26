package com.smit.flightops;

import com.smit.flightops.config.SecurityConfig;
import com.smit.flightops.observability.BookingMetrics;
import com.smit.flightops.repository.FlightRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PostgreSQL's own {@code lock_timeout}, end to end. {@code LockTimeoutTest} covers the
 * same path on H2 with a 250 ms timeout and runs without Docker. This one keeps the
 * postgres profile's {@code SET lock_timeout = '3s'} and runs against PostgreSQL 17,
 * so it checks the link that test cannot: that PostgreSQL itself gives up after about
 * three seconds with SQLSTATE 55P03, and the caller still gets the 503.
 *
 * <p>Skipped without a container runtime; CI runs it, and its "The PostgreSQL tests
 * ran" step fails if it skipped.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
@WithMockUser(authorities = {SecurityConfig.SCOPE_READ, SecurityConfig.SCOPE_WRITE})
class LockTimeoutPostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FlightRepository flightRepository;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;

    private double lockTimeoutCount() {
        Counter counter = meterRegistry.find(BookingMetrics.LOCK_TIMEOUT).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("PostgreSQL's lock_timeout fires with 55P03 after about 3 s, and the caller gets 503 LOCK_TIMEOUT")
    void postgresLockTimeoutGives503() throws Exception {
        // The profile's connection-init-sql must have reached the pool, or the
        // timing below would be measuring something else.
        assertThat(jdbcTemplate.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("3s");

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        double timeoutsBefore = lockTimeoutCount();

        // The inner try/finally must stay inside the try-with-resources.
        // ExecutorService.close() waits for the holder task, and the holder waits
        // for releaseLock, so the countDown has to run before close() does.
        try (ExecutorService holder = Executors.newSingleThreadExecutor()) {
            try {
                holder.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    // SELECT ... FOR UPDATE on the row the booking path wants. DataSeeder
                    // inserts UA123 under the postgres profile too.
                    flightRepository.findByFlightNumberForUpdate("UA123").orElseThrow();
                    lockHeld.countDown();
                    try {
                        releaseLock.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                }));

                assertThat(lockHeld.await(30, TimeUnit.SECONDS))
                        .as("the holder thread should have taken the row lock")
                        .isTrue();

                long start = System.nanoTime();
                MvcResult result = mockMvc.perform(post("/api/v1/bookings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"flightNumber":"UA123","passengerName":"Grace Hopper","seats":1,
                                         "idempotencyKey":"pg-lock-timeout-1"}
                                        """))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"))
                        .andReturn();
                long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

                assertThat(result.getResolvedException()).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(sqlStateOf(result.getResolvedException()))
                        .as("PostgreSQL's lock_not_available")
                        .isEqualTo("55P03");
                // Not H2's 250 ms. The pool's 5 s connection timeout would not
                // carry 55P03, so the SQLSTATE above already rules it out.
                assertThat(elapsedMs).isBetween(2_500L, 15_000L);
                assertThat(lockTimeoutCount())
                        .as("the 503 must also move bookings.lock_timeout")
                        .isEqualTo(timeoutsBefore + 1);
            } finally {
                releaseLock.countDown();
            }
        }
    }

    /** The SQLState of the first SQLException in the cause chain, or null if there is none. */
    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
