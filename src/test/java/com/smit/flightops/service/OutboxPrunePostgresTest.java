package com.smit.flightops.service;

import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prune statement, on the database it actually runs against.
 *
 * <p>{@link OutboxPrunerTest} covers the pruner's behaviour — retention window,
 * batch ceiling, counters — and covers it on H2, where it runs everywhere and
 * costs nothing. What H2 cannot cover is the statement itself. The prune is a
 * {@code nativeQuery}, so Hibernate hands it to the driver unchanged, and H2
 * parses {@code LIMIT ... FOR UPDATE SKIP LOCKED} inside a subquery
 * permissively. An edit that H2 still accepts and PostgreSQL rejects would ship
 * green and fail at the first prune interval — an hour after deployment, in a
 * scheduled job whose failure surfaces only as retention quietly never running.
 *
 * <p>Two things are proved here and nowhere else:
 * <ol>
 *   <li><b>PostgreSQL accepts the statement.</b> Parsed, planned and executed
 *       by the real database, not by a compatibility mode.</li>
 *   <li><b>{@code SKIP LOCKED} does what the Javadoc on
 *       {@link OutboxEventRepository#deletePublishedBefore} claims.</b> Two
 *       replicas pruning at the same moment take <em>disjoint</em> rows. This
 *       is the claim that cannot be checked by reading the query: without
 *       {@code SKIP LOCKED} the second pruner blocks on the first's locks and
 *       then finds those rows gone, so it returns zero — same final state,
 *       completely different behaviour under load, and no test would notice.</li>
 * </ol>
 *
 * <p>{@code disabledWithoutDocker = true}, like {@link
 * com.smit.flightops.BookingIntegrationTest}: on a laptop with no container
 * runtime this skips rather than failing the build. Read the skip count.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // The scheduled poller and pruner are both parked: this class
                // drives the repository method directly, and a background job
                // deleting rows underneath it would make the assertions flaky
                // in a way that looks like a locking bug.
                "app.outbox.poll-interval=3600000",
                "app.outbox.prune-interval=24h",
                // Two connections are held open simultaneously below.
                "spring.datasource.hikari.maximum-pool-size=5"
        })
@ActiveProfiles("postgres")
@Testcontainers(disabledWithoutDocker = true)
class OutboxPrunePostgresTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private static final int ELIGIBLE_ROWS = 10;
    private static final int BATCH = 5;

    private void publishedAgo(String aggregateId, Duration age) {
        OutboxEvent event = new OutboxEvent("Booking", aggregateId, "BookingCreated",
                "{\"bookingId\":\"" + aggregateId + "\"}", clock.instant().minus(age));
        event.markPublished(clock.instant().minus(age));
        outboxEventRepository.save(event);
    }

    @Test
    @DisplayName("PostgreSQL accepts the prune statement, and it deletes only rows past the cutoff")
    void pruneStatementRunsOnPostgres() {
        outboxEventRepository.deleteAll();
        publishedAgo("PG-OLD-1", Duration.ofDays(9));
        publishedAgo("PG-OLD-2", Duration.ofDays(9));
        publishedAgo("PG-NEW-1", Duration.ofMinutes(1));
        // Never published, and older than everything else: the cutoff comparison
        // must not reach it, because NULL < anything is unknown, not true.
        outboxEventRepository.save(new OutboxEvent("Booking", "PG-UNSENT", "BookingCreated",
                "{}", clock.instant().minus(Duration.ofDays(30))));

        int deleted = new TransactionTemplate(transactionManager).execute(status ->
                outboxEventRepository.deletePublishedBefore(
                        clock.instant().minus(Duration.ofHours(1)), 100));

        assertThat(deleted).isEqualTo(2);
        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getAggregateId)
                .containsExactlyInAnyOrder("PG-NEW-1", "PG-UNSENT");
    }

    /**
     * The second pruner must not block on the first, and must not come back
     * empty. Both outcomes look identical once the dust settles — the rows are
     * gone either way — so the assertion is on what the second call returns
     * <em>while the first transaction is still open</em>.
     */
    @Test
    @DisplayName("two pruners running at once take disjoint rows instead of one blocking on the other")
    void concurrentPrunersDoNotBlockEachOther() throws Exception {
        outboxEventRepository.deleteAll();
        for (int i = 0; i < ELIGIBLE_ROWS; i++) {
            publishedAgo("PG-RACE-" + i, Duration.ofDays(9));
        }

        CountDownLatch firstHoldsItsRows = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // Same shape as LockTimeoutTest: the countDown that releases the holder
        // is inside the try-with-resources, because ExecutorService.close()
        // blocks until the submitted task finishes and the task is waiting on
        // that latch. Outside it, the two wait for each other.
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<Integer> first = pool.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            int n = outboxEventRepository.deletePublishedBefore(
                                    clock.instant().minus(Duration.ofHours(1)), BATCH);
                            firstHoldsItsRows.countDown();
                            try {
                                releaseFirst.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return n;
                        }));

                assertThat(firstHoldsItsRows.await(30, TimeUnit.SECONDS))
                        .as("the first pruner should have taken its batch")
                        .isTrue();

                Future<Integer> second = pool.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status ->
                                outboxEventRepository.deletePublishedBefore(
                                        clock.instant().minus(Duration.ofHours(1)), BATCH)));

                // 10 seconds is not a performance assertion. Without SKIP LOCKED
                // this call blocks until releaseFirst fires 30 seconds later, so
                // a timeout here IS the failure -- and it fails with "the second
                // pruner blocked" rather than with a bare TimeoutException.
                Integer secondCount;
                try {
                    secondCount = second.get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    throw new AssertionError(
                            "the second pruner blocked on the first pruner's row locks — "
                                    + "FOR UPDATE SKIP LOCKED is not in effect", e);
                }

                assertThat(secondCount)
                        .as("the second pruner should step over the locked rows and take the next batch")
                        .isEqualTo(BATCH);

                releaseFirst.countDown();
                assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(BATCH);
            } finally {
                releaseFirst.countDown();
            }
        }

        List<OutboxEvent> left = outboxEventRepository.findAll();
        assertThat(left)
                .as("between them the two pruners took every eligible row, each exactly once")
                .isEmpty();
    }
}
