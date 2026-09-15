package com.smit.flightops.repository;

import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightNumber(String flightNumber);

    List<Flight> findByOriginAndDestination(String origin, String destination);

    List<Flight> findByStatusAndDepartureTimeBetween(FlightStatus s, Instant from, Instant to);

    boolean existsByFlightNumber(String flightNumber);

    @Query("SELECT f FROM Flight f WHERE f.availableSeats >= :min AND f.status = 'SCHEDULED'")
    List<Flight> findBookable(@Param("min") int min);

    // LIMIT is not Oracle syntax. Native queries trade portability for control —
    // the JPQL/derived equivalents above run unchanged on Oracle and PostgreSQL.
    @Query(value = "SELECT * FROM flights WHERE origin = :o ORDER BY departure_time LIMIT 10",
           nativeQuery = true)
    List<Flight> findNextTen(@Param("o") String origin);

    /**
     * SELECT ... FOR UPDATE. Serialises the read-modify-write on a single
     * flight's seat count, which is where last-seat contention actually lives.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Flight f WHERE f.flightNumber = :fn")
    Optional<Flight> findByFlightNumberForUpdate(@Param("fn") String fn);

    // Paged search. Four explicit derived queries instead of one query with
    // ":origin IS NULL OR ..." — that pattern makes PostgreSQL unable to infer
    // the parameter type, and it defeats the idx_origin_dest index.
    Page<Flight> findByOriginAndDestination(String origin, String destination, Pageable pageable);

    Page<Flight> findByOrigin(String origin, Pageable pageable);

    Page<Flight> findByDestination(String destination, Pageable pageable);
}
