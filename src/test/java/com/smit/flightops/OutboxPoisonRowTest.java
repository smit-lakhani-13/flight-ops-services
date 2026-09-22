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
 * <p>The answer before the attempt ceiling existed was: it is retried first on
 * every tick, forever. The claim is {@code ORDER BY id}, so the oldest failing
 * row is always at the head of the batch — a payload that fails identically on
 * attempt ten thousand spends the batch failing while every live event queues
 * up behind it. One malformed row is a total publishing outage, and waiting
 * does not fix it because waiting is what it is doing.
 *
 * <p>So the ceiling is an availability feature, not tidiness, and these three
 * tests are the three things an operator needs to be true: the row drops out,
 * the row is visible while it is out, and the row can be brought back.
 *
 * <p>{@code max-attempts} is two here. Ten is right in production and would
 * make this class ten drains long for no extra proof.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxpoison;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000",
                // Two drains, back to back, with no clock moved between
                // them. The retry backoff (V7) would make the second one claim
                // nothing, so it is off here; OutboxRetryBackoffTest pins it.
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
     * A row nobody will retry has to be visible, or the ceiling has traded a
     * loud failure for a silent one. {@code outbox.pending} recovers on its own
     * and is therefore the wrong thing to page on; {@code outbox.dead} does
     * not, which is what makes it the alert.
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
     * The documented re-drive, run as the documentation gives it. OPERATIONS.md
     * and three Javadoc comments tell an operator to run exactly this
     * statement; a test that reset the counter through some helper of its own
     * would leave the sentence they will actually paste unverified.
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
