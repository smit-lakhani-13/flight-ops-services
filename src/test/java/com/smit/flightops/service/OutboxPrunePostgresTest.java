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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox's native statements, on the database they run against.
 *
 * <p>{@link OutboxPrunerTest} and the outbox tests cover behaviour on H2, which
 * parses {@code LIMIT ... FOR UPDATE SKIP LOCKED} permissively. An edit H2 accepts
 * and PostgreSQL rejects would pass the build and fail in a scheduled job. This
 * class proves that PostgreSQL accepts the prune and the claim, and that two
 * callers of either take disjoint rows while the first transaction is still open.
 *
 * <p>{@code disabledWithoutDocker = true}, like
 * {@link com.smit.flightops.BookingIntegrationTest}, so read the skip count.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                // The scheduled poller and pruner are parked: this class calls the
                // repository directly, and a background job would race it.
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
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private static final int ELIGIBLE_ROWS = 10;
    private static final int BATCH = 5;
    private static final int MAX_ATTEMPTS = 10;

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
        // Never published and older than everything: NULL < cutoff is unknown, not true.
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
     * The rows are gone either way, so the assertion is on what the second pruner
     * returns while the first transaction is still open.
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

        // The releasing countDown sits inside the try-with-resources: close() waits
        // for the task, and the task waits on that latch.
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

                // Not a performance bound: without SKIP LOCKED this call blocks
                // until releaseFirst fires, so the timeout is the failure.
                Integer secondCount;
                try {
                    secondCount = second.get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError(
                            "the second pruner blocked on the first pruner's row locks, so "
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
                .as("between them the two pruners took every eligible row, each only once")
                .isEmpty();
    }

    /**
     * The poller's claim, held open by one transaction while a second claims. The
     * row not yet due has the lowest id, so {@code ORDER BY id} would reach it first
     * if the {@code next_attempt_at} predicate let it through.
     */
    @Test
    @DisplayName("two pollers claiming at once take disjoint rows, and a row not yet due is never claimed")
    void concurrentClaimsAreDisjointAndSkipRowsNotYetDue() throws Exception {
        outboxEventRepository.deleteAll();
        Instant now = clock.instant();
        OutboxEvent notYetDue = new OutboxEvent("Booking", "PG-LATER", "BookingCreated", "{}", now);
        notYetDue.markFailed("earlier failure", now.plus(Duration.ofHours(1)));
        Long notYetDueId = outboxEventRepository.save(notYetDue).getId();
        for (int i = 0; i < ELIGIBLE_ROWS; i++) {
            outboxEventRepository.save(new OutboxEvent("Booking", "PG-CLAIM-" + i, "BookingCreated", "{}", now));
        }

        CountDownLatch firstHoldsItsRows = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<Long> firstIds;
        List<Long> secondIds;

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            try {
                Future<List<Long>> first = pool.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            List<Long> ids = claimIds(now);
                            firstHoldsItsRows.countDown();
                            try {
                                releaseFirst.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return ids;
                        }));

                assertThat(firstHoldsItsRows.await(30, TimeUnit.SECONDS))
                        .as("the first poller should have claimed its batch")
                        .isTrue();

                Future<List<Long>> second = pool.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> claimIds(now)));
                try {
                    secondIds = second.get(10, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new AssertionError(
                            "the second poller blocked on the first poller's row locks, so "
                                    + "FOR UPDATE SKIP LOCKED is not in effect", e);
                }

                releaseFirst.countDown();
                firstIds = first.get(30, TimeUnit.SECONDS);
            } finally {
                releaseFirst.countDown();
            }
        }

        assertThat(firstIds).hasSize(BATCH).doesNotContain(notYetDueId);
        assertThat(secondIds)
                .as("the second poller steps over the locked rows and takes the rest")
                .hasSize(BATCH)
                .doesNotContain(notYetDueId)
                .doesNotContainAnyElementsOf(firstIds);
    }

    private List<Long> claimIds(Instant now) {
        return outboxEventRepository.claimUnpublished(BATCH, MAX_ATTEMPTS, now).stream()
                .map(OutboxEvent::getId)
                .toList();
    }
}
