package com.smit.flightops.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * One event, recorded in the same transaction as the change that caused it
 * (see {@code V5__outbox.sql} for why the table exists).
 *
 * <p>There are no setters. After a row is written its only transitions are
 * {@link #markPublished} and {@link #markFailed}, so marking a row published
 * without sending it is not a one-line mistake.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    /**
     * The JSON document, of any length.
     *
     * <p>Not {@code @Lob}: on PostgreSQL that maps to {@code oid}, {@code V5__outbox.sql}
     * creates {@code TEXT}, and {@code ddl-auto: validate} would refuse to start the
     * application. H2 runs {@code create-drop}, so it never shows locally. The tests
     * that run Flyway and {@code validate} against real PostgreSQL are
     * {@code BookingIntegrationTest}, {@code OutboxPrunePostgresTest},
     * {@code LockTimeoutPostgresTest} and {@code SchemaConstraintsPostgresTest}; all
     * four skip without Docker.
     * {@code LONG32VARCHAR} renders as {@code text} on PostgreSQL and binds as a plain
     * string, so no large object is created per write.
     */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** NULL until the poller has actually handed the payload to the transport. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * When this row becomes claimable again, or null for "now". Why retries are
     * spaced is in {@code V7__outbox_next_attempt_at.sql}.
     */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /**
     * The W3C trace context of the request that produced this event. Null for rows
     * written before {@code V6} and for bookings made outside a traced request.
     * 55 is the exact length of a traceparent; a longer value is not one.
     */
    @Column(length = 55)
    private String traceparent;

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
                       String payload, Instant createdAt) {
        this(aggregateType, aggregateId, eventType, payload, createdAt, null);
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
                       String payload, Instant createdAt, String traceparent) {
        this.traceparent = traceparent;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        // PostgreSQL TIMESTAMP(6) stores microseconds, so a nanosecond Instant
        // would not equal itself after a round trip.
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.attempts = 0;
    }

    /** Called only after the transport has accepted the payload. */
    public void markPublished(Instant when) {
        this.publishedAt = when.truncatedTo(ChronoUnit.MICROS);
        this.lastError = null;
    }

    /**
     * Records a failed send. The message is truncated here to the 500-character
     * column: an over-long value would fail the UPDATE and roll back the attempt
     * counter with it.
     */
    public void markFailed(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.length() <= 500 ? error
                : error.substring(0, 497) + "...";
        // Null means "claimable on the next tick" (backoff switched off).
        this.nextAttemptAt = nextAttemptAt;
    }

    /** When this row may be claimed again; null means immediately. */
    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    /** Null when the booking was not made inside a traced request. */
    public String getTraceparent() {
        return traceparent;
    }

    public Long getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }
}
