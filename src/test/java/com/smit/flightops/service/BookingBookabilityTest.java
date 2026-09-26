package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * What decides whether {@link BookingWriter#insertNewBooking} may sell a seat: the
 * flight's status, checked before the seat count, and not its departure time.
 * doc/api.md names the statuses that sell seats and gives no time rule. The rest of
 * the write path is {@link BookingWriterTest}'s job.
 */
@ExtendWith(MockitoExtension.class)
class BookingBookabilityTest {

    /** The fixed clock's instant. */
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");

    /** Ahead of the fixed clock and the wall clock alike, so only the status can refuse it. */
    private static final Instant DEPARTURE = Instant.parse("2099-03-01T08:00:00Z");

    @Mock private FlightRepository flightRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private OutboxWriter outboxWriter;

    @Spy private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks private BookingWriter bookingWriter;

    @ParameterizedTest
    @EnumSource(value = FlightStatus.class, names = {"CANCELLED", "DEPARTED", "ARRIVED"})
    @DisplayName("a sold-out flight that can no longer sell is refused as not bookable, not as full")
    void aSoldOutUnbookableFlightIsNotBookableRatherThanFull(FlightStatus status) {
        // Flights are usually full by departure, and cancelling one releases no seats.
        // INSUFFICIENT_SEATS invites a retry with fewer seats, which can never succeed
        // here, so the status has to be checked before the count.
        Flight soldOut = new Flight("UA901", "EWR", "LHR", 2, DEPARTURE);
        soldOut.reserveSeats(2);
        if (status == FlightStatus.CANCELLED) {
            soldOut.cancel();
        } else {
            // Through DEPARTED, because the entity refuses SCHEDULED -> ARRIVED.
            soldOut.updateStatus(FlightStatus.DEPARTED);
            soldOut.updateStatus(status);
        }
        when(flightRepository.findByFlightNumberForUpdate("UA901")).thenReturn(Optional.of(soldOut));

        assertThatThrownBy(() -> bookingWriter.insertNewBooking(
                new BookingRequest("UA901", "Test Passenger", 1, "sold-out-1")))
                .isInstanceOfSatisfying(FlightNotBookableException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(status));

        assertThat(soldOut.getAvailableSeats()).isZero();
        verify(bookingRepository, never()).save(any());
        verifyNoInteractions(outboxWriter);
    }

    @ParameterizedTest
    @EnumSource(names = {"SCHEDULED", "BOARDING", "DELAYED"})
    @DisplayName("bookability depends on status alone: a flight a week past its departure time still sells")
    void anOverdueFlightStillSells(FlightStatus status) {
        // Nothing compares departureTime with a clock, so a flight nobody has moved
        // to DEPARTED keeps selling. Refusing overdue flights would be a policy
        // change, and this test has to change with it. A week before the fixed clock
        // is also before the wall clock, so a guard on either clock fails here, even
        // one that allows a grace period shorter than a week.
        Flight overdue = new Flight("UA902", "EWR", "LHR", 180, NOW.minus(Duration.ofDays(7)));
        overdue.updateStatus(status);
        when(flightRepository.findByFlightNumberForUpdate("UA902")).thenReturn(Optional.of(overdue));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(i -> i.getArgument(0));

        BookingDto dto = bookingWriter.insertNewBooking(
                new BookingRequest("UA902", "Test Passenger", 2, "overdue-1"));

        assertThat(dto.flightNumber()).isEqualTo("UA902");
        assertThat(dto.seats()).isEqualTo(2);
        assertThat(overdue.getAvailableSeats()).isEqualTo(178);
        verify(outboxWriter).recordBookingCreated(dto);
    }
}
