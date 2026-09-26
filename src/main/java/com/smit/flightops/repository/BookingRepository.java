package com.smit.flightops.repository;

import com.smit.flightops.entity.Booking;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * JOIN FETCH, not a derived query: {@code Booking.flight} is LAZY, and the replay
     * check in {@code BookingService.book} maps the result to a {@code BookingDto} with
     * no transaction open at all. A proxy would throw {@code LazyInitializationException}.
     */
    @Query("SELECT b FROM Booking b JOIN FETCH b.flight WHERE b.idempotencyKey = :key")
    Optional<Booking> findByIdempotencyKey(@Param("key") String idempotencyKey);

    /**
     * JOIN FETCH: {@code BookingService.findByFlightNumber} maps the page to DTOs with
     * no transaction open, so a lazy {@code Booking.flight} would throw
     * {@code LazyInitializationException}.
     */
    @Query(value = "SELECT b FROM Booking b JOIN FETCH b.flight f WHERE f.flightNumber = :fn",
           countQuery = "SELECT count(b) FROM Booking b WHERE b.flight.flightNumber = :fn")
    Page<Booking> findByFlightNumber(@Param("fn") String flightNumber, Pageable pageable);

    /**
     * The flight number for a booking, without loading either entity, so
     * {@code BookingWriter.cancelBooking} can lock the flight row first. Loading the
     * {@code Booking} here would leave a stale instance in the persistence context.
     */
    @Query("SELECT b.flight.flightNumber FROM Booking b WHERE b.id = :id")
    Optional<String> findFlightNumberById(@Param("id") Long id);

    /**
     * {@code SELECT ... FOR UPDATE} on one booking row, taken after the flight lock. Under
     * READ COMMITTED, which the pool pins, the flight lock already serialises two cancels;
     * this makes that explicit instead of leaving it to the isolation level alone.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);
}
