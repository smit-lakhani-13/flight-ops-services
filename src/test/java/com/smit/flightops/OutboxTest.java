package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.OutboxEventRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.OutboxPublisher;
import com.smit.flightops.service.OutboxWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The transactional outbox, proved rather than asserted.
 *
 * <p>The claim the pattern makes is narrow and testable: <b>the event row and
 * the booking row share a fate</b>. Everything else — the poller, the retries,
 * the batching — is machinery in service of that one sentence, so
 * {@link #aRolledBackBookingLeavesNoEvent()} is the test this class exists for
 * and the rest support it.
 *
 * <p>The poller's schedule is pushed out to an hour so that nothing drains
 * behind the assertions; {@code drainOutbox()} is called directly instead.
 * Testing a scheduled job by waiting for its schedule is how a suite acquires
 * a sleep and, eventually, a flake.
 *
 * <p>Not {@code @Transactional}, for the same reason
 * {@code BookingIdempotencyTest} is not: a rolled-back test would let these
 * assertions read uncommitted state and the atomicity test in particular would
 * pass without proving anything. Rows therefore survive between tests, so each
 * one uses its own flight number and counts only its own events.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:outboxtest;DB_CLOSE_DELAY=-1",
                "app.outbox.poll-interval=3600000"
        })
class OutboxTest {

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxWriter outboxWriter;

    /**
     * The transport is mocked so the tests can make it fail on demand. Note
     * what is <em>not</em> mocked: the repository, the writer, the poller, the
     * transaction boundaries and the database. The only thing replaced is the
     * one hop that would otherwise need a queue.
     */
    @MockitoBean private EventPublisher eventPublisher;

    private List<OutboxEvent> eventsFor(String bookingId) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(bookingId))
                .toList();
    }

    @BeforeEach
    void resetTransport() {
        doNothing().when(eventPublisher).publish(anyString(), anyString());
    }

    // -----------------------------------------------------------------
    // The claim.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a committed booking leaves exactly one unpublished event carrying the booking's own data")
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

        // Recording is not sending. Nothing should have reached the transport
        // during the booking transaction - that separation is the whole change.
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The atomicity proof, and the one test here that could not be written
     * before the outbox existed.
     *
     * <p>The booking fails on the seat check, so its transaction rolls back.
     * The event row was written inside that transaction, so it has to go with
     * it. If this ever fails, the outbox has stopped being an outbox: a
     * consumer would be told a booking was created that the database has no
     * record of, and no amount of retrying or reconciling downstream can
     * invent the reservation.
     */
    @Test
    @DisplayName("ATOMICITY: a rolled-back booking leaves no event at all")
    void aRolledBackBookingLeavesNoEvent() {
        flightService.create(new CreateFlightRequest("OB002", "EWR", "LHR", 2,
                Instant.now().plus(Duration.ofHours(6))));
        long before = outboxEventRepository.count();

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("OB002", "Smit Lakhani", 3, "outbox-oversell")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(outboxEventRepository.count())
                .as("the event row rolled back with the booking it described")
                .isEqualTo(before);
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The payload is a snapshot of what happened, not a view of what is true
     * now. A poller that re-read the booking row at send time would publish
     * "BookingCreated" describing a cancelled booking, which is a statement
     * about the present wearing the name of an event.
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

        verify(eventPublisher).publish("BookingCreated", payload);
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

        // Once, not three times. The WHERE published_at IS NULL in
        // claimUnpublished is the only thing standing between a poller and a
        // consumer receiving every event it has ever been sent, once per tick,
        // forever.
        verify(eventPublisher).publish(eq("BookingCreated"), eq(payload));
    }

    /**
     * A transport failure must not look like a delivery. The row stays
     * unpublished, the attempt is counted, the reason is recorded where an
     * operator can read it, and the next tick tries again — which is the entire
     * reason the event was written to a table instead of a socket.
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
                .when(eventPublisher).publish(anyString(), anyString());
        outboxPublisher.drainOutbox();

        OutboxEvent afterFailure = eventsFor(id).getFirst();
        assertThat(afterFailure.getPublishedAt()).as("not sent, so not marked sent").isNull();
        assertThat(afterFailure.getAttempts()).isEqualTo(1);
        assertThat(afterFailure.getLastError()).contains("queue unreachable");

        doNothing().when(eventPublisher).publish(anyString(), anyString());
        outboxPublisher.drainOutbox();

        OutboxEvent afterRecovery = eventsFor(id).getFirst();
        assertThat(afterRecovery.getPublishedAt()).isNotNull();
        assertThat(afterRecovery.getLastError()).as("cleared on success").isNull();
        assertThat(afterRecovery.getAttempts()).as("the failed attempt is still on the record").isEqualTo(1);
    }

    /**
     * One poisoned event must not take its batch down with it.
     *
     * <p>If the per-row catch in {@code drainOutbox} were removed, the whole
     * transaction would roll back — including {@code markPublished} on rows
     * whose payloads had <em>already left the process</em>. Those would be
     * resent on the next tick. A single bad event would turn into a duplicate
     * for every good event beside it, which is the failure the outbox is meant
     * to bound rather than amplify.
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
                .when(eventPublisher).publish(anyString(), eq(poisonPayload));

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
     * {@code Propagation.MANDATORY} in action.
     *
     * <p>Without it this call would quietly succeed in its own transaction and
     * the outbox would still appear to work — right up to the first rollback,
     * when the event would commit and the booking would not. The exception here
     * is the design refusing to be used incorrectly, and this test is what
     * stops someone "fixing" the annotation to {@code REQUIRED} because a new
     * caller threw.
     */
    @Test
    @DisplayName("recording an event outside a transaction is refused, not silently committed")
    void recordingOutsideATransactionIsRefused() {
        BookingDto orphan = new BookingDto(999L, "OB999", "Nobody", 1,
                Instant.now(), null);
        long before = outboxEventRepository.count();

        assertThatThrownBy(() -> outboxWriter.recordBookingCreated(orphan))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(before);
        verify(eventPublisher, never()).publish(anyString(), anyString());
    }
}
