package com.smit.flightops.repository;

import com.smit.flightops.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    /**
     * JOIN FETCH, not a derived query: Booking.flight is LAZY, so mapping N
     * bookings to DTOs would otherwise fire N extra SELECTs (the N+1 problem).
     */
    @Query("SELECT b FROM Booking b JOIN FETCH b.flight f WHERE f.flightNumber = :fn ORDER BY b.createdAt")
    List<Booking> findByFlightNumber(@Param("fn") String flightNumber);
}
