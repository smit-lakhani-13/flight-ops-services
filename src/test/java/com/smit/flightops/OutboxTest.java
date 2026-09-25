package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.OutboxEventRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.BookingWriter;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.OutboxPublisher;
import com.smit.flightops.service.OutboxWriter;
import io.micrometer.tracing.ScopedSpan;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The transactional outbox: the event row and the booking row share a fate.
 * {@link #aRolledBackBookingLeavesNoEvent()} is the central test; the rest cover the
 * poller, retries, the MANDATORY guard and trace context.
 *
 * <p>The schedule is pushed out to an hour and {@code drainOutbox()} is called directly,
 * so no test waits on a timer. The class is not {@code @Transactional}, since a
 * rolled-back test could read uncommitted state; each test uses its own flight number.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxtest;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000",
                // Back-to-back drains with a fixed clock: the V7 backoff would make the
                // second claim nothing. Zero switches it off; OutboxRetryBackoffTest pins it.
                "app.outbox.retry-backoff=0",
                // Production samples 0.1. Sampling every span here means "no traceparent"
                // can only mean the code did not capture one.
                "management.tracing.sampling.probability=1.0"
        })
class OutboxTest {

    @Autowired private BookingService bookingService;
    @Autowired private BookingWriter bookingWriter;
    @Autowired private FlightService flightService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxWriter outboxWriter;
    @Autowired private Tracer tracer;
    @Autowired private PlatformTransactionManager transactionManager;

    /** Only the transport is mocked, so it can fail on demand; the rest is real. */
    @MockitoBean private EventPublisher eventPublisher;

    private List<OutboxEvent> eventsFor(String bookingId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(bookingId))
                .toList();
    }

    @BeforeEach
    void resetTransport() {
        doNothing().when(eventPublisher).publish(anyString(), anyString(), anyMap());
    }

    // -----------------------------------------------------------------
    // The claim.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a committed booking leaves a single unpublished event carrying the booking's own data")
    void aCommittedBookingLeavesOneEvent() {
        flightService.create(new CreateFlightRequest("OB001", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        BookingDto booking = bookingService.book(
                new BookingRequest("OB001", "Smit Lakhani", 2, "outbox-1"));

        List<OutboxEvent> events = eventsFor(String.valueOf(booking.bookingId()));
        assertThat(events).hasSize(1);
        OutboxEvent event = events.getFirst();

        assertThat(event.getEventType()).isEqualTo("BookingCreated");
        assertThat(event.getAggregateType()).isEqualTo("Booking");
        assertThat(event.getPublishedAt()).as("recorded, not yet sent").isNull();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getPayload())
                .contains("\"bookingId\":\"" + booking.bookingId() + "\"")
                .contains("\"flightNumber\":\"OB001\"")
                .contains("\"seats\":2");

        // Recording is not sending: nothing reaches the transport during the booking transaction.
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The seat check fails before anything is written, so there is no event row
     * to roll back. {@link #aRolledBackBookingLeavesNoEvent()} covers the
     * rollback.
     */
    @Test
    @DisplayName("an oversell is refused before anything is written, event included")
    void anOversellWritesNoEvent() {
        flightService.create(new CreateFlightRequest("OB002", "EWR", "LHR", 2,
                Instant.now().plus(Duration.ofHours(6))));
        long before = outboxEventRepository.count();

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("OB002", "Smit Lakhani", 3, "outbox-oversell")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(outboxEventRepository.count())
                .as("no event row was written for the refused booking")
                .isEqualTo(before);
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The booking and its event row are both written, and then the transaction
     * rolls back. If the event row survived, a consumer would hear about a
     * booking the database has no record of.
     */
    @Test
    @DisplayName("ATOMICITY: a rolled-back booking leaves no event at all")
    void aRolledBackBookingLeavesNoEvent() {
        flightService.create(new CreateFlightRequest("OB011", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        // insertNewBooking joins this transaction and writes both rows.
        // Rollback-only stands in for any failure after the event is recorded.
        BookingDto booking = new TransactionTemplate(transactionManager).execute(status -> {
            BookingDto written = bookingWriter.insertNewBooking(
                    new BookingRequest("OB011", "Smit Lakhani", 2, "outbox-rollback"));
            assertThat(eventsFor(String.valueOf(written.bookingId())))
                    .as("the event row exists before the rollback")
                    .hasSize(1);
            status.setRollbackOnly();
            return written;
        });

        assertThat(bookingRepository.findById(booking.bookingId())).isEmpty();
        assertThat(eventsFor(String.valueOf(booking.bookingId())))
                .as("the event row rolled back with the booking it described")
                .isEmpty();
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The payload is a snapshot. A poller that re-read the booking at send time would
     * publish a {@code BookingCreated} describing a cancelled booking.
     */
    @Test
    @DisplayName("the stored payload describes the booking as it was, even after the booking changes")
    void thePayloadIsASnapshot() {
        flightService.create(new CreateFlightRequest("OB003", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));
        BookingDto booking = bookingService.book(
                new BookingRequest("OB003", "Smit Lakhani", 2, "outbox-snapshot"));
        String payloadAtBookingTime = eventsFor(String.valueOf(booking.bookingId())).getFirst().getPayload();

        bookingService.cancel(booking.bookingId());

        assertThat(eventsFor(String.valueOf(booking.bookingId())).getFirst().getPayload())
                .isEqualTo(payloadAtBookingTime);
    }

    // -----------------------------------------------------------------
    // The poller.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("draining sends the payload unchanged and marks the row published")
    void drainingPublishesAndMarks() {
        flightService.create(new CreateFlightRequest("OB004", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));
        BookingDto booking = bookingService.book(
                new BookingRequest("OB004", "Smit Lakhani", 1, "outbox-drain"));
        String payload = eventsFor(String.valueOf(booking.bookingId())).getFirst().getPayload();

        outboxPublisher.drainOutbox();

        verify(eventPublisher).publish(eq("BookingCreated"), eq(payload), anyMap());
        OutboxEvent event = eventsFor(String.valueOf(booking.bookingId())).getFirst();
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
    }

    @Test
    @DisplayName("a published row is never sent twice, however often the poller runs")
    void drainingIsNotRepeated() {
        flightService.create(new CreateFlightRequest("OB005", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));
        BookingDto booking = bookingService.book(
                new BookingRequest("OB005", "Smit Lakhani", 1, "outbox-once"));
        String payload = eventsFor(String.valueOf(booking.bookingId())).getFirst().getPayload();

        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();
        outboxPublisher.drainOutbox();

        // Once, not three times: the WHERE published_at IS NULL in claimUnpublished.
        verify(eventPublisher).publish(eq("BookingCreated"), eq(payload), anyMap());
    }

    /**
     * A transport failure must not look like a delivery. The row stays unpublished,
     * the attempt and reason are recorded, and the next tick tries again.
     */
    @Test
    @DisplayName("a failed send is recorded, left unpublished, and succeeds on the next drain")
    void aFailedSendIsRetried() {
        flightService.create(new CreateFlightRequest("OB006", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));
        BookingDto booking = bookingService.book(
                new BookingRequest("OB006", "Smit Lakhani", 1, "outbox-retry"));
        String id = String.valueOf(booking.bookingId());

        doThrow(new IllegalStateException("queue unreachable"))
                .when(eventPublisher).publish(anyString(), anyString(), anyMap());
        outboxPublisher.drainOutbox();

        OutboxEvent afterFailure = eventsFor(id).getFirst();
        assertThat(afterFailure.getPublishedAt()).as("not sent, so not marked sent").isNull();
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("queue unreachable");

        doNothing().when(eventPublisher).publish(anyString(), anyString(), anyMap());
        outboxPublisher.drainOutbox();

        OutboxEvent afterRecovery = eventsFor(id).getFirst();
        assertThat(afterRecovery.getPublishedAt()).isNotNull();
        assertThat(afterRecovery.getLastError()).as("cleared on success").isNull();
        assertThat(afterRecovery.getAttempts()).as("the failed attempt is still on the record").isEqualTo(1);
    }

    /**
     * Without the per-row catch in {@code drainOutbox}, one failure would roll back
     * {@code markPublished} on rows already sent, and the next tick would resend them.
     */
    @Test
    @DisplayName("one failing event does not block the others in the same batch")
    void oneBadEventDoesNotPoisonTheBatch() {
        flightService.create(new CreateFlightRequest("OB007", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));
        BookingDto poisoned = bookingService.book(
                new BookingRequest("OB007", "Smit Lakhani", 1, "outbox-poison"));
        BookingDto healthy = bookingService.book(
                new BookingRequest("OB007", "Smit Lakhani", 1, "outbox-healthy"));

        String poisonPayload = eventsFor(String.valueOf(poisoned.bookingId())).getFirst().getPayload();
        doThrow(new IllegalStateException("that one specifically"))
                .when(eventPublisher).publish(anyString(), eq(poisonPayload), anyMap());

        outboxPublisher.drainOutbox();

        assertThat(eventsFor(String.valueOf(poisoned.bookingId())).getFirst().getPublishedAt()).isNull();
        assertThat(eventsFor(String.valueOf(healthy.bookingId())).getFirst().getPublishedAt())
                .as("the healthy event in the same batch was sent and stayed sent")
                .isNotNull();
    }

    // -----------------------------------------------------------------
    // The guard.
    // -----------------------------------------------------------------

    /**
     * {@code Propagation.MANDATORY}. Under {@code REQUIRED} this call would commit in
     * its own transaction, and after a booking rollback the event would survive alone.
     * This test stops someone switching the annotation because a new caller threw.
     */
    @Test
    @DisplayName("recording an event outside a transaction is refused, not committed on its own")
    void recordingOutsideATransactionIsRefused() {
        BookingDto orphan = new BookingDto(999L, "OB999", "Nobody", 1,
                Instant.now(), null);
        long before = outboxEventRepository.count();

        assertThatThrownBy(() -> outboxWriter.recordBookingCreated(orphan))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(before);
        verify(eventPublisher, never()).publish(anyString(), anyString(), anyMap());
    }

    // -----------------------------------------------------------------
    // The trace context.
    // -----------------------------------------------------------------

    /**
     * The outbox sends from a scheduler thread, which has no link to the request, so
     * the traceparent is stored on the row at booking time and sent as a header.
     */
    @Test
    @DisplayName("the booking's own trace context is stored on the row and travels with the send")
    void theTraceContextIsCapturedAtBookingTime() {
        flightService.create(new CreateFlightRequest("OB008", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        ScopedSpan span = tracer.startScopedSpan("booking-request");
        String traceId = span.context().traceId();
        BookingDto booking;
        try {
            booking = bookingService.book(
                    new BookingRequest("OB008", "Smit Lakhani", 1, "outbox-trace"));
        } finally {
            span.end();
        }

        OutboxEvent event = eventsFor(String.valueOf(booking.bookingId())).getFirst();
        assertThat(event.getTraceparent())
                .as("W3C form, built by the propagator rather than assembled by hand")
                .matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                .contains(traceId);

        outboxPublisher.drainOutbox();

        verify(eventPublisher).publish(eq("BookingCreated"), eq(event.getPayload()),
                eq(Map.of("traceparent", event.getTraceparent())));
    }

    /**
     * A booking made outside a trace gets no traceparent and no header. An invented
     * id would let a consumer stitch unrelated work into one trace.
     */
    @Test
    @DisplayName("a booking made outside a trace stores no traceparent and sends no header")
    void anUntracedBookingCarriesNothing() {
        flightService.create(new CreateFlightRequest("OB009", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        BookingDto booking = bookingService.book(
                new BookingRequest("OB009", "Smit Lakhani", 1, "outbox-untraced"));

        OutboxEvent event = eventsFor(String.valueOf(booking.bookingId())).getFirst();
        assertThat(event.getTraceparent()).isNull();

        outboxPublisher.drainOutbox();

        verify(eventPublisher).publish(eq("BookingCreated"), eq(event.getPayload()), eq(Map.of()));
    }

    /**
     * Pins where the capture happens. Reading the span in the poller would give every
     * event in a tick the drain's trace; the drain runs in its own span so that fails here.
     */
    @Test
    @DisplayName("the event carries the booking's trace, not the drain's")
    void theDrainDoesNotOverwriteTheTrace() {
        flightService.create(new CreateFlightRequest("OB010", "EWR", "LHR", 20,
                Instant.now().plus(Duration.ofHours(6))));

        ScopedSpan bookingSpan = tracer.startScopedSpan("booking-request");
        String bookingTraceId = bookingSpan.context().traceId();
        BookingDto booking;
        try {
            booking = bookingService.book(
                    new BookingRequest("OB010", "Smit Lakhani", 1, "outbox-trace-owner"));
        } finally {
            bookingSpan.end();
        }
        OutboxEvent event = eventsFor(String.valueOf(booking.bookingId())).getFirst();

        ScopedSpan drainSpan = tracer.startScopedSpan("outbox-drain");
        String drainTraceId = drainSpan.context().traceId();
        try {
            outboxPublisher.drainOutbox();
        } finally {
            drainSpan.end();
        }

        assertThat(drainTraceId).isNotEqualTo(bookingTraceId);
        verify(eventPublisher).publish(eq("BookingCreated"), eq(event.getPayload()),
                eq(Map.of("traceparent", event.getTraceparent())));
        assertThat(event.getTraceparent()).contains(bookingTraceId).doesNotContain(drainTraceId);
    }
}
