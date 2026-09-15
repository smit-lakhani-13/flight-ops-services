package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.entity.Booking;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.BookingNotFoundException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
@Transactional(readOnly = true)
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final FlightRepository flightRepository;
    private final BookingRepository bookingRepository;
    private final EventPublisher eventPublisher;

    public BookingService(FlightRepository flightRepository,
                          BookingRepository bookingRepository,
                          EventPublisher eventPublisher) {
        this.flightRepository = flightRepository;
        this.bookingRepository = bookingRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public BookingDto book(BookingRequest request) {
        // 1. Replay? Return the original booking. Same key, same answer, seats
        //    debited exactly once — this is what makes POST safe to retry.
        var existing = bookingRepository.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            log.info("Idempotent replay of key {} -> booking {}",
                     request.idempotencyKey(), existing.get().getId());
            return BookingDto.from(existing.get());
        }

        // 2. Lock the flight row for the duration of the transaction. A plain
        //    findByFlightNumber here would let two transactions both read
        //    availableSeats=1 and both succeed.
        String flightNumber = request.flightNumber().trim().toUpperCase(Locale.ROOT);
        Flight flight = flightRepository.findByFlightNumberForUpdate(flightNumber)
                .orElseThrow(() -> new FlightNotFoundException(request.flightNumber()));

        // 3. The entity owns the invariant. If the seat rule lived here, every
        //    other caller of Flight would be free to break it.
        flight.reserveSeats(request.seats());

        Booking booking = bookingRepository.save(new Booking(
                flight, request.passengerName(), request.seats(), request.idempotencyKey()));

        BookingDto dto = BookingDto.from(booking);
        log.info("Booked {} seat(s) on {} for {} (booking {}, {} seats left)",
                 booking.getSeats(), flightNumber, booking.getPassengerName(),
                 booking.getId(), flight.getAvailableSeats());

        // 4. Publish while still inside the transaction — see SqsEventPublisher
        //    for why that is a deliberate, documented trade-off rather than an
        //    oversight, and what the outbox alternative buys.
        eventPublisher.publishBookingCreated(dto);
        return dto;
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
