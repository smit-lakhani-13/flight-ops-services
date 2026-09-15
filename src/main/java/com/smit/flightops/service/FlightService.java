package com.smit.flightops.service;

import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.dto.FlightDto;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.exception.DuplicateFlightException;
import com.smit.flightops.exception.FlightNotFoundException;
import com.smit.flightops.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * Flight lifecycle. Every method is transactional at this layer — the
 * controller stays a thin HTTP adapter and the repository stays a thin data
 * adapter, so the transaction boundary sits exactly where the business
 * operation does.
 */
@Service
@Transactional(readOnly = true)
public class FlightService {

    private static final Logger log = LoggerFactory.getLogger(FlightService.class);

    private final FlightRepository flightRepository;

    public FlightService(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    public FlightDto findByNumber(String flightNumber) {
        return FlightDto.from(load(flightNumber));
    }

    /**
     * Paged search on any combination of origin and destination. Four explicit
     * branches instead of a Specification or a null-tolerant JPQL predicate:
     * each branch produces a query the planner can index, and the code says
     * plainly which index it expects to use.
     */
    public Page<FlightDto> search(String origin, String destination, Pageable pageable) {
        String o = normalise(origin);
        String d = normalise(destination);

        Page<Flight> page;
        if (o != null && d != null) {
            page = flightRepository.findByOriginAndDestination(o, d, pageable);
        } else if (o != null) {
            page = flightRepository.findByOrigin(o, pageable);
        } else if (d != null) {
            page = flightRepository.findByDestination(d, pageable);
        } else {
            page = flightRepository.findAll(pageable);
        }
        return page.map(FlightDto::from);
    }

    /**
     * The {@code existsByFlightNumber} check is a courtesy that turns the common
     * case into a clean 409. It is not the guarantee: two concurrent creates can
     * both pass it. The real guarantee is {@code uk_flights_flight_number}, whose
     * violation surfaces as a {@code DataIntegrityViolationException} and is
     * mapped to 409 in the exception handler.
     */
    @Transactional
    public FlightDto create(CreateFlightRequest request) {
        String flightNumber = normalise(request.flightNumber());
        if (flightRepository.existsByFlightNumber(flightNumber)) {
            throw new DuplicateFlightException(flightNumber);
        }

        Flight saved = flightRepository.save(new Flight(
                flightNumber,
                normalise(request.origin()),
                normalise(request.destination()),
                request.totalSeats(),
                request.departureTime()));

        log.info("Created flight {} ({} -> {}, {} seats)", saved.getFlightNumber(),
                 saved.getOrigin(), saved.getDestination(), saved.getTotalSeats());
        return FlightDto.from(saved);
    }

    /**
     * No explicit save: the entity is managed inside this transaction, so the
     * dirty check at flush time writes the UPDATE (and bumps {@code @Version}).
     */
    @Transactional
    public FlightDto updateStatus(String flightNumber, FlightStatus status) {
        Flight flight = load(flightNumber);
        FlightStatus previous = flight.getStatus();
        flight.updateStatus(status);
        log.info("Flight {} status {} -> {}", flight.getFlightNumber(), previous, status);
        return FlightDto.from(flight);
    }

    /**
     * Soft cancel. Bookings hold a foreign key to this row, so deleting it would
     * either fail or destroy booking history — the status transition is the
     * cancellation.
     */
    @Transactional
    public void cancel(String flightNumber) {
        Flight flight = load(flightNumber);
        flight.cancel();
        log.info("Flight {} cancelled", flight.getFlightNumber());
    }

    private Flight load(String flightNumber) {
        return flightRepository.findByFlightNumber(normalise(flightNumber))
                .orElseThrow(() -> new FlightNotFoundException(flightNumber));
    }

    /**
     * Airline codes are upper-case by convention, and the unique constraint is
     * case-sensitive — normalising on the way in stops {@code ua123} and
     * {@code UA123} from becoming two flights.
     */
    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
