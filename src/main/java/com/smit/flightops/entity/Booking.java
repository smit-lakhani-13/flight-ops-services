package com.smit.flightops.entity;

import jakarta.persistence.*;

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

    protected Booking() {}

    public Booking(Flight flight, String passengerName, int seats, String idempotencyKey) {
        this.flight = flight; this.passengerName = passengerName;
        this.seats = seats;   this.idempotencyKey = idempotencyKey;
    }

    public Long getId() { return id; }
    public Flight getFlight() { return flight; }
    public String getPassengerName() { return passengerName; }
    public int getSeats() { return seats; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
}
