package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.BookingNotCancellableException;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
import com.smit.flightops.exception.LostIdempotencyRaceException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Locale;

/**
 * Every transaction that moves seats, in one bean.
 *
 * <p>Separate from {@link BookingService} so that {@code book}, which is not
 * {@code @Transactional}, calls each method here through Spring's proxy and the
 * propagation below applies. The cancellation lives here beside the insert
 * because both lock the flight row and then the booking row, in that order, and
 * the two paths deadlock if either one reverses it.
 */
@Component
public class BookingWriter {

    private static final Logger log = LoggerFactory.getLogger(BookingWriter.class);

    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    // No BookingMetrics: everything here can still roll back, so BookingService
    // counts outcomes after these methods return.
    public BookingWriter(FlightRepository flightRepository,
                          BookingRepository bookingRepository,
                          OutboxWriter outboxWriter,
                          Clock clock) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
    }

    /**
     * The insert attempt, in its own transaction. Locks the flight row, re-checks
     * the idempotency key under that lock, debits seats, saves the booking and
     * records the event, in that order.
     *
     * <p>Any failure rolls the whole transaction back, seat debit included, so a
     * losing attempt leaves no trace ({@code oversellLeavesNoTrace} pins the same
     * for an oversell).
     */
    @Transactional
    public BookingDto insertNewBooking(BookingRequest request) {
        String flightNumber = request.flightNumber().trim().toUpperCase(Locale.ROOT);
        Flight flight = flightRepository.findByFlightNumberForUpdate(flightNumber)
                .orElseThrow(() -> new FlightNotFoundException(request.flightNumber()));

        // The key again, now under the flight row lock. A competing booking on this
        // flight has either committed, and is visible here, or has not started. This
        // read comes before reserveSeats so that a replay racing a winner who took
        // the last seats gets the winner's booking, not InsufficientSeatsException.
        // uk_bookings_idempotency_key stays as the backstop for one key on two
        // flights: they lock different rows and never queue behind each other.
        if (bookingRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            throw new LostIdempotencyRaceException(request.idempotencyKey());
        }

        flight.reserveSeats(request.seats());

        Booking booking = bookingRepository.save(new Booking(
                flight, request.passengerName(), request.seats(),
                request.idempotencyKey(), request.fingerprint(), clock.instant()));

        // No passenger name: it is personal data, and log aggregation keeps and
        // indexes these lines. The booking id joins to the row that holds it.
        log.info("Booked {} seat(s) on {} (booking {}, {} seats left)",
                 booking.getSeats(), flightNumber,
                 booking.getId(), flight.getAvailableSeats());

        BookingDto dto = BookingDto.from(booking);

        // An INSERT into outbox_events in this transaction, not a network call, so
        // the event rolls back with the booking. OutboxPublisher sends it later,
        // outside every lock held here.
        outboxWriter.recordBookingCreated(dto);
        return dto;
    }

    /**
     * Re-reads the winner of a lost idempotency-key race, in a new transaction.
     *
     * <p>The winner has committed by now: the loser's re-read waited on the flight
     * row lock, and a PostgreSQL {@code INSERT} blocks on the unique index until the
     * other transaction resolves. {@code book} is not transactional, so a plain
     * read-only transaction would also work. {@code REQUIRES_NEW} keeps this off an
     * aborted connection if {@code book} ever becomes transactional.
     *
     * @throws IllegalStateException if no booking holds the key, meaning the insert
     *         failed for some other reason; {@code book} then rethrows that failure
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public BookingDto recoverReplay(String idempotencyKey, String fingerprint) {
        Booking winner = bookingRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No booking holds idempotency key " + idempotencyKey
                        + " after a failed insert, so there is no winner to recover"));

        // The same fingerprint check as the replay path in BookingService: two
        // different requests on one key can arrive together and meet here instead.
        if (!winner.matchesRequest(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return BookingDto.from(winner);
    }

    /**
     * What a cancellation did, so the caller can count it after the commit. The
     * returned {@link BookingDto} cannot say whether this call or an earlier one
     * wrote {@code cancelledAt}.
     */
    public record Cancellation(BookingDto booking, boolean seatsReleased) {
    }

    /**
     * Cancels a booking and credits its seats back to the flight.
     *
     * <p>Lock order: flight row first, booking row second, as in
     * {@link #insertNewBooking}. Opposite orders on two paths deadlock under load.
     * The flight number comes from a scalar projection because loading the
     * {@code Booking} first would put it in the persistence context unlocked, and
     * {@code findByIdForUpdate} would return that stale instance.
     *
     * <p>Idempotent: {@link Booking#cancel} returns false for a booking already
     * cancelled, and seats are credited only on true, so a retried {@code DELETE}
     * is a no-op. {@code Flight.releaseSeats} clamps to {@code totalSeats}, which
     * bounds a double credit but does not prevent it.
     *
     * <p>An active booking on a departed or arrived flight is refused, and nothing
     * changes. A booking already cancelled is not: idempotency comes first, so its
     * retry stays a 200 whatever the flight has done since. The status is read
     * under the flight row lock, so a {@code PATCH} to DEPARTED that committed
     * first is seen here, and one that read the row before this cancellation
     * released its seats fails on {@code @Version}.
     *
     * <p>No counters here: the commit can still fail, although not on
     * {@code Flight}'s {@code @Version}, because the row is locked from read to
     * commit. There is no {@code BookingCancelled} event either. It would need a
     * second event type and a second branch in the Lambda.
     */
    @Transactional
    public Cancellation cancelBooking(Long bookingId) {
        String flightNumber = bookingRepository.findFlightNumberById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        Flight flight = flightRepository.findByFlightNumberForUpdate(flightNumber)
                .orElseThrow(() -> new FlightNotFoundException(flightNumber));

        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!booking.isCancelled() && !flight.getStatus().acceptsCancellations()) {
            throw new BookingNotCancellableException(bookingId, flightNumber, flight.getStatus());
        }

        boolean released = booking.cancel(clock.instant());
        if (released) {
            flight.releaseSeats(booking.getSeats());
            log.info("Cancelled booking {} on {} ({} seat(s) released, {} now available)",
                     bookingId, flightNumber, booking.getSeats(), flight.getAvailableSeats());
        } else {
            log.info("Booking {} was already cancelled at {} — no seats released",
                     bookingId, booking.getCancelledAt());
        }
        return new Cancellation(BookingDto.from(booking), released);
    }
}
