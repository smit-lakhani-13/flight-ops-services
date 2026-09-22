package com.smit.flightops.service;

import com.smit.flightops.dto.BookingCreatedEvent;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Records an event in the outbox table, in the caller's transaction.
 *
 * <p>The payload is built and serialised here, when the booking happens, so it
 * describes what occurred. A booking cancelled two seconds later still publishes
 * the {@code BookingCreated} it was.
 */
@Component
public class OutboxWriter {

    static final String AGGREGATE_TYPE = "Booking";
    static final String BOOKING_CREATED = "BookingCreated";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;
    private final Propagator propagator;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository outboxEventRepository,
                        ObjectMapper objectMapper,
                        Tracer tracer,
                        Propagator propagator,
                        Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.tracer = tracer;
        this.propagator = propagator;
        this.clock = clock;
    }

    /**
     * {@code MANDATORY}, so a call outside a transaction throws
     * {@code IllegalTransactionStateException}. With the default {@code REQUIRED} the
     * save would open its own transaction and the event would no longer commit with
     * the booking, with nothing failing to show it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBookingCreated(BookingDto booking) {
        BookingCreatedEvent event = BookingCreatedEvent.from(booking);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            // Unchecked in Jackson 3; caught to name the event in the failure.
            throw new IllegalStateException("Failed to serialise " + event, e);
        }

        outboxEventRepository.save(new OutboxEvent(
                AGGREGATE_TYPE, String.valueOf(booking.bookingId()),
                BOOKING_CREATED, payload, clock.instant(), currentTraceparent()));
    }

    /**
     * The W3C trace context of the request making this booking, or null. Captured
     * here because the poller runs later on a scheduler thread, where the current
     * trace belongs to the drain. {@link Propagator#inject} gets the sampled flag
     * right, which hand formatting does not. More than 55 characters is not a
     * traceparent, and would fail the INSERT and roll back the booking.
     */
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>(2);
        propagator.inject(span.context(), carrier, Map::put);
        String traceparent = carrier.get("traceparent");
        return traceparent != null && traceparent.length() <= 55 ? traceparent : null;
    }
}
