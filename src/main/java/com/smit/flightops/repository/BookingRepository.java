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
    @Query(value = "SELECT b FROM Booking b JOIN FETCH b.flight f WHERE f.flightNumber = :fn",
           countQuery = "SELECT count(b) FROM Booking b WHERE b.flight.flightNumber = :fn")
    Page<Booking> findByFlightNumber(@Param("fn") String flightNumber, Pageable pageable);

    /**
     * The flight number for a booking, without loading either entity.
     *
     * <p>Exists purely so {@link #findByIdForUpdate} can be reached in the
     * right lock order — see {@code BookingWriter.cancelBooking}. Loading the
     * {@code Booking} entity here instead would put it in the persistence
     * context before the flight row is locked, and the later re-read would
     * return that same stale instance rather than going to the database.
     */
    @Query("SELECT b.flight.flightNumber FROM Booking b WHERE b.id = :id")
    Optional<String> findFlightNumberById(@Param("id") Long id);

    /**
     * {@code SELECT ... FOR UPDATE} on one booking row.
     *
     * <p>Used by the cancellation path after the flight row is already locked.
     * The flight lock alone is enough to serialise two concurrent cancels of
     * the same booking under READ COMMITTED, because the second transaction's
     * read happens after the first has committed. This lock is the explicit
     * version of that argument: it makes the intent visible at the call site
     * rather than resting on an isolation level somebody could change in a
     * config file.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);
}
