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
 * <p>{@code max-attempts} bounds the first. Until {@code next_attempt_at}
 * existed it also, accidentally, bounded the second — and far too tightly.
 * Attempts were counted per tick and the poller ticks every second, so ten
 * attempts took ten seconds: a brief SQS outage permanently dead-lettered every
 * pending event and every event written during it, none of them defective. The
 * gauge that is supposed to catch exactly that, {@code outbox.pending}, would
 * have fallen to zero as it happened, because a dead row is not a pending row.
 *
 * <p>These tests pin the three properties that make the ceiling a bound on time
 * rather than on ticks: a failed row waits, the wait doubles, and the wait is
 * part of what an operator has to clear to re-drive a row.
 *
 * <p>The waits here are minutes long, and no test sleeps. The clock is moved by
 * writing {@code next_attempt_at} into the past, which is also what makes these
 * assertions about the claim query rather than about {@code Thread.sleep}.
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

    /** Moves the row's retry clock into the past, which is what waiting would do. */
    private void makeClaimableNow(OutboxEvent event) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = NULL WHERE id = ?",
                event.getId());
    }

    /**
     * THE REGRESSION. Ten drains, back to back, is what a ten-second outage used
     * to look like at a one-second poll interval. It must not exhaust the row.
     */
    @Test
    @DisplayName("REGRESSION: ten drains in a row cost ONE attempt, not ten — the ceiling is on time, not on ticks")
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

    /**
     * The wait itself, and the doubling. Both are read off the row rather than
     * timed, so this test is deterministic and takes milliseconds.
     */
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
     * The re-drive an operator actually pastes. It had to change when this
     * column arrived: resetting {@code attempts} alone leaves a row that the
     * claim query still refuses, for as long as its last backoff has left to
     * run — which is the most confusing possible outcome for somebody who has
     * just fixed the cause and is watching for the event to go.
     */
    @Test
    @DisplayName("the documented re-drive clears the retry clock as well as the counter")
    void theDocumentedRedriveClearsBothColumns() {
        OutboxEvent event = unpublishedEvent("BK003");
        outboxPublisher.drainOutbox();
        assertThat(reread(event).getNextAttemptAt()).as("waiting").isNotNull();

        doNothing().when(eventPublisher).publish(anyString(), anyString(), anyMap());

        // Exactly the statement in OPERATIONS.md, ARCHITECTURE.md, the README
        // and three Javadoc comments. Resetting only `attempts` would not
        // publish anything here, and that is the point of running it verbatim.
        jdbcTemplate.update(
                "UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?",
                event.getId());

        outboxPublisher.drainOutbox();

        assertThat(reread(event).getPublishedAt())
                .as("the cause was fixed and the row went out on the next tick")
                .isNotNull();
    }
}
