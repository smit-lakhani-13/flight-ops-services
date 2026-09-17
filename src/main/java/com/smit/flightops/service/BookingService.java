package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;
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
        // 1. Replay after the original committed? Return it. Same key, same
        //    answer, seats debited exactly once — this is what makes POST
        //    safe to retry once the first attempt is done and visible.
        var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay of key {} -> booking {}",
                     request.idempotencyKey(), existing.get().getId());
            return BookingDto.from(existing.get());
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
            return bookingWriter.recoverReplay(request.idempotencyKey());
        }
    }

    /**
     * The canonical read for one booking. This exists because {@code POST
     * /api/v1/bookings} returns {@code Location: /api/v1/bookings/{id}}, and a
     * Location header pointing at a URL that 404s is worse than no header at all.
     */
    public BookingDto findById(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .map(BookingDto::from)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
    }

    public List<BookingDto> findByFlightNumber(String flightNumber) {
        return bookingRepository.findByFlightNumber(flightNumber.trim().toUpperCase(Locale.ROOT))
                .stream()
                .map(BookingDto::from)
                .toList();
    }
}
