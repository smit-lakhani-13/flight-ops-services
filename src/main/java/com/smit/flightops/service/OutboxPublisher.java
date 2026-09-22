package com.smit.flightops.service;

import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.observability.OutboxMetrics;
import com.smit.flightops.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
 * <h2>A row does not retry forever</h2>
 * The claim query carries {@code AND attempts < :maxAttempts}, and that bound
 * is about availability rather than tidiness. The claim is {@code ORDER BY id},
 * so an event the transport structurally rejects — a payload it will refuse
 * identically on attempt 10,000 — is retried <em>first</em> on every single
 * tick, consuming the batch while live events queue up behind it. Without the
 * ceiling one malformed row is a total publishing outage that no amount of
 * waiting resolves. With it, the row drops out of the claim, {@code
 * outbox.dead} goes above zero, and the log names the id and the booking.
 * Bringing it back is deliberate and manual:
 * {@code UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?}.
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
    private final OutboxMetrics metrics;
    private final Clock clock;

    public OutboxPublisher(OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           OutboxProperties properties,
                           OutboxMetrics metrics,
                           Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.metrics = metrics;
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
        Instant now = clock.instant();
        List<OutboxEvent> batch = outboxEventRepository.claimUnpublished(
                properties.batchSize(), properties.maxAttempts(), now);
        if (batch.isEmpty()) {
            return;
        }

        int published = 0;
        for (OutboxEvent event : batch) {
            try {
                eventPublisher.publish(event.getEventType(), event.getPayload(), headersFor(event));
                event.markPublished(now);
                metrics.publishSucceeded();
                published++;
            } catch (RuntimeException e) {
                // Per row, deliberately. Letting this propagate would roll the
                // whole batch back, so one poisoned event would undo the
                // successful sends beside it - and those payloads are already
                // on the queue, so the next tick would send them again. One bad
                // row must not turn into N duplicates.
                event.markFailed(e.toString(), nextAttemptAt(now, event.getAttempts() + 1));
                metrics.publishFailed();

                if (event.getAttempts() >= properties.maxAttempts()) {
                    // The claim query will not return this row again. Said at
                    // WARN with the id in it because the row is now invisible
                    // to the poller and only a human can bring it back:
                    //   UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?
                    metrics.attemptsExhausted();
                    log.warn("Outbox event {} ({}) exhausted {} attempts and will not be retried. "
                             + "Booking {} has no published event. Last error: {}",
                             event.getId(), event.getEventType(), properties.maxAttempts(),
                             event.getAggregateId(), e.toString());
                } else {
                    log.warn("Outbox event {} ({}) failed to publish on attempt {} of {}: {}",
                            event.getId(), event.getEventType(), event.getAttempts(),
                            properties.maxAttempts(), e.toString());
                }
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

    /**
     * When a row that just failed its {@code attempt}-th attempt may be claimed
     * again: {@code retry-backoff} doubled once per attempt, capped at
     * {@code max-retry-backoff}.
     *
     * <p>Returns null — "claimable on the next tick" — when backoff is
     * configured to zero. That is not a production setting; it exists so a test
     * can drain twice in a row without moving a clock.
     *
     * <p>The doubling is written as a bounded loop rather than
     * {@code base << (attempt - 1)} on purpose: the loop stops at the cap, so
     * there is no attempt count and no configured base that can overflow it.
     * A shift is one character shorter and silently wrong at attempt 64.
     */
    private Instant nextAttemptAt(Instant now, int attempt) {
        long baseMillis = properties.retryBackoff().toMillis();
        if (baseMillis <= 0) {
            return null;
        }
        long capMillis = properties.maxRetryBackoff().toMillis();
        long delayMillis = baseMillis;
        for (int i = 1; i < attempt && delayMillis < capMillis; i++) {
            delayMillis <<= 1;
        }
        return now.plusMillis(Math.min(delayMillis, capMillis));
    }

    /**
     * The transport metadata for one row: the stored {@code traceparent}, if
     * the booking that produced it was made inside a traced request.
     *
     * <p>An absent trace context yields an empty map rather than a placeholder.
     * A fabricated traceparent is worse than none — a consumer cannot tell it
     * is fake, so it would stitch unrelated work into one trace and quietly
     * corrupt the very thing the field exists to provide.
     */
    private static Map<String, String> headersFor(OutboxEvent event) {
        String traceparent = event.getTraceparent();
        return traceparent == null || traceparent.isBlank()
                ? Map.of()
                : Map.of("traceparent", traceparent);
    }
}
