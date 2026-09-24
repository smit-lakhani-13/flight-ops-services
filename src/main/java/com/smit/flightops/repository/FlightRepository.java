package com.smit.flightops.repository;

import com.smit.flightops.entity.Flight;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightNumber(String flightNumber);

    boolean existsByFlightNumber(String flightNumber);

    /** {@code SELECT ... FOR UPDATE}: serialises the read-modify-write on one flight's seat count. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Flight f WHERE f.flightNumber = :fn")
    Optional<Flight> findByFlightNumberForUpdate(@Param("fn") String fn);

    // Paged search. Three explicit derived queries (plus the inherited findAll) instead
    // of one query with ":origin IS NULL OR ...", which PostgreSQL cannot type and
    // which defeats the idx_origin_dest index.
    Page<Flight> findByOriginAndDestination(String origin, String destination, Pageable pageable);

    Page<Flight> findByOrigin(String origin, Pageable pageable);

    Page<Flight> findByDestination(String destination, Pageable pageable);
}
