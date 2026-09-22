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
import java.util.Collection;
import java.util.List;
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

    // Not called by the API: kept, with FlightRepositoryTest, as examples of a derived,
    // an enum-driven IN and a native query.

    List<Flight> findByOriginAndDestination(String origin, String destination);

    List<Flight> findByStatusAndDepartureTimeBetween(FlightStatus s, Instant from, Instant to);

    // The status list comes from FlightStatus.bookableStatuses(), so the query cannot
    // disagree with the entity about what is bookable.
    @Query("SELECT f FROM Flight f WHERE f.availableSeats >= :min AND f.status IN :statuses")
    List<Flight> findBookable(@Param("min") int min,
                              @Param("statuses") Collection<FlightStatus> statuses);

    default List<Flight> findBookable(int min) {
        return findBookable(min, FlightStatus.bookableStatuses());
    }

    // Native, so it trades portability (LIMIT is not Oracle syntax) for control. No
    // status or time filter: departed and cancelled flights are included.
    @Query(value = "SELECT * FROM flights WHERE origin = :o ORDER BY departure_time LIMIT 10",
           nativeQuery = true)
    List<Flight> findNextTen(@Param("o") String origin);
}
