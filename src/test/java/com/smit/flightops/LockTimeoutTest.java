package com.smit.flightops;

import com.jayway.jsonpath.JsonPath;
import com.smit.flightops.config.SecurityConfig;
import com.smit.flightops.observability.BookingMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The lock timeout end to end: a booking or a booking cancellation behind a contended
 * flight row gives 503 with {@code Retry-After}, not a hang and not a 500. The setting only
 * works if the database error, Hibernate's {@code LockTimeoutException}, Spring's
 * translation, the advice and the header all agree.
 *
 * <p>It runs on H2, which raises error 50200 from {@code SET LOCK_TIMEOUT} where PostgreSQL
 * raises 55P03 from {@code lock_timeout}. Both reach the same Hibernate exception, so this
 * covers every link from Hibernate outwards and runs without Docker. PostgreSQL's own
 * timeout firing is covered in CI by {@code LockTimeoutPostgresTest}. 250ms keeps the
 * build fast; the latch makes sure the lock is held before the request goes out.
 * {@code @WithMockUser} holds both scopes because the subject is the error contract.
 */
@SpringBootTest(properties = {
        // Its own database, so the held row lock cannot touch another test's cached context.
        "spring.datasource.url=jdbc:h2:mem:locktimeouttest;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 250"
})
@AutoConfigureMockMvc
@WithMockUser(authorities = {SecurityConfig.SCOPE_READ, SecurityConfig.SCOPE_WRITE})
class LockTimeoutTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FlightRepository flightRepository;
    @Autowired private MeterRegistry meterRegistry;

    private double lockTimeoutCount() {
        Counter counter = meterRegistry.find(BookingMetrics.LOCK_TIMEOUT).counter();
        return counter == null ? 0d : counter.count();
    }

    @Test
    @DisplayName("a booking that cannot get the flight row lock is 503 with Retry-After, not 500")
    void contendedFlightRowGives503() throws Exception {
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        double timeoutsBefore = lockTimeoutCount();

        // The inner try/finally must stay inside the try-with-resources.
        // ExecutorService.close() waits for the holder task, and the holder waits
        // for releaseLock, so the countDown has to run before close() does.
        try (ExecutorService holder = Executors.newSingleThreadExecutor()) {
            try {
                holder.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    // SELECT ... FOR UPDATE on the same row the booking path wants.
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

                mockMvc.perform(post("/api/v1/bookings")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"flightNumber":"UA123","passengerName":"Ada Lovelace","seats":1,
                                         "idempotencyKey":"lock-timeout-1"}
                                        """))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));

                // The status is the client's contract and this counter the operator's:
                // OPERATIONS.md alerts on rate(bookings_lock_timeout_total[5m]).
                assertThat(lockTimeoutCount())
                        .as("the 503 must also move bookings.lock_timeout")
                        .isEqualTo(timeoutsBefore + 1);
            } finally {
                releaseLock.countDown();
            }
        }
    }

    @Test
    @DisplayName("a cancellation that cannot get the flight row lock is 503 with Retry-After, not 500")
    void contendedFlightRowGives503OnCancel() throws Exception {
        String created = mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Katherine Johnson","seats":1,
                                 "idempotencyKey":"lock-timeout-cancel-1"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Number bookingId = JsonPath.read(created, "$.bookingId");

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        double timeoutsBefore = lockTimeoutCount();

        // The same holder as contendedFlightRowGives503. cancelBooking locks the
        // flight row before the booking row, so holding the flight row is enough.
        try (ExecutorService holder = Executors.newSingleThreadExecutor()) {
            try {
                holder.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
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

                mockMvc.perform(delete("/api/v1/bookings/{bookingId}", bookingId.longValue()))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(header().string("Retry-After", "1"))
                        .andExpect(jsonPath("$.code").value("LOCK_TIMEOUT"));

                assertThat(lockTimeoutCount())
                        .as("the cancellation's 503 must also move bookings.lock_timeout")
                        .isEqualTo(timeoutsBefore + 1);
            } finally {
                releaseLock.countDown();
            }
        }
    }


    @Test
    @DisplayName("with nothing holding the lock the same request succeeds, so the timeout is not just rejecting everything")
    void uncontendedBookingStillSucceeds() throws Exception {
        double timeoutsBefore = lockTimeoutCount();

        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Alan Turing","seats":1,
                                 "idempotencyKey":"lock-timeout-control-1"}
                                """))
                .andExpect(status().isCreated());

        // The meter counts contention, so the happy path must not move it.
        assertThat(lockTimeoutCount())
                .as("a successful booking must not move bookings.lock_timeout")
                .isEqualTo(timeoutsBefore);
    }
}
