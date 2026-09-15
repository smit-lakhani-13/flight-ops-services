package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingServiceTest {

    @Mock private FlightRepository flightRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private EventPublisher eventPublisher;

    @InjectMocks private BookingService bookingService;

    private static final Instant DEPARTURE = Instant.now().plus(Duration.ofHours(8));

    private Flight flight() {
        return new Flight("UA123", "EWR", "LHR", 180, DEPARTURE);
    }

    private BookingRequest request(int seats, String key) {
        return new BookingRequest("UA123", "Smit Lakhani", seats, key);
    }

    @Test
    @DisplayName("a first-time booking locks the flight, debits seats, persists, then publishes")
    void happyPath() {
        Flight flight = flight();
        when(bookingRepository.findByIdempotencyKey("demo-1")).thenReturn(Optional.empty());
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        BookingDto dto = bookingService.book(request(3, "demo-1"));

        assertThat(flight.getAvailableSeats()).isEqualTo(177);
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.seats()).isEqualTo(3);
        assertThat(dto.idempotencyKey()).isEqualTo("demo-1");
        verify(eventPublisher).publishBookingCreated(dto);
    }

    @Test
    @DisplayName("a replayed key returns the original booking and debits nothing")
    void replayIsAServedFromTheExistingBooking() {
        Flight flight = flight();
        flight.reserveSeats(3);
        Booking original = new Booking(flight, "Smit Lakhani", 3, "demo-1");
        when(bookingRepository.findByIdempotencyKey("demo-1")).thenReturn(Optional.of(original));

        BookingDto dto = bookingService.book(request(3, "demo-1"));

        assertThat(dto.seats()).isEqualTo(3);
        assertThat(flight.getAvailableSeats()).isEqualTo(177);   // still 177, not 174
        verify(flightRepository, never()).findByFlightNumberForUpdate(any());
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("the flight row is read FOR UPDATE, not with a plain lookup")
    void bookingTakesAPessimisticLock() {
        when(bookingRepository.findByIdempotencyKey("demo-2")).thenReturn(Optional.empty());
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight()));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        bookingService.book(request(1, "demo-2"));

        verify(flightRepository).findByFlightNumberForUpdate("UA123");
        verify(flightRepository, never()).findByFlightNumber(any());
    }

    @Test
    @DisplayName("overselling aborts before anything is written or published")
    void oversellIsRejected() {
        Flight flight = flight();
        flight.reserveSeats(179);
        when(bookingRepository.findByIdempotencyKey("demo-3")).thenReturn(Optional.empty());
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));

        assertThatThrownBy(() -> bookingService.book(request(2, "demo-3")))
                .isInstanceOf(InsufficientSeatsException.class);

        assertThat(flight.getAvailableSeats()).isEqualTo(1);
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
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
        when(bookingRepository.findByIdempotencyKey("demo-5")).thenReturn(Optional.empty());
        when(flightRepository.findByFlightNumberForUpdate("UA123")).thenReturn(Optional.of(flight));

        assertThatThrownBy(() -> bookingService.book(request(1, "demo-5")))
                .isInstanceOf(FlightNotBookableException.class)
                .hasMessageContaining("CANCELLED");

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("findById maps the entity to a DTO")
    void findByIdReturnsTheBooking() {
        Booking booking = new Booking(flight(), "Smit Lakhani", 3, "demo-6");
        when(bookingRepository.findById(1L)).thenReturn(Optional.of(booking));

        BookingDto dto = bookingService.findById(1L);

        assertThat(dto.passengerName()).isEqualTo("Smit Lakhani");
        assertThat(dto.flightNumber()).isEqualTo("UA123");
        assertThat(dto.idempotencyKey()).isEqualTo("demo-6");
    }

    @Test
    @DisplayName("findById on an unknown id throws BookingNotFoundException, carrying the id")
    void unknownBookingIsA404() {
        when(bookingRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.findById(999L))
                .isInstanceOf(BookingNotFoundException.class)
                .hasMessageContaining("999");

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unknownFlightIsA404() {
        when(bookingRepository.findByIdempotencyKey("demo-4")).thenReturn(Optional.empty());
        when(flightRepository.findByFlightNumberForUpdate("XX999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("xx999", "Smit Lakhani", 1, "demo-4")))
                .isInstanceOf(FlightNotFoundException.class);

        verifyNoInteractions(eventPublisher);
    }
}
