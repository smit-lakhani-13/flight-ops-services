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
 * Retention, proved on a real database rather than on a mock.
 *
 * <p>A mocked repository would have made these four tests pass without ever
 * running the statement they are about — and the statement is the only part
 * that can be wrong. {@code DELETE … WHERE id IN (SELECT … ORDER BY id LIMIT n
 * FOR UPDATE SKIP LOCKED)} either works on the database in front of it or does
 * not, and the answer is not knowable from the Java.
 *
 * <p>Retention is squeezed to the one-hour floor and the batch to a single row,
 * so the loop, the ceiling and the short-batch exit all happen within a test
 * method rather than only in production at some scale nobody here can create.
 *
 * <p>Not {@code @Transactional}: the pruner commits each batch through its own
 * {@link org.springframework.transaction.support.TransactionTemplate}, and a
 * test transaction wrapped around that would be a different shape from the
 * thing being tested. Rows therefore survive between methods, which is what
 * {@link #clearTheTable()} is for.
 *
 * <p>In {@code com.smit.flightops.service} rather than beside the other outbox
 * tests so that {@link OutboxPruner#MAX_BATCHES_PER_RUN} can be read rather
 * than copied. A test that hard-codes 50 keeps passing after somebody changes
 * the ceiling to 5, and tests the number instead of the behaviour.
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
     * The test that matters most, because the failure it guards against is
     * silent and permanent: a pruner that deleted unpublished rows would
     * quietly destroy events that had never been sent, and the booking they
     * describe would simply never reach the consumer. No error, no retry, no
     * trace of what went missing.
     *
     * <p>The row here is older than every other row in the class and has no
     * {@code published_at} at all — exactly the shape a {@code WHERE
     * published_at < cutoff} written against a database with two-valued logic
     * would sweep up.
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
     * With the batch size at one row, {@link OutboxPruner#MAX_BATCHES_PER_RUN}
     * is reached after fifty deletes and the run stops with work left over —
     * the behaviour that keeps a first run against a huge backlog from holding
     * a connection and a scheduler thread for as long as it takes to delete
     * everything. The second call proves the leftover is not stranded.
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
