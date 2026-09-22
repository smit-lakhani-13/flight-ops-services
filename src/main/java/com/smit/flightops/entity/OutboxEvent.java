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
 * One event, recorded in the same transaction as the change that caused it.
 *
 * <p>See {@code V5__outbox.sql} for why the table exists at all; this class is
 * the part of that story the compiler can enforce.
 *
 * <p>The setters are package-private and there is no general-purpose one. A row
 * has exactly two legal transitions after it is written — published, or failed
 * another attempt — and they are the two methods below. Exposing
 * {@code setPublishedAt} would make "mark it published without ever sending
 * it" a one-line mistake in a class nobody reviews twice.
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
     * An arbitrary-length JSON document, rather than something with a column
     * width somebody has to guess and then raise in a migration the day a field
     * is added.
     *
     * <p><b>Deliberately not {@code @Lob}, and the reason is the sharpest schema
     * trap in this repository.</b> {@code @Lob} on a {@code String} resolves to
     * {@code SqlTypes.CLOB}, and PostgreSQL's dialect maps {@code CLOB} to the
     * column type {@code oid} - a pointer into {@code pg_largeobject}, not
     * inline text. {@code V5__outbox.sql} creates this column as {@code TEXT},
     * so {@code ddl-auto: validate} compares {@code text (Types#VARCHAR)}
     * against {@code oid (Types#CLOB)}, refuses to match, and the application
     * context fails to refresh. Every replica would have entered {@code
     * CrashLoopBackOff} - not a broken outbox, a service that never starts.
     *
     * <p>It is invisible locally, which is what makes it worth a comment this
     * long: H2 runs {@code ddl-auto: create-drop}, so Hibernate generates the
     * column itself and the two can never disagree. The only test that runs
     * Flyway and {@code validate} against real PostgreSQL is {@code
     * BookingIntegrationTest}, and it skips without Docker.
     *
     * <p>{@code LONG32VARCHAR} is the explicit spelling of what was wanted all
     * along: PostgreSQL renders it as {@code text} and binds it as a plain
     * string, and H2 falls back to the same {@code clob} it already had. The
     * second-order win is that nothing goes through {@code
     * PreparedStatement.setClob} any more - that path creates a server-side
     * large object per write, and because this entity has no {@code
     * DynamicUpdate}, every {@code markPublished} would have rewritten the
     * payload and orphaned one.
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

    protected OutboxEvent() {
        // JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventType,
                       String payload, Instant createdAt) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.payload = payload;
        // Truncated for the same reason Booking.createdAt is: PostgreSQL
        // TIMESTAMP(6) stores microseconds and Instant holds nanoseconds, so an
        // untruncated value is not equal to itself after a round trip, and
        // every equality assertion against it becomes flaky on one database and
        // not the other.
        this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        this.attempts = 0;
    }

    /** Called only after the transport has accepted the payload. */
    public void markPublished(Instant when) {
        this.publishedAt = when.truncatedTo(ChronoUnit.MICROS);
        this.lastError = null;
    }

    /**
     * Records a failed send so the row is visible to an operator rather than
     * merely retried forever in silence.
     *
     * <p>The message is truncated to the column width here rather than left to
     * the database. An over-long value would otherwise fail the UPDATE, which
     * would roll back the attempt counter along with it — so the one row that
     * most needs its failure recorded would be the one row that never records
     * it, and the counter would stay at zero while the event retried forever.
     */
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null
                : error.length() <= 500 ? error
                : error.substring(0, 497) + "...";
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
