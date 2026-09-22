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
     * Claims a batch of unpublished events for this replica and nobody else.
     *
     * <p>A native query rather than {@code @Lock(PESSIMISTIC_WRITE)}, and the
     * reason is {@code SKIP LOCKED}, which is the only interesting thing in
     * this file.
     *
     * <p>With two replicas polling the same table, plain {@code FOR UPDATE}
     * makes replica B <em>block</em> on the rows replica A has claimed, then
     * publish them itself the moment A commits — so every event goes out twice,
     * and the second copy is emitted by the replica that waited. {@code SKIP
     * LOCKED} makes B step over locked rows and take the next unclaimed ones
     * instead. The two replicas end up draining disjoint halves of the backlog
     * with no coordination, no leader election and no distributed lock. It is
     * the single line that makes this poller safe to run on every pod rather
     * than on a designated one.
     *
     * <p>{@code LIMIT} is not optional either. Without it a replica claims the
     * entire backlog, holds those locks for as long as the sends take, and a
     * queue outage turns into one enormous transaction that blocks every other
     * replica out of the table completely — the failure mode {@code SKIP
     * LOCKED} was added to prevent, reintroduced by the missing bound.
     *
     * <p>The ordering is by {@code id}, which is insertion order, so events are
     * published in roughly the order they occurred. Only roughly: two replicas
     * draining disjoint batches concurrently can interleave, so this is
     * <em>not</em> a total ordering guarantee and nothing downstream should
     * depend on one. The consumer's DynamoDB key is built from the booking's
     * own timestamp precisely so that it does not have to.
     */
    @Query(value = """
            SELECT * FROM outbox_events
             WHERE published_at IS NULL
               AND attempts < :maxAttempts
             ORDER BY id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize,
                                       @Param("maxAttempts") int maxAttempts);

    /** Unpublished and still retryable: the poller's backlog. */
    long countByPublishedAtIsNullAndAttemptsLessThan(int maxAttempts);

    /**
     * Unpublished and out of attempts. These will never be claimed again, so
     * this number going above zero is an operator's problem, not a transient
     * one, and it is the gauge worth alerting on.
     *
     * <p>Re-driving one, once the cause is fixed, is a single statement:
     * {@code UPDATE outbox_events SET attempts = 0 WHERE id = ?}.
     */
    long countByPublishedAtIsNullAndAttemptsGreaterThanEqual(int maxAttempts);

    /**
     * Deletes up to {@code limit} published rows older than {@code cutoff},
     * returning how many went.
     *
     * <p>The subquery is what keeps the delete bounded. {@code DELETE FROM
     * outbox_events WHERE published_at < :cutoff} is one line shorter and can
     * take a multi-million-row lock, write a WAL segment large enough to stall
     * replication and hold the table against the poller for the duration. The
     * inner {@code SELECT ... ORDER BY id LIMIT} makes every statement the same
     * small size no matter how far behind the pruner has fallen — which is
     * exactly the case that matters, because the first run after this feature
     * ships is the one with the whole backlog in front of it.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} on the inner select so that two
     * replicas pruning at the same moment take disjoint rows instead of one
     * blocking on the other — the same reasoning as {@link #claimUnpublished},
     * and the same one-line mechanism.
     *
     * <p>{@code published_at IS NOT NULL} is implied by {@code < :cutoff} in
     * PostgreSQL, since {@code NULL < anything} is unknown rather than true.
     * Stated anyway: a reader should not have to recall three-valued logic to
     * satisfy themselves that this cannot delete an unpublished event.
     *
     * <p>There is no index on {@code published_at}, and {@code ORDER BY id} is
     * what makes that sustainable rather than an oversight. Rows are inserted
     * in id order and published seconds later, so id order and publication
     * order agree: walking the primary key from the start finds the first
     * {@code limit} eligible rows within roughly {@code limit} rows examined
     * and stops, instead of scanning the table. An index on
     * {@code published_at} would buy nothing here and cost a write on the
     * booking transaction's hot path, which is the one place in this service
     * where latency is a customer's problem.
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
