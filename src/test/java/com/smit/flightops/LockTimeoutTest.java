package com.smit.flightops;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the lock timeout end to end: a contended flight row produces 503 with
 * {@code Retry-After}, not a request that hangs and not a 500.
 *
 * <p>This test exists because the timeout is four words of configuration whose
 * effect depends on a chain of five things agreeing — the database raising the
 * right error code, Hibernate's dialect converting it to a {@code
 * LockTimeoutException}, Spring translating that to a {@code
 * PessimisticLockingFailureException}, the advice mapping that to 503, and the
 * header being set. Any one of those being wrong leaves the setting looking
 * configured and doing nothing useful, and none of it is visible by reading
 * {@code application.yml}. Asserting the status code is the only way to know.
 *
 * <p><b>Runs on H2, deliberately, and this is the one test where that is a real
 * compromise worth naming.</b> Production is PostgreSQL, which raises SQLSTATE
 * 55P03 from a session {@code lock_timeout}; H2 raises error 50200 from {@code
 * SET LOCK_TIMEOUT}. Different mechanism, different code, same Hibernate
 * exception type, so what this proves is every link in the chain from Hibernate
 * outwards — which is the part written in this repository. The first link, that
 * PostgreSQL's {@code lock_timeout} fires at all, is the database's own
 * documented behaviour and is the piece this test does not cover. Running it
 * against PostgreSQL needs Docker and would put it with
 * {@link BookingIntegrationTest} in the group that skips without it; keeping it
 * on H2 means it runs everywhere, including on a laptop with no Docker and in
 * CI, which for a regression guard is worth more than the extra realism.
 *
 * <p>250ms rather than the 3s the real profiles use: this test wants the
 * timeout to fire, and waiting three seconds to find that out is three seconds
 * added to every build.
 *
 * <p>The latch is not decoration. The whole test depends on the lock being held
 * *before* the HTTP request goes out, and a {@code Thread.sleep} guess would
 * either be flaky on a loaded machine or slow on a fast one.
 */
@SpringBootTest(properties = {
        // Its own database, so holding a row lock for the duration cannot
        // affect any other test sharing a cached context.
        "spring.datasource.url=jdbc:h2:mem:locktimeouttest;DB_CLOSE_DELAY=-1",
        "spring.datasource.hikari.connection-init-sql=SET LOCK_TIMEOUT 250"
})
@AutoConfigureMockMvc
/**
 * Authenticated as a caller holding both scopes, because these tests are about
 * the error contract rather than about who may call what. The filter chain is
 * fully in place — this is the real {@code SecurityConfig} — so without this
 * every assertion below would be made against a 401.
 *
 * <p>{@code @WithMockUser} puts an {@code Authentication} straight into the
 * {@code SecurityContext} rather than sending an {@code Authorization} header,
 * so it proves nothing about the credentials themselves; that is
 * {@code SecurityRulesTest}'s job. The authority strings are taken from
 * {@code SecurityConfig}'s constants rather than retyped, so renaming a scope
 * breaks compilation here instead of turning every one of these into a silent
 * 403.
 */
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

        // The inner try/finally is not redundant, and getting it wrong cost
        // this test thirty seconds per run. Java 21 made ExecutorService
        // AutoCloseable, and its close() calls shutdown() and then BLOCKS
        // until the submitted tasks finish. With the countDown in a finally
        // attached to the try-with-resources instead of inside it, close()
        // waits for the holder task, the holder task waits for a latch that is
        // only released after close() returns, and the two sit there until the
        // holder's own 30-second await times out. The build still passed,
        // which is what makes it worth a comment: a deadlock with a timeout on
        // it does not look like a deadlock, it looks like a slow test.
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

                // The status code is the client's contract; this counter is the
                // operator's. OPERATIONS.md names
                // rate(bookings_lock_timeout_total[5m]) as the leading indicator
                // for contention -- the alert that fires before the 503s become
                // a customer's problem -- and until now nothing asserted that
                // the meter is incremented at all. A refactor that moved the
                // increment off the 503 path would have left the alert
                // permanently silent with every test still green.
                assertThat(lockTimeoutCount())
                        .as("the 503 must also move bookings.lock_timeout")
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

        // The other half of the meter's contract: it counts contention, not
        // bookings. A counter that also moved on the happy path would make the
        // alert fire on ordinary traffic and get muted within a week.
        assertThat(lockTimeoutCount())
                .as("a successful booking must not move bookings.lock_timeout")
                .isEqualTo(timeoutsBefore);
    }
}
