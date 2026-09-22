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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * What happens to an event the transport will never accept.
 *
 * <p>The claim is {@code ORDER BY id}, so without the attempt ceiling the oldest failing
 * row heads every batch and one malformed row stops all publishing. These tests cover
 * what an operator needs: the row drops out, it is visible while out, and it can be
 * brought back. {@code max-attempts} is two here so the class stays short.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxpoison;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000",
                // Back-to-back drains with a fixed clock: the V7 backoff would make the
                // second claim nothing, so it is off here. OutboxRetryBackoffTest pins it.
                "app.outbox.retry-backoff=0",
                "app.outbox.max-attempts=2"
        })
class OutboxPoisonRowTest {

    private static final int MAX_ATTEMPTS = 2;

    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    @MockitoBean private EventPublisher eventPublisher;

    @BeforeEach
    void startFromAnEmptyTableWithARejectingTransport() {
        outboxEventRepository.deleteAll();
        doThrow(new IllegalStateException("the queue will never accept this"))
                .when(eventPublisher).publish(anyString(), anyString(), anyMap());
    }

    private OutboxEvent unpublishedEvent(String aggregateId) {
        return outboxEventRepository.save(new OutboxEvent("Booking", aggregateId, "BookingCreated",
                "{\"bookingId\":\"" + aggregateId + "\"}", clock.instant()));
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    @Test
    @DisplayName("a row that fails max-attempts times stops being claimed")
    void anExhaustedRowDropsOutOfTheClaim() {
        OutboxEvent poisoned = unpublishedEvent("PO001");

        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();

        // Four drains, two sends. The third and fourth found nothing to claim.
        verify(eventPublisher, times(MAX_ATTEMPTS)).publish(anyString(), anyString(), anyMap());
        OutboxEvent after = outboxEventRepository.findById(poisoned.getId()).orElseThrow();
        assertThat(after.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(after.getPublishedAt()).as("never sent, and never claimed to have been").isNull();
        assertThat(after.getLastError()).contains("never accept this");
    }

    /**
     * A row that will not be retried has to be visible. {@code outbox.pending} recovers
     * on its own, so {@code outbox.dead}, which does not, is the one to alert on.
     */
    @Test
    @DisplayName("an exhausted row moves from the pending gauge to the dead one")
    void theDeadGaugeIsWhereTheRowGoes() {
        unpublishedEvent("PO002");
        assertThat(gauge("outbox.pending")).isEqualTo(1);
        assertThat(gauge("outbox.dead")).isZero();

        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();

        assertThat(gauge("outbox.pending")).as("no longer waiting; nothing will claim it").isZero();
        assertThat(gauge("outbox.dead")).isEqualTo(1);
    }

    /**
     * The re-drive statement from OPERATIONS.md, run verbatim, so the SQL an operator
     * pastes is the SQL under test.
     */
    @Test
    @DisplayName("resetting attempts brings an exhausted row back, and it publishes")
    void resettingAttemptsRedrivesTheRow() {
        OutboxEvent poisoned = unpublishedEvent("PO003");
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();
        assertThat(gauge("outbox.dead")).isEqualTo(1);

        doNothing().when(eventPublisher).publish(anyString(), anyString(), anyMap());
        jdbcTemplate.update(
                "UPDATE outbox_events SET attempts = 0, next_attempt_at = NULL WHERE id = ?",
                poisoned.getId());

        outboxPublisher.drainOutbox();

        assertThat(outboxEventRepository.findById(poisoned.getId()).orElseThrow().getPublishedAt())
                .as("the cause was fixed, so the event went out rather than being lost")
                .isNotNull();
        assertThat(gauge("outbox.dead")).isZero();
    }
}
