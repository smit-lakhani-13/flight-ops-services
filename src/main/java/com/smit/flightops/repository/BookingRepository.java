package com.smit.flightops.repository;

import com.smit.flightops.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /**
     * JOIN FETCH, not a derived query: {@code Booking.flight} is LAZY, and
     * both callers of this method — the replay check and the race-recovery
     * read in {@code BookingService}/{@code BookingWriter} — map the result
     * straight to a {@code BookingDto} outside the transaction that read it.
     * A derived query would hand back an unfetchable proxy the moment that
     * transaction closes, which is exactly the {@code LazyInitializationException}
     * this fixes: {@code BookingService.book} stopped being {@code @Transactional}
     * itself when the idempotency-race fix split the write path out to
     * {@code BookingWriter}, so nothing keeps a session open past this call
     * unless the fetch happens inside it.
     */
    @Query("SELECT b FROM Booking b JOIN FETCH b.flight WHERE b.idempotencyKey = :key")
    Optional<Booking> findByIdempotencyKey(@Param("key") String idempotencyKey);

    /**
     * JOIN FETCH, not a derived query: Booking.flight is LAZY, so mapping N
     * bookings to DTOs would otherwise fire N extra SELECTs (the N+1 problem).
     */
    @Query("SELECT b FROM Booking b JOIN FETCH b.flight f WHERE f.flightNumber = :fn ORDER BY b.createdAt")
    List<Booking> findByFlightNumber(@Param("fn") String flightNumber);
}
