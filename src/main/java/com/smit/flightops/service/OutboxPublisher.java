package com.smit.flightops.service;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Drains the outbox: claims a batch of unpublished events, hands each payload
 * to the transport, and marks the ones that were accepted.
 *
 * <p>This is the half of the outbox pattern that does the delivering. The other
 * half, {@link OutboxWriter}, does the recording, and the split between them is
 * the whole point: recording is transactional with the booking, delivering is
 * not transactional with anything.
 *
 * <h2>At-least-once, and why that is the correct choice rather than a
 * compromise</h2>
 * The order below is publish, then mark. A crash in between republishes the
 * event on the next tick. The alternative order — mark, then publish — loses
 * the event entirely on the same crash. There is no third option that is
 * atomic, because the queue and the database are two systems, which is the
 * same reason the outbox exists in the first place. Duplicates are recoverable
 * by a consumer that can absorb them; a lost booking event is not recoverable
 * by anyone. The Lambda's conditional write to DynamoDB is what absorbs them.
 *
 * <h2>Safe on every replica</h2>
 * No leader election, no distributed lock, no designated drainer pod. {@code
 * SKIP LOCKED} in {@code claimUnpublished} means concurrent replicas take
 * disjoint batches — see that query's Javadoc, which is where the reasoning
 * lives.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           OutboxProperties properties,
                           Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One drain.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}. {@code fixedRate} schedules
     * the next run a fixed time after the previous one <em>started</em>, so a
     * drain that takes longer than the interval — which is exactly what happens
     * when the queue is slow, the moment you least want it — has the next run
     * queued behind it and the one after that queued behind them. The backlog
     * that builds is of scheduler invocations, not of events, and it does not
     * drain when the queue recovers. {@code fixedDelay} measures from the
     * previous run's <em>finish</em>, so a slow drain simply slows the polling
     * down, which is the behaviour you want from a system under strain.
     *
     * <p>{@code @Scheduled} and {@code @Transactional} on the same method work
     * because the scheduler is handed the proxy, not the target — but only for
     * a public, non-final method on a proxied bean. Make it {@code private} or
     * {@code final} and the schedule still fires, the transaction silently does
     * not, and {@code SKIP LOCKED} stops protecting anything because the locks
     * are released the instant the query returns. It is a two-keyword change
     * with no compiler error and no runtime error.
     *
     * <p>The send does happen inside this transaction, which looks like the
     * thing this class was written to avoid. It is not the same thing: the rows
     * locked here are outbox rows, not the flight row on the request path, and
     * holding them for the duration of the send is what stops a second replica
     * publishing the same event. A slow queue delays events; before the outbox
     * it rejected bookings. That is the trade the batch size bounds.
     */
    @Scheduled(fixedDelayString = "${app.outbox.poll-interval}")
    @Transactional
    public void drainOutbox() {
        List<OutboxEvent> batch = outboxEventRepository.claimUnpublished(properties.batchSize());
        if (batch.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                eventPublisher.publish(event.getEventType(), event.getPayload());
                event.markPublished(clock.instant());
                published++;
            } catch (RuntimeException e) {
                // Per row, deliberately. Letting this propagate would roll the
                // whole batch back, so one poisoned event would undo the
                // successful sends beside it - and those payloads are already
                // on the queue, so the next tick would send them again. One bad
                // row must not turn into N duplicates.
                event.markFailed(e.toString());
                log.warn("Outbox event {} ({}) failed to publish on attempt {}: {}",
                        event.getId(), event.getEventType(), event.getAttempts(), e.toString());
            }
        }

        // Dirty checking flushes markPublished/markFailed on commit; no explicit
        // save() call. These are managed entities, loaded in this transaction.
        if (published < batch.size()) {
            log.warn("Outbox drain published {} of {} claimed events", published, batch.size());
        } else if (log.isDebugEnabled()) {
            log.debug("Outbox drain published {} event(s)", published);
        }
    }
}
