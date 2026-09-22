package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.observability.BookingMetrics;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The write path that used to live directly in {@code BookingService.book}
 * before the idempotency-race fix split it out — see {@link BookingWriter}'s
 * Javadoc for why. These tests exercise exactly what
 * {@code insertNewBooking} does; the replay/recovery orchestration is
 * {@link BookingServiceTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class BookingWriterTest {

    private static final Instant CANCELLED_AT = Instant.parse("2026-09-20T12:00:00Z");

    @Mock private FlightRepository flightRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private OutboxWriter outboxWriter;
    @Mock private BookingMetrics metrics;

    /**
     * A real fixed Clock, not a mock. The cancellation path stores whatever
     * this returns, so the assertions can be equalities against a known
     * instant; a mock would need stubbing in every test that touches it and
     * would fail Mockito's strict-stubs check in the ones that do not.
     */
    @Spy private Clock clock = Clock.fixed(CANCELLED_AT, ZoneOffset.UTC);

    @InjectMocks private BookingWriter bookingWriter;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private Flight flight() {
        return new Flight("UA123", "EWR", "LHR", 180, DEPARTURE);
    }

    private BookingRequest request(int seats, String key) {
        return new BookingRequest("UA123", "Smit Lakhani", seats, key);
    }

    @Test
    @DisplayName("locks the flight, debits seats, persists, then records the event in the outbox")
    void happyPath() {
        Flight flight = flight();
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        BookingDto dto = bookingWriter.insertNewBooking(request(3, "demo-1"));

        assertThat(flight.getAvailableSeats()).isEqualTo(177);
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.seats()).isEqualTo(3);
        // The key is stored and not returned, and both halves of that matter:
        // the column is what makes the next replay of this request idempotent,
        // and the response is where echoing it would hand one caller's key to
        // whoever can read the list endpoint. So the assertion is on the row
        // that was saved, not on the DTO that went back.
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("demo-1");
        // The outbox, not the transport. Before the outbox this line read
        // verify(eventPublisher).publishBookingCreated(dto), and the change in
        // that line is the change in the design: what happens inside this
        // transaction is now a database insert, and the network call happens
        // later, somewhere else, holding none of these locks. A test still
        // verifying the publisher here would be pinning behaviour that was
        // deliberately removed.
        verify(outboxWriter).recordBookingCreated(dto);
    }

    @Test
    @DisplayName("the flight row is read FOR UPDATE, not with a plain lookup")
    void bookingTakesAPessimisticLock() {
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight()));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        bookingWriter.insertNewBooking(request(1, "demo-2"));

        verify(flightRepository).findByFlightNumberForUpdate("UA123");
        verify(flightRepository, never()).findByFlightNumber(any());
    }

    @Test
    @DisplayName("overselling aborts before anything is written or published")
    void oversellIsRejected() {
        Flight flight = flight();
        flight.reserveSeats(179);
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(request(2, "demo-3")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(flight.getAvailableSeats()).isEqualTo(1);
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("REGRESSION: booking a cancelled flight writes nothing and publishes nothing")
    void cancelledFlightIsRejected() {
        // The service does not repeat the check — it calls reserveSeats and lets
        // the entity refuse. What this test pins is the CONSEQUENCE of the throw
        // landing where it does: before bookingRepository.save() and before the
        // event is published, so a cancelled flight produces no booking row and
        // no downstream DynamoDB projection of a booking that never happened.
        Flight flight = flight();
        flight.cancel();
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(request(1, "demo-5")))
                .isInstanceOf(FlightNotBookableException.class)
                .hasMessageContaining("CANCELLED");

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("unknown flight throws before any lock is attempted on a nonexistent row")
    void unknownFlightIsA404() {
        when(flightRepository.findByFlightNumberForUpdate("XX999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(
                new BookingRequest("xx999", "Smit Lakhani", 1, "demo-4")))
                .isInstanceOf(FlightNotFoundException.class);

        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("recoverReplay returns the winner's booking when one exists")
    void recoverReplayFindsTheWinner() {
        Flight flight = flight();
        Booking winner = new Booking(flight, "Smit Lakhani", 3, "raced-key", null);
        when(bookingRepository.findByIdempotencyKey("raced-key")).thenReturn(Optional.of(winner));

        BookingDto dto = bookingWriter.recoverReplay("raced-key", "any-fingerprint");

        assertThat(dto.passengerName()).isEqualTo("Smit Lakhani");
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.seats()).isEqualTo(3);
    }

    @Test
    @DisplayName("REGRESSION: recoverReplay refuses to invent a booking that doesn't exist")
    void recoverReplayThrowsIfSomehowNothingWon() {
        // Should be unreachable in production — a DataIntegrityViolationException
        // on this key means someone won. Pinned anyway: silently returning null or
        // fabricating a response here would be far worse than a loud 500.
        when(bookingRepository.findByIdempotencyKey("ghost-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingWriter.recoverReplay("ghost-key", "any-fingerprint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost-key");
    }
}
