package com.smit.flightops.service;

import com.smit.flightops.config.EventProperties;
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
 * to the transport, and marks the ones it accepted. {@link OutboxWriter} records
 * events inside the booking transaction; this class delivers them outside it.
 *
 * <p>Publish, then mark, so delivery is at-least-once: a crash between the two
 * republishes on the next tick, and the Lambda's conditional write absorbs the
 * duplicate. A row stops being claimed after {@code max-attempts} failures,
 * because the {@code ORDER BY id} claim would otherwise retry a poison row first
 * on every tick. {@code SKIP LOCKED} lets every replica run this safely.
 *
 * @see OutboxEventRepository#claimUnpublished
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

    /**
     * {@code events} comes first on purpose: Spring resolves constructor arguments
     * in order, so an unknown {@code app.events.publisher} fails on its binding, which
     * names the property, before the missing {@code EventPublisher} bean is reported.
     */
    public OutboxPublisher(EventProperties events,
                           OutboxEventRepository outboxEventRepository,
                           EventPublisher eventPublisher,
                           OutboxProperties properties,
                           OutboxMetrics metrics,
                           Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        log.info("Outbox publisher started with event transport '{}'", events.publisher());
    }

    /**
     * One drain.
     *
     * <p>{@code fixedDelay}, so a slow drain delays the next poll instead of queueing
     * scheduler runs behind it. {@code @Scheduled} and {@code @Transactional} work
     * together only while this method is public and non-final: the scheduler calls
     * the proxy. Otherwise the transaction is lost and {@code SKIP LOCKED} protects
     * nothing, with no error from the compiler or at runtime.
     *
     * <p>The send runs inside the transaction. The locks held are outbox rows, not
     * the flight row, and holding them is what stops a second replica sending the
     * same event. The batch size bounds how long they are held.
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
                // Caught per row: rolling the batch back would resend the rows
                // already accepted by the queue on the next tick.
                event.markFailed(e.toString(), nextAttemptAt(now, event.getAttempts() + 1));
                metrics.publishFailed();

                if (event.getAttempts() >= properties.maxAttempts()) {
                    // The claim will not return this row again, so only a person can
                    // bring it back:
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

        // Managed entities: dirty checking flushes markPublished/markFailed on commit.
        if (published < batch.size()) {
            log.warn("Outbox drain published {} of {} claimed events", published, batch.size());
        } else if (log.isDebugEnabled()) {
            log.debug("Outbox drain published {} event(s)", published);
        }
    }

    /**
     * When a row that just failed its {@code attempt}-th attempt may be claimed again:
     * {@code retry-backoff} doubled per attempt, capped at {@code max-retry-backoff}.
     * Null (claimable next tick) when backoff is zero, which only tests use. A loop
     * that stops at the cap, because a shift overflows at attempt 64.
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
     * The stored {@code traceparent}, if the booking was made inside a traced request.
     * No placeholder when absent: a consumer cannot tell a fabricated one is fake.
     */
    private static Map<String, String> headersFor(OutboxEvent event) {
        String traceparent = event.getTraceparent();
        return traceparent == null || traceparent.isBlank()
                ? Map.of()
                : Map.of("traceparent", traceparent);
    }
}
