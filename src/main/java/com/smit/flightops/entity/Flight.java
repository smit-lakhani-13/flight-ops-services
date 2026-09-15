package com.smit.flightops.entity;

import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "flights", indexes = {
    @Index(name = "idx_flight_number", columnList = "flightNumber", unique = true),
    @Index(name = "idx_origin_dest",   columnList = "origin,destination")
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
     * Any status to any status. KNOWN LIMITATION, and a conscious one: a real
     * system would enforce a transition graph here (ARRIVED is terminal;
     * DEPARTED cannot go back to SCHEDULED). That is a state machine, and the
     * useful half of it — "can this flight be booked?" — is already covered by
     * {@link FlightStatus#isBookable()}, which is checked on the one path where
     * getting it wrong loses money.
     */
    public void updateStatus(FlightStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "status must not be null");
    }

    /** Soft cancel: bookings still reference this row, so it is never deleted. */
    public void cancel() {
        this.status = FlightStatus.CANCELLED;
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
