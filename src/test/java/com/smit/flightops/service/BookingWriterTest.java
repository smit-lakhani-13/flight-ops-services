package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.exception.LostIdempotencyRaceException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The transactional write path in {@link BookingWriter}: the lock, the in-lock re-read,
 * the seat debit, the outbox record and cancellation. The replay and recovery
 * orchestration is {@link BookingServiceTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class BookingWriterTest {

    private static final Instant CANCELLED_AT = Instant.parse("2026-09-20T12:00:00Z");

    @Mock private FlightRepository flightRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private OutboxWriter outboxWriter;

    /** A fixed Clock, so cancellation times are known; a mock would trip strict stubs. */
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
        // The key is stored for replays but not returned, since the list endpoint
        // would expose it. So the assertion is on the saved row.
        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).save(saved.capture());
        assertThat(saved.getValue().getIdempotencyKey()).isEqualTo("demo-1");
        // The outbox, not the transport: inside this transaction the event is an
        // insert, and the network call happens later holding none of these locks.
        verify(outboxWriter).recordBookingCreated(dto);
    }

    @Test
    @DisplayName("REGRESSION: a replay that arrives when the winner took the last seats is a lost race, not an oversell")
    void racingReplayOnTheLastSeatIsNotAnOversell() {
        // The winner committed under this lock and took every remaining seat. If
        // reserveSeats ran before the re-read, a retry of a request that succeeded
        // would get InsufficientSeatsException.
        Flight full = flight();
        full.reserveSeats(180);
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(full));
        Booking winner = new Booking(full, "Smit Lakhani", 2, "raced-key", "fp");
        when(bookingRepository.findByIdempotencyKey("raced-key")).thenReturn(Optional.of(winner));

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(request(2, "raced-key")))
                .isInstanceOf(LostIdempotencyRaceException.class)
                .hasMessageContaining("raced-key");

        // Nothing written, nothing published and no second debit.
        assertThat(full.getAvailableSeats()).isZero();
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    @DisplayName("the re-check happens while the flight row is locked, not before it")
    void theReCheckIsInsideTheLock() {
        Flight flight = flight();
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        Booking winner = new Booking(flight, "Smit Lakhani", 1, "raced-key-2", "fp");
        when(bookingRepository.findByIdempotencyKey("raced-key-2")).thenReturn(Optional.of(winner));

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(request(1, "raced-key-2")))
                .isInstanceOf(LostIdempotencyRaceException.class);

        // Outside the lock the read would repeat the pre-check BookingService.book
        // already did, so it has to come after findByFlightNumberForUpdate.
        InOrder inOrder = inOrder(flightRepository, bookingRepository);
        inOrder.verify(flightRepository).findByFlightNumberForUpdate("UA123");
        inOrder.verify(bookingRepository).findByIdempotencyKey("raced-key-2");
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
        // The entity refuses in reserveSeats. This pins that the throw lands before
        // save() and before the outbox record, so no row and no event exist.
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
    @DisplayName("recoverReplay returns the winner's booking when the fingerprints match")
    void recoverReplayFindsTheWinner() {
        Flight flight = flight();
        // A real fingerprint: Booking.matchesRequest short-circuits on null, which
        // would pass any fingerprint.
        BookingRequest original = request(3, "raced-key");
        Booking winner = new Booking(flight, "Smit Lakhani", 3, "raced-key", original.fingerprint());
        when(bookingRepository.findByIdempotencyKey("raced-key")).thenReturn(Optional.of(winner));

        BookingDto dto = bookingWriter.recoverReplay("raced-key", original.fingerprint());

        assertThat(dto.passengerName()).isEqualTo("Smit Lakhani");
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.seats()).isEqualTo(3);
    }

    @Test
    @DisplayName("recoverReplay refuses the winner when the loser asked for a different booking")
    void recoverReplayRejectsAReusedKey() {
        // Two callers reuse one key for different bookings at the same moment. The
        // loser must get the same 409 as the sequential case.
        Flight flight = flight();
        Booking winner = new Booking(flight, "Ada Lovelace", 3, "reused-key",
                                     request(3, "reused-key").fingerprint());
        when(bookingRepository.findByIdempotencyKey("reused-key")).thenReturn(Optional.of(winner));

        String differentRequest = new BookingRequest("UA123", "Grace Hopper", 1, "reused-key").fingerprint();

        assertThatThrownBy(() -> bookingWriter.recoverReplay("reused-key", differentRequest))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("reused-key")
                // The message must not describe the winner, or a guessable key
                // would read out someone else's reservation.
                .hasMessageNotContaining("Ada");
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cancelling releases the seats and reports that it did")
    void cancellationReleasesSeats() {
        Flight flight = flight();
        flight.reserveSeats(3);
        Booking booking = new Booking(flight, "Smit Lakhani", 3, "cancel-1", null);

        when(bookingRepository.findFlightNumberById(7L)).thenReturn(Optional.of("UA123"));
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        when(bookingRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(booking));

        BookingWriter.Cancellation result = bookingWriter.cancelBooking(7L);

        assertThat(result.seatsReleased()).isTrue();
        assertThat(result.booking().cancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("a retried cancellation releases nothing and says so, so the counter cannot double-count")
    void secondCancellationIsANoOp() {
        Flight flight = flight();
        flight.reserveSeats(3);
        Booking booking = new Booking(flight, "Smit Lakhani", 3, "cancel-2", null);
        booking.cancel(CANCELLED_AT.minusSeconds(60));

        when(bookingRepository.findFlightNumberById(8L)).thenReturn(Optional.of("UA123"));
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        when(bookingRepository.findByIdForUpdate(8L)).thenReturn(Optional.of(booking));

        BookingWriter.Cancellation result = bookingWriter.cancelBooking(8L);

        assertThat(result.seatsReleased()).isFalse();
        // The original cancellation time, which a client reconciling its state needs.
        assertThat(result.booking().cancelledAt()).isEqualTo(CANCELLED_AT.minusSeconds(60));
        assertThat(flight.getAvailableSeats()).isEqualTo(177);
    }

    @Test
    @DisplayName("the flight row is locked before the booking row, which is what stops the deadlock")
    void cancellationTakesTheLocksInTheDocumentedOrder() {
        Flight flight = flight();
        flight.reserveSeats(1);
        Booking booking = new Booking(flight, "Smit Lakhani", 1, "cancel-3", null);

        when(bookingRepository.findFlightNumberById(9L)).thenReturn(Optional.of("UA123"));
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        when(bookingRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(booking));

        bookingWriter.cancelBooking(9L);

        // insertNewBooking locks the flight, then writes bookings; the reverse order
        // here would deadlock with it. The scalar projection allows this order
        // without loading the Booking.
        InOrder order = inOrder(bookingRepository, flightRepository);
        order.verify(bookingRepository).findFlightNumberById(9L);
        order.verify(flightRepository).findByFlightNumberForUpdate("UA123");
        order.verify(bookingRepository).findByIdForUpdate(9L);
    }

    @Test
    @DisplayName("cancelling an unknown booking is a 404 before any row is locked")
    void cancellingAnUnknownBookingIsA404() {
        when(bookingRepository.findFlightNumberById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingWriter.cancelBooking(404L))
                .isInstanceOf(BookingNotFoundException.class);

        verifyNoInteractions(flightRepository);
    }

    @Test
    @DisplayName("REGRESSION: recoverReplay refuses to invent a booking that doesn't exist")
    void recoverReplayThrowsIfSomehowNothingWon() {
        // Reached when an insert failed for a reason other than the key.
        // BookingService then rethrows that failure, so this must not return null
        // or invent a booking.
        when(bookingRepository.findByIdempotencyKey("ghost-key")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingWriter.recoverReplay("ghost-key", "any-fingerprint"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost-key");
    }
}
