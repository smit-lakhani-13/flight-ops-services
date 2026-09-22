package com.smit.flightops.repository;

import com.smit.flightops.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
             ORDER BY id
             LIMIT :batchSize
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimUnpublished(@Param("batchSize") int batchSize);

    long countByPublishedAtIsNull();
}
