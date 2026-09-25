package com.smit.flightops;

import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.OutboxPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The difference between "this row is poison" and "the queue is down".
 *
 * <p>{@code max-attempts} bounds the first. Counted per one-second tick, ten attempts
 * would take ten seconds, and a short SQS outage would dead-letter every pending event.
 * {@code next_attempt_at} makes the ceiling a bound on time: a failed row waits, the
 * wait doubles, and the re-drive has to clear it. No test sleeps; where a test needs the
 * wait to be over, it clears {@code next_attempt_at}, which the claim treats as due now.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxbackoff;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000",
                "app.outbox.retry-backoff=1m",
                "app.outbox.max-retry-backoff=4m",
                "app.outbox.max-attempts=10"
        })
class OutboxRetryBackoffTest {

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @MockitoBean private EventPublisher eventPublisher;

    @BeforeEach
    void startFromAnEmptyTableWithAnUnreachableQueue() {
        outboxEventRepository.deleteAll();
        doThrow(new IllegalStateException("queue unreachable"))
                .when(eventPublisher).publish(anyString(), anyString(), anyMap());
    }

    private OutboxEvent unpublishedEvent(String aggregateId) {
        return outboxEventRepository.save(new OutboxEvent("Booking", aggregateId, "BookingCreated",
                "{\"bookingId\":\"" + aggregateId + "\"}", clock.instant()));
    }

    private OutboxEvent reread(OutboxEvent event) {
        return outboxEventRepository.findById(event.getId()).orElseThrow();
    }

    /** Clears next_attempt_at, which the claim treats as due now: the wait has run out. */
    private void makeClaimableNow(OutboxEvent event) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = NULL WHERE id = ?",
                event.getId());
    }

    /** Ten back-to-back drains stand in for a ten-second outage at a one-second poll. */
    @Test
    @DisplayName("ten drains in a row cost one attempt, not ten: the ceiling is on time, not on ticks")
    void aBurstOfDrainsDoesNotBurnTheCeiling() {
        OutboxEvent event = unpublishedEvent("BK001");

        for (int i = 0; i < 10; i++) {
            outboxPublisher.drainOutbox();
        }

        verify(eventPublisher, times(1)).publish(anyString(), anyString(), anyMap());
        OutboxEvent after = reread(event);
        assertThat(after.getAttempts()).as("one attempt, not ten").isEqualTo(1);
        assertThat(meterRegistry.get("outbox.dead").gauge().value())
                .as("nothing is abandoned by an outage shorter than the backoff")
                .isZero();
        assertThat(meterRegistry.get("outbox.pending").gauge().value())
                .as("and it is still on the gauge an operator is watching")
                .isEqualTo(1);
    }

    /** The wait and its doubling, read off the row so the test is deterministic. */
    @Test
    @DisplayName("the first retry waits retry-backoff, the second waits twice that, and the cap holds")
    void theWaitDoublesUpToTheCap() {
        OutboxEvent event = unpublishedEvent("BK002");

        outboxPublisher.drainOutbox();
        Instant first = reread(event).getNextAttemptAt();
        assertThat(first).isNotNull();
        assertThat(Duration.between(clock.instant(), first))
                .as("one minute, the configured base")
                .isBetween(Duration.ofSeconds(55), Duration.ofSeconds(65));

        makeClaimableNow(event);
        outboxPublisher.drainOutbox();
        assertThat(Duration.between(clock.instant(), reread(event).getNextAttemptAt()))
                .as("two minutes on the second failure")
                .isBetween(Duration.ofSeconds(115), Duration.ofSeconds(125));

        makeClaimableNow(event);
        outboxPublisher.drainOutbox();
        makeClaimableNow(event);
        outboxPublisher.drainOutbox();
        assertThat(Duration.between(clock.instant(), reread(event).getNextAttemptAt()))
                .as("eight minutes would be next, but max-retry-backoff is four")
                .isBetween(Duration.ofSeconds(235), Duration.ofSeconds(245));
        assertThat(reread(event).getAttempts()).isEqualTo(4);
    }

    /**
     * Resetting {@code attempts} alone leaves a row the claim still refuses until its
     * backoff runs out, so the re-drive clears {@code next_attempt_at} too.
     */
    @Test
    @DisplayName("the documented re-drive clears the retry clock as well as the counter")
    void theDocumentedRedriveClearsBothColumns() {
        OutboxEvent event = unpublishedEvent("BK003");
        outboxPublisher.drainOutbox();
        assertThat(reread(event).getNextAttemptAt()).as("waiting").isNotNull();

        doNothing().when(eventPublisher).publish(anyString(), anyString(), anyMap());

        // The statement from doc/OPERATIONS.md, doc/ARCHITECTURE.md and the README, verbatim.
        jdbcTemplate.update(
                "UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?",
                event.getId());

        outboxPublisher.drainOutbox();

        assertThat(reread(event).getPublishedAt())
                .as("the cause was fixed and the row went out on the next tick")
                .isNotNull();
    }
}
