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
 * Flight lifecycle. Every method is transactional here, so the transaction boundary
 * sits where the business operation does and the controller and repository stay thin.
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
     * Paged search on any combination of origin and destination. Four explicit branches,
     * each a query the planner can index, instead of a null-tolerant predicate.
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
     * {@code existsByFlightNumber} turns the common duplicate into 409
     * {@code DUPLICATE_FLIGHT}. Two concurrent creates can both pass it; the loser then
     * hits {@code uk_flights_flight_number} and gets 409 {@code DUPLICATE_REQUEST} from
     * the exception handler, and its retry gets {@code DUPLICATE_FLIGHT}. I left that
     * as it is: mapping the violation here would change an error code, which the
     * changelog treats as a major change.
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

    /** No explicit save: dirty checking writes the UPDATE and bumps {@code @Version}. */
    @Transactional
    public FlightDto updateStatus(String flightNumber, FlightStatus status) {
        Flight flight = load(flightNumber);
        FlightStatus previous = flight.getStatus();
        flight.updateStatus(status);
        log.info("Flight {} status {} -> {}", flight.getFlightNumber(), previous, status);
        return FlightDto.from(flight);
    }

    /** Soft cancel: bookings hold a foreign key to this row, so it is never deleted. */
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

    /** Upper-cased because the unique constraint is case-sensitive: ua123 is UA123. */
    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
