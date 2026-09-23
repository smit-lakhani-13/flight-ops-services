package com.smit.flightops.entity;

import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.IllegalFlightTransitionException;
import com.smit.flightops.exception.InsufficientSeatsException;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
// No @Index for flightNumber: unique = true already gives it one, as in Booking.
@Table(name = "flights", indexes = {
    @Index(name = "idx_origin_dest", columnList = "origin,destination")
})
// The four checks V2__seat_and_route_invariants.sql adds to PostgreSQL, declared so
// the create-drop H2 schema has them too and a test cannot pass by breaking one. V2
// is the source of truth; this list only shapes the H2 schema. ddl-auto: validate does
// not compare check constraints, so nothing catches this list drifting from V2;
// change both together.
@Checks({
    @Check(name = "ck_flights_seat_floor", constraints = "available_seats >= 0"),
    @Check(name = "ck_flights_seat_ceiling", constraints = "available_seats <= total_seats"),
    @Check(name = "ck_flights_capacity", constraints = "total_seats > 0"),
    @Check(name = "ck_flights_distinct_endpoints", constraints = "origin <> destination")
})
public class Flight {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 10)
    private String flightNumber;

    @Column(nullable = false, length = 3) private String origin;
    @Column(nullable = false, length = 3) private String destination;

    @Column(nullable = false)             private int totalSeats;
    @Column(nullable = false)             private int availableSeats;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FlightStatus status = FlightStatus.SCHEDULED;

    @Column(nullable = false) private Instant departureTime;

    /** Optimistic locking: a conflicting concurrent commit fails instead of overwriting the other write. */
    @Version
    private Long version;

    protected Flight() {}

    public Flight(String flightNumber, String origin, String destination,
                  int totalSeats, Instant departureTime) {
        this.flightNumber = flightNumber;
        this.origin = origin;
        this.destination = destination;
        this.totalSeats = totalSeats;
        this.availableSeats = totalSeats;
        // TIMESTAMP(6) stores microseconds. Truncated here, so the 201 shows the time a GET returns.
        this.departureTime = departureTime.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Debits seats, refusing a flight that is not bookable before one that is short of
     * seats: "flight is CANCELLED" stops a client retrying, "not enough seats" does not.
     * Guarded here, not in {@code BookingService}, for the reason {@link FlightStatus}'s
     * class Javadoc gives: a second caller would forget it.
     */
    public void reserveSeats(int count) {
        if (count <= 0) throw new IllegalArgumentException("count must be positive");
        if (!status.isBookable())
            throw new FlightNotBookableException(flightNumber, status);
        if (availableSeats < count)
            throw new InsufficientSeatsException(flightNumber, count, availableSeats);
        this.availableSeats -= count;
    }

    /**
     * Not status-guarded, because refunds happen on cancelled flights. The clamp stops a
     * double release inflating capacity past {@code totalSeats}.
     */
    public void releaseSeats(int count) {
        this.availableSeats = Math.min(totalSeats, availableSeats + count);
    }

    /**
     * Applies a status change, if {@link FlightStatus#canTransitionTo} allows it. Enforced
     * here, not in {@code FlightService}, for the reason {@link FlightStatus}'s class
     * Javadoc gives: a second caller would forget.
     *
     * @throws IllegalFlightTransitionException if the flight cannot reach
     *         {@code newStatus} from where it is now
     */
    public void updateStatus(FlightStatus newStatus) {
        Objects.requireNonNull(newStatus, "status must not be null");
        if (!status.canTransitionTo(newStatus)) {
            throw new IllegalFlightTransitionException(flightNumber, status, newStatus);
        }
        this.status = newStatus;
    }

    /**
     * Soft cancel: bookings still reference this row, so it is never deleted. Goes
     * through {@link #updateStatus}, so an arrived flight cannot be cancelled, and a
     * repeated cancel is a no-op, which keeps {@code DELETE} safe to retry.
     */
    public void cancel() {
        updateStatus(FlightStatus.CANCELLED);
    }

    public Long getId() { return id; }
    public String getFlightNumber() { return flightNumber; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public int getTotalSeats() { return totalSeats; }
    public int getAvailableSeats() { return availableSeats; }
    public FlightStatus getStatus() { return status; }
    public Instant getDepartureTime() { return departureTime; }
}
