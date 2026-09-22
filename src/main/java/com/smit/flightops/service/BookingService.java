package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
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
 * The money path. Two independent hazards live here and each gets its own
 * mechanism:
 *
 * <ol>
 *   <li><b>Duplicate requests</b> — the client retries after a timeout it never
 *       learned the outcome of. Guarded by {@code idempotencyKey} plus the unique
 *       constraint {@code uk_bookings_idempotency_key}.</li>
 *   <li><b>Concurrent requests for the last seats</b> — two different bookings
 *       read 1 available seat and both reserve it. Guarded by
 *       {@code SELECT ... FOR UPDATE} on the flight row.</li>
 * </ol>
 *
 * <p>They are not interchangeable: the unique key does nothing about oversell,
 * and the row lock does nothing about a retry that arrives an hour later.
 *
 * <p>This class is deliberately NOT {@code @Transactional} on {@link #book}.
 * The actual write lives in {@link BookingWriter#insertNewBooking}, on a
 * different bean, called through Spring's proxy rather than through
 * {@code this.} — see that class's Javadoc for why the split is load-bearing
 * and not just style.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingWriter bookingWriter;

    public BookingService(BookingRepository bookingRepository, BookingWriter bookingWriter) {
        this.bookingRepository = bookingRepository;
        this.bookingWriter = bookingWriter;
    }

    public BookingDto book(BookingRequest request) {
        String fingerprint = request.fingerprint();

        // 1. Replay after the original committed? Return it — but only if it is
        //    genuinely the same request. Same key, same answer, seats debited
        //    exactly once is what makes POST safe to retry once the first
        //    attempt is done and visible.
        //
        //    The fingerprint comparison is the part that used to be missing,
        //    and its absence was the worst bug in this class. A client that
        //    reused one key for a different passenger, flight or seat count got
        //    201 and the FIRST booking's details back: no seats debited for the
        //    booking it thought it had just made, no error to tell it so, and a
        //    confirmation naming somebody else. The unique constraint cannot
        //    catch this — it is doing exactly its job, one booking per key. The
        //    key was never the whole of the request, and now it is not treated
        //    as though it were.
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
            return BookingDto.from(booking);
        }

        // 2. Not visible yet — attempt the insert. If a concurrent request
        //    with the same key is *also* mid-flight, the check above cannot
        //    see it (it hasn't committed), so both requests reach here and
        //    race for real. insertNewBooking's own transaction, not this
        //    method, decides who wins.
        try {
            return bookingWriter.insertNewBooking(request);
        } catch (DataIntegrityViolationException e) {
            // 3. Lost the race: uk_bookings_idempotency_key fired. The winner
            //    is guaranteed committed by now (Postgres blocks the loser's
            //    INSERT on the unique index until the winner resolves), so
            //    recover its row in a fresh transaction and hand back the
            //    SAME answer the winner got — 201, not an error. Without this
            //    recovery step, the loser of a genuine race would get 409 for
            //    a request that, from the client's point of view, succeeded.
            log.info("Lost an idempotency-key race on {} — recovering the winner's booking",
                     request.idempotencyKey());
            return bookingWriter.recoverReplay(request.idempotencyKey(), fingerprint);
        }
    }

    /**
     * Cancels a booking and returns the cancelled record.
     *
     * <p>A thin delegate on purpose. The transaction, the lock order and the
     * idempotency of the seat credit all live in
     * {@link BookingWriter#cancelBooking}, next to the insert that takes the
     * same locks — see that method's Javadoc. Putting the {@code @Transactional}
     * here instead would separate the two paths that have to agree about lock
     * ordering, which is how they come to disagree.
     *
     * <p>Returns the booking rather than void so the caller can see
     * {@code cancelledAt} — and so a retried cancellation has something
     * truthful to return instead of a second 204 that implies it did something.
     */
    public BookingDto cancel(Long bookingId) {
        return bookingWriter.cancelBooking(bookingId);
    }

    /**
     * The canonical read for one booking. This exists because {@code POST
     * /api/v1/bookings} returns {@code Location: /api/v1/bookings/{id}}, and a
     * Location header pointing at a URL that 404s is worse than no header at all.
     *
     * <p>{@code @Transactional(readOnly = true)}, not left implicit: unlike
     * {@link #findByFlightNumber}, {@link BookingRepository#findById} is the
     * plain inherited {@code JpaRepository} method, not a {@code JOIN FETCH}
     * query — it opens and closes its own short session around just that call.
     * Without a wider transaction here, {@code BookingDto.from} dereferences
     * the lazy {@code Booking.flight} proxy after that session has already
     * closed, throwing {@code LazyInitializationException} on every real
     * lookup. See {@code BookingFindByIdLazyLoadingTest} for the repro.
     */
    @Transactional(readOnly = true)
    public BookingDto findById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingDto::from)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    /**
     * Bookings on one flight, paged.
     *
     * <p>This used to return {@code List<BookingDto>} with no limit. The
     * defence was that a flight has at most 850 seats and seats cap at 9 per
     * booking, so the list is bounded — which was true of the *active*
     * bookings and stopped being true the moment cancellation arrived in V4.
     * Cancelled bookings keep their rows, so a popular route rebooked over and
     * over has no ceiling at all. An unbounded list endpoint whose bound was an
     * argument rather than a {@code LIMIT} is a slow leak: correct on the day
     * it ships, and nobody re-checks the argument when the schema changes.
     */
    public Page<BookingDto> findByFlightNumber(String flightNumber, Pageable pageable) {
        return bookingRepository
                .findByFlightNumber(flightNumber.trim().toUpperCase(Locale.ROOT), pageable)
                .map(BookingDto::from);
    }
}
