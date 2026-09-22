package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
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
 * <p>Split out of {@link BookingService} so that {@code book} — which is
 * deliberately NOT itself {@code @Transactional} — can call each edge through
 * Spring's AOP proxy rather than through {@code this.}, which is what makes the
 * propagation below actually apply. See {@link BookingService#book} for why
 * that split exists and what it fixed.
 *
 * <p>Three edges now: the insert, the race recovery, and the cancellation. The
 * cancellation belongs here rather than in {@code BookingService} because it
 * takes the same lock on the same row in the same order as the insert, and that
 * ordering is the only thing standing between the two paths and a deadlock.
 * Keeping both in one file is how the next person finds that out.
 */
@Component
public class BookingWriter {

    private static final Logger log = LoggerFactory.getLogger(BookingWriter.class);

    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;

    // No BookingMetrics here, and its absence is the point: everything this
    // class does happens inside a transaction that can still roll back, so
    // there is nothing here worth counting yet. BookingService owns the meters.
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
     * The insert attempt, in its own transaction. Locks the flight row, debits
     * seats, saves the booking, records the event — all four in a strict
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

        // The idempotency key, checked a second time — and this is the only
        // place in the request where the answer can be trusted.
        //
        // BookingService.book checked it before calling, outside any lock, and
        // could not see a concurrent request that had not committed yet. Here
        // the flight row is locked, so a competing booking on this flight has
        // either committed or has not started: if it committed, its key is
        // visible to this read.
        //
        // Without this the race was still resolved — by
        // uk_bookings_idempotency_key, a few lines further down — but only
        // when there were seats left to debit first. When the winner took the
        // LAST seats, reserveSeats threw InsufficientSeatsException before the
        // insert ever ran, and the loser got 409 for a booking that had in fact
        // been made. A client retrying a request that succeeded was told the
        // flight was full, and the race on the last seat is exactly the request
        // somebody retries.
        //
        // The constraint stays as the backstop, because this read cannot cover
        // everything: the same key used for a DIFFERENT flight locks a
        // different row, so those two requests never queue behind each other.
        // That case reaches the insert, fails the constraint, and recoverReplay
        // answers it with the 409 it deserves — same key, different request.
        if (bookingRepository.findByIdempotencyKey(request.idempotencyKey()).isPresent()) {
            throw new LostIdempotencyRaceException(request.idempotencyKey());
        }

        flight.reserveSeats(request.seats());

        Booking booking = bookingRepository.save(new Booking(
                flight, request.passengerName(), request.seats(),
                request.idempotencyKey(), request.fingerprint()));

        // No passenger name in this line. It used to be here, and it is the one
        // field in the request that is personal data: log aggregation ships
        // these lines to a third party, keeps them for months and indexes them
        // for search, which turns an application log into an un-audited copy of
        // the passenger list. The booking id is the join key to the row that
        // does hold the name, behind whatever authorisation the database has.
        log.info("Booked {} seat(s) on {} (booking {}, {} seats left)",
                 booking.getSeats(), flightNumber,
                 booking.getId(), flight.getAvailableSeats());

        BookingDto dto = BookingDto.from(booking);

        // An INSERT into outbox_events, in this transaction. Not a network
        // call: that is the entire change, and it is why the flight row lock is
        // now held for the duration of two local writes instead of for however
        // long SQS takes to answer. If this transaction rolls back - an
        // oversell, a lost idempotency race - the event row rolls back with the
        // booking, so there is no event describing a booking that does not
        // exist. OutboxPublisher does the sending, afterwards, outside every
        // lock this method holds.
        outboxWriter.recordBookingCreated(dto);
        return dto;
    }

    /**
     * Re-reads the winner of a lost idempotency-key race, in a brand-new
     * transaction.
     *
     * <p>{@code REQUIRES_NEW} is belt-and-braces here, not the thing that
     * makes this work — worth being precise about, because the obvious story
     * is the wrong one. What makes it work is that {@link BookingService#book}
     * is not {@code @Transactional}: by the time {@code
     * DataIntegrityViolationException} reaches its catch block, Spring's
     * interceptor has already rolled {@code insertNewBooking}'s transaction
     * back and returned its connection, so there is no transaction left to
     * join and a plain {@code @Transactional(readOnly = true)} would behave
     * identically. Before the {@code BookingWriter} split, {@code book} *was*
     * transactional, and then this read genuinely did run on the connection
     * PostgreSQL had marked aborted — it got {@code current transaction is
     * aborted} instead of a row, which is how the race loser ended up with a
     * 409. {@code REQUIRES_NEW} keeps that guarantee if {@code book} ever
     * becomes transactional again.
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
    public BookingDto recoverReplay(String idempotencyKey, String fingerprint) {
        Booking winner = bookingRepository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Lost a unique-constraint race on idempotency key " + idempotencyKey +
                        " but no winning booking exists to recover — should be unreachable, " +
                        "see BookingWriter.recoverReplay's Javadoc for why."));

        // The fingerprint check belongs here as well as on the committed-replay
        // path in BookingService, and forgetting it here would leave the bug
        // half fixed: two requests reusing one key for different bookings can
        // arrive close enough together that neither sees the other's row, and
        // then the loser arrives at exactly this line. Same key, different
        // request, so the same 409 the sequential case gets.
        if (!winner.matchesRequest(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return BookingDto.from(winner);
    }

    /**
     * Cancels a booking and credits its seats back to the flight.
     *
     * <p>The lock order is the load-bearing part: flight row first, booking row
     * second, which is the same order {@link #insertNewBooking} takes them in
     * (it locks the flight, then inserts into {@code bookings}). Two paths that
     * take the same two locks in opposite orders deadlock under concurrency,
     * and the database resolves it by killing one of them — intermittently, in
     * production, under load. That is why the flight number is fetched with a
     * scalar projection first: loading the {@code Booking} entity to read
     * {@code booking.getFlight().getFlightNumber()} would put it in the
     * persistence context before the flight is locked, and the later
     * {@code findByIdForUpdate} would hand back that same stale instance from
     * the first-level cache instead of going to the database for the committed
     * {@code cancelled_at}.
     *
     * <p>Idempotent by construction: {@link Booking#cancel} returns false if
     * the row is already cancelled, and the seats are only credited when it
     * returns true. So a retried {@code DELETE} is a no-op, not a second
     * refund. {@code Flight.releaseSeats} clamps to {@code totalSeats}, which
     * would bound the damage of a double credit, but bounding is not
     * preventing: on a 180-seat flight with 100 sold, a double cancel of a
     * 2-seat booking would still invent 2 seats.
     *
     * <p>The counters are <em>not</em> incremented here, which is the rule the
     * booking counter already follows: a meter records an outcome, not an
     * attempt. This method is {@code @Transactional}, so everything in it can
     * still be undone after the last statement runs — {@code Flight} carries an
     * {@code @Version}, and a concurrent write makes the commit itself throw.
     * A counter incremented before that commit counts a cancellation that never
     * happened, and the graph then disagrees with the table it is supposed to
     * describe. {@code BookingService.cancel} increments it once this returns.
     *
     * <p>No {@code BookingCancelled} event, and that is a deliberate omission
     * rather than an oversight. Publishing one means a second event type on the
     * queue, a second branch in the Lambda and a decision about what a
     * cancellation does to the DynamoDB record it can no longer find — real
     * work, and none of it needed to make cancellation correct here. The
     * limitation is written down rather than papered over.
     */
    /**
     * What a cancellation did, so the caller can count it after the commit.
     *
     * <p>The outcome is not recoverable from the returned {@link BookingDto}: a
     * booking cancelled a moment ago and one cancelled an hour ago both carry a
     * {@code cancelledAt}, and only this transaction knows which of the two it
     * just wrote. Returning the flag is the alternative to incrementing a
     * counter inside a transaction that can still roll back.
     */
    public record Cancellation(BookingDto booking, boolean seatsReleased) {
    }

    @Transactional
    public Cancellation cancelBooking(Long bookingId) {
        String flightNumber = bookingRepository.findFlightNumberById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

        Flight flight = flightRepository.findByFlightNumberForUpdate(flightNumber)
                .orElseThrow(() -> new FlightNotFoundException(flightNumber));

        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));

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
