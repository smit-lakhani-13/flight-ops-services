package com.smit.flightops.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
// No @Index for idempotencyKey: the @Column(unique = true) below already causes
// ddl-auto to emit a unique constraint on that column, and a unique constraint is
// backed by an index. Declaring the @Index as well asks for a second one on the
// same column — write cost paid twice for no read benefit. On PostgreSQL none of
// this applies: V1__init.sql owns the schema (uk_bookings_idempotency_key) and
// Hibernate is set to validate, not generate.
@Table(name = "bookings")
// Mirrors ck_bookings_seats_positive from V2 into the H2 schema; see Flight for
// why the checks are declared in both places.
@Check(name = "ck_bookings_seats_positive", constraints = "seats > 0")
public class Booking {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    @Column(nullable = false) private String passengerName;
    @Column(nullable = false) private int seats;

    /** Exactly-once guarantee on a money path. */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * Truncated to microseconds because that is all PostgreSQL stores. Without
     * this, the in-memory value carries nanoseconds and differs from the value
     * read back — and the downstream DynamoDB sort key is built from it.
     */
    @Column(nullable = false) private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    /**
     * SHA-256 of the request that created this booking, from
     * {@code BookingRequest.fingerprint()}. Compared on every idempotent replay
     * so that the same key used for a *different* booking is a 409 instead of a
     * silent 201 returning somebody else's reservation.
     *
     * <p>Nullable on purpose, and it is the migration that forces it: rows
     * written before V3 have no fingerprint and there is nothing honest to
     * backfill. NULL means "cannot compare", and {@code BookingService} treats
     * such a row as a plain replay — the old behaviour, for old rows only.
     * A {@code NOT NULL} column with a sentinel would have meant inventing a
     * hash for data nobody hashed.
     */
    @Column(length = 64) private String requestFingerprint;

    /**
     * When this booking was cancelled and its seats returned to the flight.
     * NULL means active.
     *
     * <p>This field is the idempotency guard for the refund, not just a record
     * of it. {@code Flight.releaseSeats} clamps to {@code totalSeats}, which
     * bounds a double release but does not prevent one: cancelling twice on a
     * 180-seat flight with 100 sold would still invent two seats out of
     * nothing. {@link #cancel()} refuses the second call instead.
     *
     * <p>No index on this column, on either database, and that is now the
     * considered answer rather than a gap. V4 created a partial
     * {@code idx_bookings_active} on {@code (flight_id) WHERE cancelled_at IS
     * NULL}; V8 drops it. PostgreSQL will only choose a partial index when the
     * query repeats its predicate, and no query here does — the bookings list
     * deliberately returns cancelled rows too, because a cancelled booking
     * keeps its idempotency key and has to be replayable. An index nothing
     * reads is write cost on every insert and a line in every VACUUM.
     */
    @Column private Instant cancelledAt;

    protected Booking() {}

    public Booking(Flight flight, String passengerName, int seats,
                   String idempotencyKey, String requestFingerprint) {
        this.flight = flight; this.passengerName = passengerName;
        this.seats = seats;   this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }

    public Long getId() { return id; }
    public Flight getFlight() { return flight; }
    public String getPassengerName() { return passengerName; }
    public int getSeats() { return seats; }
    public String getIdempotencyKey() { return idempotencyKey; }
    /**
     * Cancels the booking, returning true if this call is the one that did it.
     *
     * <p>The return value is the whole point. The caller
     * ({@code BookingService.cancel}) credits the seats back to the flight only
     * when this returns true, so a retried {@code DELETE} is a no-op rather
     * than a second refund. Returning void and letting the caller check
     * {@code isCancelled()} first would put the read and the write in the
     * caller's hands and reintroduce the check-then-act the booking path spends
     * its whole length avoiding.
     */
    public boolean cancel(Instant when) {
        if (cancelledAt != null) {
            return false;
        }
        this.cancelledAt = when.truncatedTo(ChronoUnit.MICROS);
        return true;
    }

    public boolean isCancelled() { return cancelledAt != null; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getRequestFingerprint() { return requestFingerprint; }

    /**
     * Whether this booking was created by a request identical to {@code other}.
     *
     * <p>Answers true when the fingerprint is unknown. That is the deliberate
     * choice for pre-V2 rows described on the field: a booking we cannot
     * compare is treated as a match, so an old key keeps behaving the way it
     * did rather than starting to return 409 after a deployment.
     */
    public boolean matchesRequest(String otherFingerprint) {
        return requestFingerprint == null || requestFingerprint.equals(otherFingerprint);
    }
}
