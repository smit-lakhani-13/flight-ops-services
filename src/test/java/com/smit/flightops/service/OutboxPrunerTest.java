package com.smit.flightops.service;

import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention rules, proved on an in-memory H2 database rather than on a mock.
 * Whether the native {@code DELETE ... FOR UPDATE SKIP LOCKED} is valid PostgreSQL
 * is {@link OutboxPrunePostgresTest}'s job.
 *
 * <p>Retention sits at the one-hour floor and the batch at one row, so the loop, the
 * ceiling and the short-batch exit all run inside a test. The class is not
 * {@code @Transactional} because the pruner commits each batch through its own
 * {@code TransactionTemplate}; {@link #clearTheTable()} resets rows instead. It lives
 * in this package so it can read {@link OutboxPruner#MAX_BATCHES_PER_RUN} instead of
 * copying 50.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxprune;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000",
                "app.outbox.retention=1h",
                "app.outbox.prune-interval=24h",
                "app.outbox.prune-batch-size=1"
        })
class OutboxPrunerTest {

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPruner outboxPruner;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private Clock clock;

    @BeforeEach
    void clearTheTable() {
        outboxEventRepository.deleteAll();
    }

    private OutboxEvent publishedAgo(String aggregateId, Duration age) {
        OutboxEvent event = new OutboxEvent("Booking", aggregateId, "BookingCreated",
                "{\"bookingId\":\"" + aggregateId + "\"}", clock.instant().minus(age));
        event.markPublished(clock.instant().minus(age));
        return outboxEventRepository.save(event);
    }

    private OutboxEvent neverPublished(String aggregateId, Duration age) {
        return outboxEventRepository.save(new OutboxEvent("Booking", aggregateId, "BookingCreated",
                "{\"bookingId\":\"" + aggregateId + "\"}", clock.instant().minus(age)));
    }

    private double prunedCount() {
        return meterRegistry.get("outbox.pruned").counter().count();
    }

    @Test
    @DisplayName("published rows older than the retention window are deleted, and counted")
    void oldPublishedRowsAreDeleted() {
        publishedAgo("PR001", Duration.ofHours(3));
        publishedAgo("PR002", Duration.ofHours(2));
        publishedAgo("PR003", Duration.ofDays(9));
        double before = prunedCount();

        outboxPruner.prunePublishedEvents();

        assertThat(outboxEventRepository.findAll()).isEmpty();
        assertThat(prunedCount() - before)
                .as("outbox.pruned is the only signal that this job ran at all")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a row published inside the retention window is left alone")
    void recentlyPublishedRowsAreKept() {
        OutboxEvent recent = publishedAgo("PR004", Duration.ofMinutes(5));
        OutboxEvent old = publishedAgo("PR005", Duration.ofHours(4));

        outboxPruner.prunePublishedEvents();

        assertThat(outboxEventRepository.findAllById(List.of(recent.getId(), old.getId())))
                .as("retention is the window in which 'did that booking publish?' is still answerable")
                .extracting(OutboxEvent::getId)
                .containsExactly(recent.getId());
    }

    /**
     * Deleting an unpublished row loses an event that was never sent, with no error
     * or retry. The row is older than any other here and has no {@code published_at},
     * the shape a {@code WHERE published_at < cutoff} under two-valued logic would sweep up.
     */
    @Test
    @DisplayName("an unpublished row is never pruned, however old it is")
    void unpublishedRowsAreNeverPruned() {
        OutboxEvent stuck = neverPublished("PR006", Duration.ofDays(400));

        outboxPruner.prunePublishedEvents();

        assertThat(outboxEventRepository.findById(stuck.getId()))
                .as("an undelivered event is not rubbish, it is a backlog")
                .isPresent();
    }

    /**
     * With one-row batches the run stops at {@link OutboxPruner#MAX_BATCHES_PER_RUN}
     * with work left over, so a large backlog does not hold a connection and a
     * scheduler thread for the whole delete. The second call picks up the leftover.
     */
    @Test
    @DisplayName("a run stops at the batch ceiling and the next run picks up the remainder")
    void theRunStopsAtTheCeilingAndResumes() {
        int eligible = OutboxPruner.MAX_BATCHES_PER_RUN + 1;
        for (int i = 0; i < eligible; i++) {
            publishedAgo("PR1" + i, Duration.ofHours(2));
        }

        outboxPruner.prunePublishedEvents();
        assertThat(outboxEventRepository.count())
                .as("fifty batches of one, then stop")
                .isEqualTo(1);

        outboxPruner.prunePublishedEvents();
        assertThat(outboxEventRepository.count()).isZero();
    }
}
