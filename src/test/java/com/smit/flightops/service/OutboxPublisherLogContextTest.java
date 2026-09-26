package com.smit.flightops.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.smit.flightops.config.EventProperties;
import com.smit.flightops.config.OutboxProperties;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.observability.OutboxMetrics;
import com.smit.flightops.repository.OutboxEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The drain runs under a trace of its own, so the booking's trace reaches the log lines
 * of a send only through the MDC. A leaked key would label the next event's lines, or
 * any later line on the reused scheduler thread, with the wrong booking's trace.
 */
class OutboxPublisherLogContextTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TRACEPARENT = "00-10cd4f19102abf7a3f922f252b6d4a97-da65ef2344ac0aa1-01";

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

    /** What the MDC held while the transport ran, one entry per send. */
    private final List<String> seenByTransport = new ArrayList<>();

    private final Logger logger = (Logger) LoggerFactory.getLogger(OutboxPublisher.class);

    /** Snapshots the MDC at append time; a plain ListAppender reads it later, once cleared. */
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private OutboxPublisher publisherWith(EventPublisher transport) {
        OutboxProperties properties = new OutboxProperties(true, 1000, 100, 10,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5));
        return new OutboxPublisher(new EventProperties("log"), repository, transport, properties,
                mock(OutboxMetrics.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void claimReturns(OutboxEvent... events) {
        when(repository.claimUnpublished(anyInt(), anyInt(), any(Instant.class))).thenReturn(List.of(events));
    }

    private static OutboxEvent event(String bookingId, String traceparent) {
        return new OutboxEvent("Booking", bookingId, "BookingCreated",
                "{\"bookingId\":\"" + bookingId + "\"}", NOW, traceparent);
    }

    @Test
    @DisplayName("the warning for a failed send carries the booking's traceparent, and the MDC is empty afterwards")
    void aFailedSendIsLoggedWithTheBookingsTrace() {
        claimReturns(event("42", TRACEPARENT));
        OutboxPublisher publisher = publisherWith((type, payload, headers) -> {
            seenByTransport.add(MDC.get(OutboxPublisher.TRACEPARENT));
            throw new IllegalStateException("queue unreachable");
        });

        publisher.drainOutbox();

        assertThat(seenByTransport)
                .as("the transport's own lines, such as the SQS success line, carry it too")
                .containsExactly(TRACEPARENT);
        assertThat(appender.list)
                .filteredOn(e -> e.getFormattedMessage().contains("failed to publish on attempt 1 of 10"))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getMDCPropertyMap()).containsEntry(OutboxPublisher.TRACEPARENT, TRACEPARENT);
                });
        assertThat(MDC.getCopyOfContextMap()).as("nothing leaks onto the scheduler thread").isNullOrEmpty();
    }

    /** Per event, so one booking's trace never labels the next booking's send. */
    @Test
    @DisplayName("each send sees only its own traceparent, and none when the row has none")
    void eachSendSeesOnlyItsOwnTrace() {
        claimReturns(event("42", TRACEPARENT), event("43", null));
        OutboxPublisher publisher = publisherWith(
                (type, payload, headers) -> seenByTransport.add(MDC.get(OutboxPublisher.TRACEPARENT)));

        publisher.drainOutbox();

        assertThat(seenByTransport).containsExactly(TRACEPARENT, null);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("the header the consumer reads is unchanged")
    void theHeaderIsStillSent() {
        claimReturns(event("42", TRACEPARENT));
        List<Map<String, String>> sent = new ArrayList<>();
        publisherWith((type, payload, headers) -> sent.add(headers)).drainOutbox();

        assertThat(sent).containsExactly(Map.of("traceparent", TRACEPARENT));
    }
}
