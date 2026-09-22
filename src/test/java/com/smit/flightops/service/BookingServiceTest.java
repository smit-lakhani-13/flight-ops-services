package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code BookingService.book} is pure orchestration now: replay check, then
 * delegate to {@link BookingWriter}, then recover if the writer lost a race.
 * The write path itself (locking, debiting, publishing) is
 * {@link BookingWriterTest}'s job — this class only pins the orchestration.
 */
@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private BookingRepository bookingRepository;
    @Mock private BookingWriter bookingWriter;

    @InjectMocks private BookingService bookingService;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private Flight flight() {
        return new Flight("UA123", "EWR", "LHR", 180, DEPARTURE);
    }

    private BookingRequest request(int seats, String key) {
        return new BookingRequest("UA123", "Smit Lakhani", seats, key);
    }

    @Test
    @DisplayName("no existing row -> delegates straight to the writer")
    void firstTimeBookingDelegatesToTheWriter() {
        when(bookingRepository.findByIdempotencyKey("demo-1")).thenReturn(Optional.empty());
        BookingDto written = new BookingDto(1L, "UA123", "Smit Lakhani", 3, Instant.now(), null);
        when(bookingWriter.insertNewBooking(any())).thenReturn(written);

        BookingDto dto = bookingService.book(request(3, "demo-1"));

        assertThat(dto).isEqualTo(written);
        verify(bookingWriter, never()).recoverReplay(any(), any());
    }

    @Test
    @DisplayName("a replay after the original committed returns the original, and never touches the writer")
    void replayIsServedFromTheExistingBooking() {
        Flight flight = flight();
        flight.reserveSeats(3);
        Booking original = new Booking(flight, "Smit Lakhani", 3, "demo-1",
                                       request(3, "demo-1").fingerprint());
        when(bookingRepository.findByIdempotencyKey("demo-1")).thenReturn(Optional.of(original));

        BookingDto dto = bookingService.book(request(3, "demo-1"));

        assertThat(dto.seats()).isEqualTo(3);
        verifyNoInteractions(bookingWriter);
    }

    @Test
    @DisplayName("REGRESSION: a lost idempotency-key race recovers the winner's booking instead of surfacing 409")
    void racingTheWriterRecoversTheWinner() {
        when(bookingRepository.findByIdempotencyKey("raced-key")).thenReturn(Optional.empty());
        when(bookingWriter.insertNewBooking(any())).thenThrow(new DataIntegrityViolationException("dup"));
        BookingDto winner = new BookingDto(2L, "UA123", "Smit Lakhani", 3, Instant.now(), null);
        when(bookingWriter.recoverReplay(eq("raced-key"), any())).thenReturn(winner);

        BookingDto dto = bookingService.book(request(3, "raced-key"));

        assertThat(dto).isEqualTo(winner);
        verify(bookingWriter).recoverReplay(eq("raced-key"), any());
    }

    @Test
    @DisplayName("a non-constraint failure from the writer propagates, not recovered")
    void unknownFlightPropagatesWithoutRecovery() {
        when(bookingRepository.findByIdempotencyKey("demo-4")).thenReturn(Optional.empty());
        when(bookingWriter.insertNewBooking(any())).thenThrow(new FlightNotFoundException("XX999"));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("xx999", "Smit Lakhani", 1, "demo-4")))
                .isInstanceOf(FlightNotFoundException.class);

        verify(bookingWriter, never()).recoverReplay(any(), any());
    }

    @Test
    @DisplayName("findById maps the entity to a DTO")
    void findByIdReturnsTheBooking() {
        Booking booking = new Booking(flight(), "Smit Lakhani", 3, "demo-6", null);
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

        BookingDto dto = bookingService.findById(1L);

        assertThat(dto.passengerName()).isEqualTo("Smit Lakhani");
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.seats()).isEqualTo(3);
    }

    @Test
    @DisplayName("findById on an unknown id throws BookingNotFoundException, carrying the id")
    void unknownBookingIsA404() {
        when(bookingRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.findById(999L))
                .isInstanceOf(BookingNotFoundException.class)
                .hasMessageContaining("999");
    }
}
