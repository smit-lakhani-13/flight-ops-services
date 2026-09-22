package com.smit.flightops.service;

import com.smit.flightops.dto.BookingCreatedEvent;
import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.entity.OutboxEvent;
import com.smit.flightops.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Records an event in the outbox table, in the caller's transaction.
 *
 * <p>The event is built and serialised here, once, at the moment the booking
 * happened — not later by the poller. That is what makes the stored payload a
 * description of what occurred rather than of what the row happens to look like
 * whenever it is eventually sent. A booking that is cancelled two seconds after
 * it is made must still publish a {@code BookingCreated} describing the
 * booking, and a poller that re-derived the payload from the current row would
 * publish something else.
 */
@Component
public class OutboxWriter {

    static final String AGGREGATE_TYPE = "Booking";
    static final String BOOKING_CREATED = "BookingCreated";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxWriter(OutboxEventRepository outboxEventRepository,
                        ObjectMapper objectMapper,
                        Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * {@code Propagation.MANDATORY} is the most important annotation in this
     * class, and it is there to make one specific mistake impossible.
     *
     * <p>The entire value of an outbox comes from the event row committing
     * <em>with</em> the booking row. Called outside a transaction, this method
     * would still work perfectly: Spring Data would open its own transaction
     * for the {@code save}, the row would appear, every test would pass — and
     * the atomicity would be gone, silently, with nothing in the logs and
     * nothing failing. {@code MANDATORY} turns that into an
     * {@code IllegalTransactionStateException} at the first call. {@code
     * REQUIRED}, the default, is the version of this that looks identical and
     * quietly removes the guarantee.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordBookingCreated(BookingDto booking) {
        BookingCreatedEvent event = BookingCreatedEvent.from(booking);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            // Unchecked in Jackson 3, so this catch is a choice. Kept because a
            // serialisation failure is a bug in the event contract and needs to
            // surface with the event in the message rather than as a bare
            // Jackson stack trace from inside a transaction.
            throw new IllegalStateException("Failed to serialise " + event, e);
        }

        outboxEventRepository.save(new OutboxEvent(
                AGGREGATE_TYPE, String.valueOf(booking.bookingId()),
                BOOKING_CREATED, payload, clock.instant()));
    }
}
