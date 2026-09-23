package com.smit.flightops.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Entity
// No @Index for idempotencyKey: unique = true already gives it one. On PostgreSQL
// V1__init.sql owns the schema (uk_bookings_idempotency_key) and Hibernate validates.
@Table(name = "bookings")
// Mirrors ck_bookings_seats_positive from V2 into the H2 schema. Nothing checks the
// two copies agree, so change both together (see Flight).
@Check(name = "ck_bookings_seats_positive", constraints = "seats > 0")
public class Booking {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flight_id", nullable = false)
    private Flight flight;

    @Column(nullable = false) private String passengerName;
    @Column(nullable = false) private int seats;

    /**
     * Unique (uk_bookings_idempotency_key), so a retry with the same key gets this booking
     * back instead of creating a second one.
     */
    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    /**
     * Truncated to microseconds, all PostgreSQL stores; the DynamoDB sort key is built
     * from it. {@code BookingWriter} passes the injected Clock's instant, as it does for
     * {@link #cancel(Instant)}.
     */
    @Column(nullable = false) private Instant createdAt;

    /**
     * SHA-256 of the creating request, from {@code BookingRequest.fingerprint()}, so the
     * same key on a different booking is a 409. Nullable because rows written before V3
     * have none and there is nothing to backfill; see {@link #matchesRequest}.
     */
    @Column(length = 64) private String requestFingerprint;

    /**
     * When the booking was cancelled and its seats returned; NULL means active. It
     * guards the refund: {@link #cancel(Instant)} refuses a second call, where
     * {@code Flight.releaseSeats} only clamps. No index: V8 dropped V4's partial
     * {@code idx_bookings_active}, because no query repeats its predicate.
     */
    @Column private Instant cancelledAt;

    protected Booking() {}

    public Booking(Flight flight, String passengerName, int seats,
                   String idempotencyKey, String requestFingerprint, Instant createdAt) {
        this.flight = flight; this.passengerName = passengerName;
        this.seats = seats;   this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public Flight getFlight() { return flight; }
    public String getPassengerName() { return passengerName; }
    public int getSeats() { return seats; }
    public String getIdempotencyKey() { return idempotencyKey; }
    /**
     * Cancels the booking, returning true if this call did it. The caller
     * ({@code BookingWriter.cancelBooking}) credits seats only on true, so a retried
     * {@code DELETE} is a no-op and the check and the write stay in one place.
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
     * Whether this booking was created by a request with {@code otherFingerprint}. True
     * when the fingerprint is unknown, so pre-V3 rows keep replaying as they did.
     */
    public boolean matchesRequest(String otherFingerprint) {
        return requestFingerprint == null || requestFingerprint.equals(otherFingerprint);
    }
}
