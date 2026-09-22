package com.smit.flightops.entity;

import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.IllegalFlightTransitionException;
import com.smit.flightops.exception.InsufficientSeatsException;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.Checks;

import java.time.Instant;
import java.util.Objects;

@Entity
// No @Index for flightNumber: @Column(unique = true) below already makes
// ddl-auto emit a unique constraint, and every database backs that with an
// index. Declaring both asks for a second index on the same column — write
// cost paid twice for no read benefit. Same reasoning as Booking.java.
@Table(name = "flights", indexes = {
    @Index(name = "idx_origin_dest", columnList = "origin,destination")
})
// The same four checks V2__seat_and_route_invariants.sql adds to PostgreSQL,
// declared here so the H2 schema Hibernate generates for the default profile
// and the @DataJpaTest slices has them too. Without this the constraints would
// exist only in the profile nobody develops against, and a test could pass on
// H2 while violating the production schema. ddl-auto: validate does not compare
// check constraints, so there is no drift risk in the other direction.
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

    /** Optimistic locking: a conflicting concurrent commit fails instead of silently overwriting. */
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
        this.departureTime = departureTime;
    }

    /**
     * Business invariants live on the entity — can't be bypassed.
     *
     * <p>Two of them, and the order matters. The status check is first because it
     * is the more useful answer: told "not enough seats" on a cancelled flight a
     * client retries with fewer seats, forever. Told "flight is CANCELLED" it
     * stops.
     *
     * <p>The status check is a regression guard. Without it, {@code DELETE
     * /api/v1/flights/UA123} soft-cancels the flight and a booking posted
     * immediately afterwards still returns 201 and decrements availableSeats —
     * a seat sold on a flight that is not going anywhere. It is guarded here
     * rather than in {@code BookingService} for the reason the class comment
     * gives: a second caller would forget it. See
     * {@link FlightStatus#isBookable()}.
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
     * Deliberately NOT status-guarded. Releasing seats back is the refund path,
     * and refunds happen precisely on the flights that were cancelled. The
     * {@code min(totalSeats, ...)} clamp is the invariant that matters here: a
     * double-release must not inflate capacity past the aircraft.
     */
    public void releaseSeats(int count) {
        this.availableSeats = Math.min(totalSeats, availableSeats + count);
    }

    /**
     * Applies a status change, if the lifecycle allows it.
     *
     * <p>This method used to accept any status from any status, and its Javadoc
     * called the missing transition graph a conscious limitation on the grounds
     * that {@link FlightStatus#isBookable()} already covered the case where
     * getting it wrong loses money. That was wrong, and the hole was one PATCH
     * wide: {@code CANCELLED -> SCHEDULED} made {@code isBookable()} start
     * answering true again, putting the seats of a cancelled flight back on
     * sale. The guard against overselling a cancelled flight was being enforced
     * by a field that any caller could set to anything.
     *
     * <p>Enforced on the entity rather than in {@code FlightService} for the
     * reason the class comment gives: a second caller would forget.
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
     * Soft cancel: bookings still reference this row, so it is never deleted.
     *
     * <p>Goes through {@link #updateStatus} rather than assigning the field, so
     * cancelling an already-arrived flight is refused here too. Cancelling an
     * already-cancelled flight is allowed and does nothing, which keeps {@code
     * DELETE /api/v1/flights/{n}} safe to retry.
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
