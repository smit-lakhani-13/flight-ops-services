package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * The two transactional edges of a booking, split into their own bean so
 * {@link BookingService#book} — which is deliberately NOT itself
 * {@code @Transactional} — can call each through Spring's AOP proxy rather
 * than through {@code this.}, which is what makes the propagation below
 * actually apply. See {@link BookingService#book} for why this split exists
 * and what it fixed.
 */
@Component
public class BookingWriter {

    private static final Logger log = LoggerFactory.getLogger(BookingWriter.class);

    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final EventPublisher eventPublisher;

    public BookingWriter(FlightRepository flightRepository,
                          BookingRepository bookingRepository,
                          EventPublisher eventPublisher) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * The insert attempt, in its own transaction. Locks the flight row, debits
     * seats, saves the booking, publishes the event — all four in a strict
     * order, per {@link BookingService#book}'s numbered comment.
     *
     * <p>If this throws {@code DataIntegrityViolationException} (the
     * {@code idempotency_key} unique constraint lost a race), the whole
     * transaction rolls back, including the seat debit: a losing attempt must
     * leave no trace, exactly like {@code oversellLeavesNoTrace} already pins
     * for the oversell case.
     */
    @Transactional
    public BookingDto insertNewBooking(BookingRequest request) {
        String flightNumber = request.flightNumber().trim().toUpperCase(Locale.ROOT);
        Flight flight = flightRepository.findByFlightNumberForUpdate(flightNumber)
                .orElseThrow(() -> new FlightNotFoundException(request.flightNumber()));

        flight.reserveSeats(request.seats());

        Booking booking = bookingRepository.save(new Booking(
                flight, request.passengerName(), request.seats(), request.idempotencyKey()));

        log.info("Booked {} seat(s) on {} for {} (booking {}, {} seats left)",
                 booking.getSeats(), flightNumber, booking.getPassengerName(),
                 booking.getId(), flight.getAvailableSeats());

        BookingDto dto = BookingDto.from(booking);
        eventPublisher.publishBookingCreated(dto);
        return dto;
    }

    /**
     * Re-reads the winner of a lost idempotency-key race, in a brand-new
     * transaction.
     *
     * <p>{@code REQUIRES_NEW}, not the default propagation: {@code
     * insertNewBooking} above just failed and PostgreSQL marks that
     * connection's transaction aborted until it rolls back — any further
     * statement on it, including a plain read, gets {@code current
     * transaction is aborted} instead of a row. A fresh transaction sidesteps
     * that entirely.
     *
     * <p>Safe to assume the winning row is already committed and visible:
     * PostgreSQL does not let the loser's {@code INSERT} discover the
     * conflict until the winner's transaction has actually committed or
     * rolled back — the loser blocks on the unique index until then. By the
     * time {@code DataIntegrityViolationException} reaches
     * {@link BookingService#book}, the winner is not "probably" done, it is
     * done.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public BookingDto recoverReplay(String idempotencyKey) {
        return bookingRepository.findByIdempotencyKey(idempotencyKey)
                .map(BookingDto::from)
                .orElseThrow(() -> new IllegalStateException(
                        "Lost a unique-constraint race on idempotency key " + idempotencyKey +
                        " but no winning booking exists to recover — should be unreachable, " +
                        "see BookingWriter.recoverReplay's Javadoc for why."));
    }
}
