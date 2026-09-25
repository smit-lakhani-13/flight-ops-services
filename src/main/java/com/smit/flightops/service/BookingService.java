package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
import com.smit.flightops.exception.LostIdempotencyRaceException;
import com.smit.flightops.observability.BookingMetrics;
import com.smit.flightops.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * The money path, guarding two hazards with two mechanisms. A retried request is
 * answered by {@code idempotencyKey} and its request fingerprint, backed by
 * {@code uk_bookings_idempotency_key}. Two bookings for the last seats are
 * serialised by {@code SELECT ... FOR UPDATE} on the flight row. Neither covers
 * the other's hazard.
 *
 * <p>{@link #book} is not {@code @Transactional}: each write is a call through the
 * proxy to {@link BookingWriter}, so the loser of a race is rolled back before
 * this class recovers the winner.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingWriter bookingWriter;
    private final BookingMetrics metrics;

    public BookingService(BookingRepository bookingRepository, BookingWriter bookingWriter,
                          BookingMetrics metrics) {
        this.bookingRepository = bookingRepository;
        this.bookingWriter = bookingWriter;
        this.metrics = metrics;
    }

    public BookingDto book(BookingRequest request) {
        String fingerprint = request.fingerprint();

        // 1. A replay of a committed booking returns it, but only if the fingerprint
        //    matches. The same key on a different request is a 409; the unique
        //    constraint cannot catch that, because one booking per key is all it checks.
        var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            Booking booking = existing.get();
            if (!booking.matchesRequest(fingerprint)) {
                log.warn("Idempotency key {} reused for a different booking (existing booking {})",
                         request.idempotencyKey(), booking.getId());
                throw new IdempotencyKeyConflictException(request.idempotencyKey());
            }
            log.info("Idempotent replay of key {} -> booking {}",
                     request.idempotencyKey(), booking.getId());
            metrics.bookingReplayed();
            return BookingDto.from(booking);
        }

        // 2. No committed row: attempt the insert. A concurrent request with the
        //    same key is invisible to the read above until it commits.
        try {
            BookingDto dto = bookingWriter.insertNewBooking(request);
            // Counted after the call, because insertNewBooking can still throw.
            metrics.bookingCreated();
            return dto;
        } catch (LostIdempotencyRaceException | DataIntegrityViolationException e) {
            // 3. A lost race. On one flight, insertNewBooking's re-read under the row
            //    lock throws LostIdempotencyRaceException. The same key on two flights
            //    locks two rows and reaches uk_bookings_idempotency_key instead. Either
            //    way the winner has committed. recoverReplay returns its booking (201)
            //    when the fingerprints match and throws the 409 when they differ, as
            //    they always do across two flights.
            BookingDto winner;
            try {
                winner = bookingWriter.recoverReplay(request.idempotencyKey(), fingerprint);
            } catch (IllegalStateException noWinner) {
                // No row holds this key, so the insert failed for another reason.
                // Rethrow that failure instead of reporting a race.
                e.addSuppressed(noWinner);
                log.warn("Insert for idempotency key {} failed and no booking holds the key; "
                         + "not a lost race", request.idempotencyKey());
                throw e;
            }
            log.info("Lost an idempotency-key race on {}; recovered the winner's booking {}",
                     request.idempotencyKey(), winner.bookingId());
            metrics.bookingReplayed();
            return winner;
        }
    }

    /**
     * Cancels a booking and returns the cancelled record, so a retried cancellation
     * can show {@code cancelledAt}. The transaction and the lock order live in
     * {@link BookingWriter#cancelBooking}, beside the insert that takes the same locks.
     */
    public BookingDto cancel(Long bookingId) {
        // Counted after the writer's transaction has committed.
        BookingWriter.Cancellation result = bookingWriter.cancelBooking(bookingId);
        if (result.seatsReleased()) {
            metrics.bookingCancelled();
        } else {
            metrics.cancellationWasANoOp();
        }
        return result.booking();
    }

    /**
     * The read behind the {@code Location} header that {@code POST /api/v1/bookings}
     * returns. It needs a transaction, read-only because nothing is written:
     * {@code findById} is the inherited method with no {@code JOIN FETCH}, and
     * {@code BookingDto.from} reads the lazy {@code Booking.flight}. Without a
     * transaction that throws {@code LazyInitializationException} (see
     * {@code BookingFindByIdLazyLoadingTest}).
     */
    @Transactional(readOnly = true)
    public BookingDto findById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingDto::from)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /**
     * Bookings on one flight, paged. Cancelled bookings keep their rows, so the
     * number of rows for one flight has no upper bound.
     */
    public Page<BookingDto> findByFlightNumber(String flightNumber, Pageable pageable) {
        return bookingRepository
                .findByFlightNumber(flightNumber.trim().toUpperCase(Locale.ROOT), pageable)
                .map(BookingDto::from);
    }
}
