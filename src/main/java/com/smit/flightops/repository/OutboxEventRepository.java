package com.smit.flightops.repository;

import com.smit.flightops.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of unpublished events for this replica alone.
     *
     * <p>Native, for {@code SKIP LOCKED}: with plain {@code FOR UPDATE} a second
     * replica blocks on the claimed rows and publishes them again once the first
     * commits. With it, replicas drain disjoint batches without a leader or a
     * distributed lock. {@code LIMIT} bounds how many locks one replica holds while
     * it sends.
     *
     * <p>{@code next_attempt_at} spaces retries (see {@code V7__outbox_next_attempt_at.sql});
     * NULL, every row that never failed, means claimable now. {@code ORDER BY id} is
     * roughly insertion order, not a total order across replicas. It is also why
     * {@code attempts < :maxAttempts} matters: a poison row would otherwise head
     * every batch.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL
               AND attempts < :maxAttempts
               AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
             ORDER BY id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("now") Instant now);

    /** Unpublished and still retryable: the poller's backlog. */
    long countByPublishedAtIsNullAndAttemptsLessThan(int maxAttempts);

    /**
     * Unpublished and out of attempts, so never claimed again: the gauge to alert on.
     * Re-drive one with
     * {@code UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?}.
     */
    long countByPublishedAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);

    /**
     * Deletes up to {@code limit} published rows older than {@code cutoff}, returning
     * how many went.
     *
     * <p>The {@code ORDER BY id LIMIT} subquery keeps each statement small however far
     * behind the pruner is; an unbounded {@code DELETE} could lock millions of rows and
     * stall replication. {@code FOR UPDATE SKIP LOCKED} lets two replicas prune disjoint
     * rows, as in {@link #claimUnpublished}. {@code published_at IS NOT NULL} is implied
     * by the comparison but stated, so the SQL visibly cannot delete an unsent event.
     *
     * <p>There is no index on {@code published_at}. Walking the primary key is bounded
     * when a backlog exists; in steady state each run is one walk of the retained
     * rows, which at seven days of this service's volume is cheaper than a
     * {@code published_at} index maintained on the booking insert path.
     */
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
             WHERE id IN (
                   SELECT id FROM outbox_events
                    WHERE published_at IS NOT NULL
                      AND published_at < :cutoff
                    ORDER BY id
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
